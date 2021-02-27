package app.redwarp.gif.decoder.descriptors

import net.redwarp.gif.decoder.streams.SeekableInputStream

class GifDescriptor(
    val header: Header,
    val logicalScreenDescriptor: LogicalScreenDescriptor,
    val globalColorTable: IntArray?,
    val loopCount: Int?,
    val imageDescriptors: List<ImageDescriptor>,
    val data: SeekableInputStream
)
