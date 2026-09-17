package dev.rabbit.trackerbridge

/**
 * Arma cuadros JPEG a partir de los payloads UVC que llegan por un endpoint bulk.
 *
 * La entrada se trata como flujo de bytes: si un payload final mide justo un multiplo del tamano
 * de paquete USB y la camara no manda paquete de largo cero, la lectura se junta con el payload
 * siguiente. Por eso los payloads intermedios se cortan por tamano ([maxPayload]) y el final por
 * el marcador JPEG de fin de imagen (FF D9).
 */
class UvcPayloadParser(
    private val maxPayload: Int,
    initialFrameCapacity: Int,
    private val onFrame: (ByteArray) -> Unit,
    private val onDropped: () -> Unit = {},
) {
    private var stream = ByteArray(maxPayload * 2)
    private var len = 0
    private var frame = ByteArray(initialFrameCapacity)
    private var frameLen = 0
    private var frameError = false
    private var errorCause = ""
    private var lastFid = -1
    private var discardUntilBoundary = false

    var lastFrameNanos = 0L
        private set

    /** Por que se descarto el ultimo cuadro invalido (solo para el registro). */
    var lastDropReason = ""
        private set

    /** Cuadros validos armados desde el inicio (para estadisticas). */
    var validFrames = 0L
        private set

    /** [shortRead]: la lectura termino en un paquete corto, o sea en el borde de un payload. */
    fun feed(data: ByteArray, n: Int, shortRead: Boolean) {
        if (n <= 0) return
        if (discardUntilBoundary) {
            if (shortRead) discardUntilBoundary = false
            return
        }
        if (len + n > stream.size) stream = stream.copyOf(maxOf(stream.size * 2, len + n))
        System.arraycopy(data, 0, stream, len, n)
        len += n
        parse(shortRead)
    }

    private fun parse(boundaryAtEnd: Boolean) {
        var s = 0
        while (len - s >= 2) {
            val avail = len - s
            val hle = stream[s].toInt() and 0xFF
            val bfh = stream[s + 1].toInt() and 0xFF
            if (hle < 2 || hle > 64 || (hle > avail && boundaryAtEnd)) {
                // Cabecera invalida: se perdio la sincronia; se descarta hasta el proximo borde.
                // Solo arruina el cuadro si ya habia uno a medias.
                if (frameLen > 0) markError("bad header")
                s = len
                if (!boundaryAtEnd) discardUntilBoundary = true
                break
            }
            if (avail < hle) break

            val fid = bfh and 0x01
            val eof = (bfh and 0x02) != 0
            if (lastFid >= 0 && fid != lastFid) finishFrame()
            lastFid = fid
            if ((bfh and 0x40) != 0) markError("camera error bit")

            val dataStart = s + hle
            val end: Int = if (!eof) {
                when {
                    avail >= maxPayload -> s + maxPayload
                    boundaryAtEnd -> len
                    else -> break
                }
            } else {
                val eoi = findEoi(dataStart, minOf(len, s + maxPayload))
                val afterEoi = eoi + 2
                when {
                    // Fin de imagen seguido del inicio del siguiente cuadro (lecturas juntas)
                    eoi >= 0 && (afterEoi == len || looksLikeNextHeader(afterEoi, fid)) -> afterEoi
                    boundaryAtEnd && avail <= maxPayload -> len
                    avail >= maxPayload -> s + maxPayload
                    boundaryAtEnd -> len
                    else -> break
                }
            }
            appendFrame(dataStart, end)
            s = end
            if (eof) finishFrame()
        }
        // En un borde de payload no puede quedar nada util a medias
        if (boundaryAtEnd) s = len
        if (s > 0) {
            System.arraycopy(stream, s, stream, 0, len - s)
            len -= s
        }
    }

    private fun findEoi(from: Int, to: Int): Int {
        var i = from
        while (i + 1 < to) {
            if (stream[i] == FF && stream[i + 1] == EOI) return i
            i++
        }
        return -1
    }

    /** Cabecera UVC plausible del cuadro siguiente: largo valido y FID cambiado. */
    private fun looksLikeNextHeader(pos: Int, currentFid: Int): Boolean {
        if (pos + 2 > len) return false
        val hle = stream[pos].toInt() and 0xFF
        val bfh = stream[pos + 1].toInt() and 0xFF
        return hle in 2..12 && (bfh and 0x01) != currentFid
    }

    private fun appendFrame(from: Int, to: Int) {
        val n = to - from
        if (n <= 0) return
        if (frameLen + n > frame.size) {
            if (frame.size >= MAX_FRAME) {
                markError("too big")
                return
            }
            frame = frame.copyOf(maxOf(frame.size * 2, frameLen + n))
        }
        System.arraycopy(stream, from, frame, frameLen, n)
        frameLen += n
    }

    private fun finishFrame() {
        // Algunas camaras rellenan despues del fin de imagen: recortar hasta el ultimo FF D9
        var end = frameLen
        val minEnd = maxOf(4, frameLen - 2048)
        while (end >= minEnd && !(frame[end - 2] == FF && frame[end - 1] == EOI)) end--
        val valid = !frameError && end >= minEnd && frame[0] == FF && frame[1] == SOI
        if (valid) {
            lastFrameNanos = System.nanoTime()
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
