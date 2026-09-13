package dev.rabbit.trackerbridge

/** Lo que necesitamos de los descriptores USB de una camara UVC. */
data class UvcFrameDesc(
    val index: Int,
    val width: Int,
    val height: Int,
    val defaultInterval: Int, // en unidades de 100 ns
    val intervals: List<Int>,
)

data class UvcStreamingEndpoint(
    val interfaceId: Int,
    val altSetting: Int,
    val address: Int,
    val isBulk: Boolean,
    val maxPacketSize: Int,
)

data class UvcInfo(
    val bcdUvc: Int,
    val controlInterfaceId: Int,
    val streamingInterfaceId: Int,
    val mjpegFormatIndex: Int,
    val mjpegFrames: List<UvcFrameDesc>,
    val endpoints: List<UvcStreamingEndpoint>,
) {
    /** Longitud de la estructura probe/commit segun la version UVC. */
    val probeLength: Int
        get() = when {
            bcdUvc >= 0x0150 -> 48
            bcdUvc >= 0x0110 -> 34
            else -> 26
        }

    fun bulkEndpoint(): UvcStreamingEndpoint? = endpoints.firstOrNull { it.isBulk }
}

object UvcDescriptors {
    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24

    private const val CLASS_VIDEO = 14
    private const val SUBCLASS_CONTROL = 1
    private const val SUBCLASS_STREAMING = 2

    private const val VC_HEADER = 0x01
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07
    private const val VS_FORMAT_FRAME_BASED = 0x10

    /** Devuelve null si el dispositivo no es una camara UVC con formato MJPEG. */
    fun parse(raw: ByteArray?): UvcInfo? {
        if (raw == null) return null
        var bcdUvc = 0x0100
        var controlIf = -1
        var streamingIf = -1
        var mjpegIndex = -1
        var inMjpegFormat = false
        val frames = mutableListOf<UvcFrameDesc>()
        val endpoints = mutableListOf<UvcStreamingEndpoint>()

        var ifId = -1
        var ifAlt = 0
        var ifClass = 0
        var ifSub = 0

        var i = 0
        while (i + 1 < raw.size) {
            val len = u8(raw, i)
            val type = u8(raw, i + 1)
            if (len < 2 || i + len > raw.size) break

            when (type) {
                DESC_INTERFACE -> if (len >= 9) {
                    ifId = u8(raw, i + 2)
                    ifAlt = u8(raw, i + 3)
                    ifClass = u8(raw, i + 5)
                    ifSub = u8(raw, i + 6)
                    if (ifClass == CLASS_VIDEO && ifSub == SUBCLASS_CONTROL && controlIf < 0) controlIf = ifId
                    if (ifClass == CLASS_VIDEO && ifSub == SUBCLASS_STREAMING && streamingIf < 0) streamingIf = ifId
                }

                DESC_ENDPOINT -> if (len >= 7 && ifClass == CLASS_VIDEO && ifSub == SUBCLASS_STREAMING && ifId == streamingIf) {
                    val address = u8(raw, i + 2)
                    val attributes = u8(raw, i + 3) and 0x03
                    if ((address and 0x80) != 0 && (attributes == 1 || attributes == 2)) {
                        endpoints += UvcStreamingEndpoint(
                            interfaceId = ifId,
                            altSetting = ifAlt,
                            address = address,
                            isBulk = attributes == 2,
                            maxPacketSize = u16(raw, i + 4) and 0x7FF,
                        )
                    }
                }

                DESC_CS_INTERFACE -> if (ifClass == CLASS_VIDEO && len >= 3) {
                    val subtype = u8(raw, i + 2)
                    if (ifSub == SUBCLASS_CONTROL && subtype == VC_HEADER && len >= 5) {
                        bcdUvc = u16(raw, i + 3)
                    } else if (ifSub == SUBCLASS_STREAMING) {
                        when (subtype) {
                            VS_FORMAT_MJPEG -> {
                                inMjpegFormat = mjpegIndex < 0
                                if (inMjpegFormat) mjpegIndex = u8(raw, i + 3)
                            }
                            VS_FORMAT_UNCOMPRESSED, VS_FORMAT_FRAME_BASED -> inMjpegFormat = false
                            VS_FRAME_MJPEG -> if (inMjpegFormat && len >= 26) {
                                val intervalType = u8(raw, i + 25)
                                val intervals = mutableListOf<Int>()
                                if (intervalType > 0) {
                                    for (k in 0 until intervalType) {
                                        val off = i + 26 + 4 * k
                                        if (off + 4 <= i + len) intervals += u32(raw, off)
                                    }
                                }
                                frames += UvcFrameDesc(
                                    index = u8(raw, i + 3),
                                    width = u16(raw, i + 5),
                                    height = u16(raw, i + 7),
                                    defaultInterval = u32(raw, i + 21),
                                    intervals = intervals,
                                )
                            }
                        }
                    }
                }
            }
            i += len
        }

        if (streamingIf < 0 || mjpegIndex < 0 || frames.isEmpty()) return null
        return UvcInfo(bcdUvc, controlIf, streamingIf, mjpegIndex, frames, endpoints)
    }

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)
    fun u32(b: ByteArray, off: Int): Int = u16(b, off) or (u16(b, off + 2) shl 16)

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v shr 8).toByte()
    }

    fun putU32(b: ByteArray, off: Int, v: Int) {
        putU16(b, off, v)
        putU16(b, off + 2, v shr 16)
    }
}
