package dev.rabbit.trackerbridge

/**
 * Arma cuadros JPEG a partir de paquetes isocronos UVC (webcams normales). A diferencia del modo bulk,
 * cada paquete que no esta vacio es un payload completo: cabecera UVC + datos. Un cuadro termina con
 * el bit EOF, o cuando cambia el FID si la camara no marca EOF.
 */
class IsoFrameAssembler(
    initialFrameCapacity: Int,
    private val onFrame: (ByteArray) -> Unit,
    private val onDropped: () -> Unit = {},
) {
    private var frame = ByteArray(initialFrameCapacity)
    private var frameLen = 0
    private var frameError = false
    private var errorCause = ""
    private var lastFid = -1
    private var batchNanos = 0L
    private var frameStartNanos = 0L

    /** Cuando llego el ultimo cuadro valido (el grupo de paquetes que lo completo). */
    var lastFrameNanos = 0L
        private set
    var validFrames = 0L
        private set

    /** Cuanto tardo el ultimo cuadro valido desde su primer paquete con datos hasta el ultimo. */
    var lastTransferNanos = 0L
        private set

    /** Bytes de imagen recibidos, sin cabeceras: sirve para saber si la camara sigue mandando algo. */
    var imageBytes = 0L
        private set

    /** Por que se descarto el ultimo cuadro invalido (solo para el registro). */
    var lastDropReason = ""
        private set

    /** Marca de tiempo del grupo de paquetes que se entrega a continuacion con [onPacket]. */
    fun beginBatch(nanos: Long) {
        batchNanos = nanos
    }

    /** [status] distinto de 0: el paquete llego con error y el cuadro en curso ya no sirve. */
    fun onPacket(data: ByteArray, offset: Int, length: Int, status: Int) {
        if (status != 0) {
            if (frameLen > 0) markError("USB packet error $status")
            return
        }
        // Paquete vacio: la camara no tenia datos en ese intervalo
        if (length < 2) return
        val hle = data[offset].toInt() and 0xFF
        val bfh = data[offset + 1].toInt() and 0xFF
        if (hle < 2 || hle > length || hle > 64) {
            if (frameLen > 0) markError("bad header")
            return
        }

        val fid = bfh and 0x01
        if (lastFid >= 0 && fid != lastFid) finishFrame()
        lastFid = fid
        if ((bfh and 0x40) != 0) markError("camera error bit")

        append(data, offset + hle, length - hle)
        if ((bfh and 0x02) != 0) finishFrame()
    }

    private fun append(data: ByteArray, from: Int, n: Int) {
        if (n <= 0) return
        if (frameLen == 0) frameStartNanos = batchNanos
        imageBytes += n
        if (frameLen + n > frame.size) {
            if (frameLen + n > MAX_FRAME) {
                markError("too big")
                return
            }
            frame = frame.copyOf(maxOf(frame.size * 2, frameLen + n))
        }
        System.arraycopy(data, from, frame, frameLen, n)
        frameLen += n
    }

    private fun finishFrame() {
        // Algunas camaras rellenan despues del fin de imagen: recortar hasta el ultimo FF D9
        var end = frameLen
        val minEnd = maxOf(4, frameLen - 2048)
        while (end >= minEnd && !(frame[end - 2] == FF && frame[end - 1] == EOI)) end--
        val valid = !frameError && end >= minEnd && frame[0] == FF && frame[1] == SOI
        if (valid) {
            lastFrameNanos = if (batchNanos != 0L) batchNanos else System.nanoTime()
            lastTransferNanos = if (frameStartNanos != 0L) lastFrameNanos - frameStartNanos else 0L
            validFrames++
            onFrame(frame.copyOf(end))
        } else if (frameLen > 0) {
            lastDropReason = when {
                frameError -> errorCause
                end < minEnd -> "no JPEG end"
                else -> "no JPEG start"
            }
            onDropped()
        }
        frameLen = 0
        frameError = false
    }

    private fun markError(cause: String) {
        if (!frameError) errorCause = cause
        frameError = true
    }

    private companion object {
        const val FF = 0xFF.toByte()
        const val SOI = 0xD8.toByte()
        const val EOI = 0xD9.toByte()
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}
