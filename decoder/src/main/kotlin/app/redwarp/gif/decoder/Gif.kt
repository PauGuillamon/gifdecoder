/* Copyright 2020 Benoit Vermont
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.redwarp.gif.decoder

import app.redwarp.gif.decoder.descriptors.Dimension
import app.redwarp.gif.decoder.descriptors.GifDescriptor
import app.redwarp.gif.decoder.descriptors.GraphicControlExtension
import app.redwarp.gif.decoder.descriptors.ImageDescriptor
import app.redwarp.gif.decoder.descriptors.LogicalScreenDescriptor
import app.redwarp.gif.decoder.descriptors.params.LoopCount
import app.redwarp.gif.decoder.descriptors.params.PixelPacking
import app.redwarp.gif.decoder.lzw.LzwDecoder
import app.redwarp.gif.decoder.utils.Palettes
import java.io.File
import java.io.InputStream

private const val TRANSPARENT_COLOR = 0x0

/**
 * Representation of the gif, with methods to decode frames.
 * This class's methods are not thread safe.
 */
class Gif(
    private val gifDescriptor: GifDescriptor
) {
    private var lastRenderedFrame: Int = -1
    private var frameIndex = 0
    private val framePixels = IntArray(gifDescriptor.logicalScreenDescriptor.dimension.size).apply {
        // Fill the frame with the background color, unless that is transparent,
        // as a new int array is already initialized to zero.
        if (backgroundColor != TRANSPARENT_COLOR) fill(backgroundColor)
    }
    private val scratch = ByteArray(gifDescriptor.logicalScreenDescriptor.dimension.size)
    private val rawScratch = ByteArray(gifDescriptor.imageDescriptors.maxOf { it.imageData.length })
    private val previousPixels: IntArray by lazy { IntArray(framePixels.size) }

    private val lzwDecoder: LzwDecoder = LzwDecoder()

    private val isTransparent: Boolean =
        gifDescriptor.imageDescriptors.any { it.graphicControlExtension?.transparentColorIndex != null }

    val currentIndex: Int get() = frameIndex

    /**
     * Returns the delay time of the current frame, in millisecond.
     * This delay represents how long we should show this frame before displaying the next one in the animation.
     * If the gif is not animated, returns zero.
     * Some animated GIFs have a specified delay of 0L, meaning we should draw the next frame as fast as possible.
     */
    val currentDelay: Long
        get() {
            return if (!isAnimated) {
                0L
            } else {
                val delay =
                    gifDescriptor.imageDescriptors[frameIndex].graphicControlExtension?.delayTime?.let {
                        it.toLong() * 10L
                    }

                delay ?: 0L
            }
        }

    val dimension: Dimension = gifDescriptor.logicalScreenDescriptor.dimension

    val frameCount: Int = gifDescriptor.imageDescriptors.size

    val loopCount: LoopCount = when (val count = gifDescriptor.loopCount) {
        null -> LoopCount.NoLoop
        0 -> LoopCount.Infinite
        else -> LoopCount.Fixed(count)
    }

    val aspectRatio: Double = run {
        val ratio = gifDescriptor.logicalScreenDescriptor.pixelAspectRatio.toInt() and 0xff
        if (ratio == 0) 1.0 else {
            (ratio + 15).toDouble() / 64.0
        }
    }

    val backgroundColor: Int =
        run {
            // If at last one of the frame is transparent, let's use transparent as the background color.
            if (isTransparent) {
                TRANSPARENT_COLOR
            } else {
                // First, look for the background color in the global color table if it exists. Default to transparent.
                gifDescriptor.logicalScreenDescriptor.backgroundColorIndex?.let {
                    gifDescriptor.globalColorTable?.get(it.toInt() and 0xff)
                } ?: TRANSPARENT_COLOR
            }
        }

    val isAnimated: Boolean = gifDescriptor.imageDescriptors.size > 1

    /**
     * Advance the frame index, and loop back to zero after the last frame.
     * Does not care about loop count.
     */
    fun advance() {
        if (isAnimated) {
            frameIndex = (currentIndex + 1) % frameCount
        }
    }

    /**
     * Write the current frame in the int array.
     * @param inPixels The buffer where the pixel will be written
     * @return true if a frame was successfully written.
     */
    fun getCurrentFrame(inPixels: IntArray): Boolean {
        synchronized(gifDescriptor) {
            return if (lastRenderedFrame == frameIndex) {
                // We are redrawing a previously managed frame
                framePixels.copyInto(inPixels)
                true
            } else {
                val didRender = getFrame(frameIndex, inPixels)
                if (didRender) {
                    lastRenderedFrame = frameIndex
                }
                didRender
            }
        }
    }

    fun getFrame(index: Int): IntArray? {
        val pixels = IntArray(gifDescriptor.logicalScreenDescriptor.dimension.size)
        return if (getFrame(index, pixels)) {
            pixels
        } else {
            null
        }
    }

    fun getFrame(index: Int, inPixels: IntArray): Boolean {
        val imageDescriptor = gifDescriptor.imageDescriptors[index]
        val colorTable =
            imageDescriptor.localColorTable ?: gifDescriptor.globalColorTable
                ?: Palettes.createFakeColorMap(
                    gifDescriptor.logicalScreenDescriptor.colorCount
                )

        val graphicControlExtension = imageDescriptor.graphicControlExtension

        val disposal = graphicControlExtension?.disposalMethod
            ?: GraphicControlExtension.Disposal.NOT_SPECIFIED

        if (disposal == GraphicControlExtension.Disposal.RESTORE_TO_PREVIOUS) {
            framePixels.copyInto(previousPixels)
        }

        try {
            gifDescriptor.data.use { stream ->
                stream.seek(imageDescriptor.imageData.position)
                stream.read(rawScratch, 0, imageDescriptor.imageData.length)
            }

            lzwDecoder.decode(imageData = rawScratch, scratch, framePixels.size)

            fillPixels(
                framePixels,
                scratch,
                colorTable,
                gifDescriptor.logicalScreenDescriptor,
                imageDescriptor
            )

            framePixels.copyInto(inPixels)

            when (disposal) {
                GraphicControlExtension.Disposal.RESTORE_TO_PREVIOUS -> {
                    previousPixels.copyInto(framePixels)
                }
                GraphicControlExtension.Disposal.NOT_SPECIFIED -> Unit // Unspecified, we do nothing.
                GraphicControlExtension.Disposal.DO_NOT_DISPOSE -> Unit // Do not dispose, we do nothing.
                GraphicControlExtension.Disposal.RESTORE_TO_BACKGROUND -> {
                    // Restore the section drawn for this frame to the background color.
                    val (frame_width, frame_height) = imageDescriptor.dimension
                    val (offset_x, offset_y) = imageDescriptor.position

                    for (line in 0 until frame_height) {
                        val startIndex = (line + offset_y) * dimension.width + offset_x
                        framePixels.fill(backgroundColor, startIndex, startIndex + frame_width)
                    }
                }
            }
            return true
        } catch (exception: Exception) {
            return false
        }
    }

    private fun fillPixels(
        pixels: IntArray,
        colorData: ByteArray,
        colorTable: IntArray,
        logicalScreenDescriptor: LogicalScreenDescriptor,
        imageDescriptor: ImageDescriptor
    ) {
        if (imageDescriptor.isInterlaced) {
            fillPixelsInterlaced(
                pixels,
                colorData,
                colorTable,
                logicalScreenDescriptor,
                imageDescriptor
            )
        } else {
            fillPixelsSimple(
                pixels,
                colorData,
                colorTable,
                logicalScreenDescriptor,
                imageDescriptor
            )
        }
    }

    private fun fillPixelsSimple(
        pixels: IntArray,
        colorData: ByteArray,
        colorTable: IntArray,
        logicalScreenDescriptor: LogicalScreenDescriptor,
        imageDescriptor: ImageDescriptor
    ) {
        val transparentColorIndex = imageDescriptor.graphicControlExtension?.transparentColorIndex
        val frameWidth = imageDescriptor.dimension.width
        val (offset_x, offset_y) = imageDescriptor.position
        val imageWidth = logicalScreenDescriptor.dimension.width

        for (index in 0 until imageDescriptor.dimension.size) {
            val colorIndex = colorData[index]
            if (colorIndex != transparentColorIndex) {
                val color = colorTable[colorIndex.toInt() and 0xff]
                val x = index % frameWidth
                val y = index / frameWidth
                val pixelIndex =
                    (y + offset_y) * imageWidth + offset_x + x
                pixels[pixelIndex] = color
            }
        }
    }

    private fun fillPixelsInterlaced(
        pixels: IntArray,
        colorData: ByteArray,
        colorTable: IntArray,
        logicalScreenDescriptor: LogicalScreenDescriptor,
        imageDescriptor: ImageDescriptor
    ) {
        val transparentColorIndex = imageDescriptor.graphicControlExtension?.transparentColorIndex
        val imageWidth = logicalScreenDescriptor.dimension.width
        val (frameWidth, frameHeight) = imageDescriptor.dimension
        val (offset_x, offset_y) = imageDescriptor.position
        var pass = 0
        var stride = 8
        var matchedLine = 0

        var lineIndex = 0
        while (pass < 4) {
            while (matchedLine < frameHeight) {
                val copyFromIndex = lineIndex * frameWidth
                val copyToIndex = (matchedLine + offset_y) * imageWidth + offset_x
                val indexOffset = copyToIndex - copyFromIndex

                for (index in copyFromIndex until copyFromIndex + frameWidth) {
                    val colorIndex = colorData[index]
                    if (colorIndex != transparentColorIndex) {
                        val color = colorTable[colorIndex.toInt() and 0xff]

                        val pixelIndex = index + indexOffset
                        pixels[pixelIndex] = color
                    }
                }

                lineIndex++
                matchedLine += stride
            }

            pass++
            when (pass) {
                1 -> {
                    matchedLine = 4
                    stride = 8
                }
                2 -> {
                    matchedLine = 2
                    stride = 4
                }
                3 -> {
                    matchedLine = 1
                    stride = 2
                }
            }
        }
    }

    companion object {
        fun from(
            file: File,
            pixelPacking: PixelPacking = PixelPacking.ARGB
        ): Result<Gif> = Parser.parse(file, pixelPacking).map(::Gif)

        fun from(
            inputStream: InputStream,
            pixelPacking: PixelPacking = PixelPacking.ARGB
        ): Result<Gif> = Parser.parse(inputStream, pixelPacking).map(::Gif)
    }
}
