package dev.rabbit.trackerbridge

/**
 * Saca cuadros JPEG del flujo de bytes de una placa conectada por puerto serie (OpenIris en modo
 * cableado sobre UART: ESP32-CAM y demas placas sin USB nativo).
 *
 * El firmware manda una cabecera propia (FF A0 FF A1 y el tamano) delante de cada cuadro, pero ni
 * ETVR ni Babble la usan: buscan el inicio (FF D8 FF) y el fin (FF D9) del JPEG. Aca se hace igual,
 * asi anda con cualquier firmware que mande JPEG por el cable, y ademas permite reengancharse en
 * cualquier momento, aunque la lectura empiece a mitad de un cuadro.
 */
class SerialFrameParser(
    private val maxFrame: Int = 1024 * 1024,
    private val onFrame: (ByteArray) -> Unit,
    private val onDropped: () -> Unit = {},
) {
    private var buf = ByteArray(32 * 1024)
    private var len = 0

    /** Donde empieza el cuadro que se esta armando, o -1 si todavia no aparecio el inicio. */
    private var start = -1

    /** Hasta donde ya se busco la marca que falta. */
    private var scan = 0

    /** Bytes de cuadros ya entregados que siguen en el buffer, para no contarlos como basura. */
    private var deliveredInBuffer = 0

    var validFrames = 0L
        private set
    var lastFrameNanos = 0L
        private set

    /** Bytes de JPEG entregados (sin la basura del medio), para las estadisticas. */
    var frameBytes = 0L
        private set

    /** Bytes que se tiraron por no ser parte de un cuadro: ruido, cabeceras o texto del firmware. */
    var skippedBytes = 0L
        private set

    /** Se vio la cabecera de OpenIris (FF A0 FF A1) antes de algun cuadro. */
    var openIrisHeader = false
        private set

    var lastDropReason = ""
        private set

    fun feed(data: ByteArray, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) {
            // Un cuadro entero tiene que entrar en el buffer; el limite lo pone maxFrame
            buf = buf.copyOf(maxOf(buf.size * 2, len + n).coerceAtMost(maxFrame + 64 * 1024))
            if (len + n > buf.size) {
                // No cabe: lo que hay a medias ya no sirve
                drop("too big")
                len = 0
                start = -1
                scan = 0
                deliveredInBuffer = 0
                if (n > buf.size) return
            }
        }
        System.arraycopy(data, 0, buf, len, n)
        len += n
        parse()
        compact()
    }

    private fun parse() {
        while (true) {
            if (start < 0) {
                val soi = indexOfSoi(scan)
                if (soi < 0) {
                    // Puede que la marca quede partida entre dos lecturas: guardar los ultimos bytes
                    scan = maxOf(0, len - 2)
                    return
                }
                if (hasOpenIrisHeader(soi)) openIrisHeader = true
                start = soi
                scan = soi + 3
            }
            val eoi = indexOfEoi(scan)
            if (eoi < 0) {
                scan = maxOf(start + 3, len - 1)
                if (len - start > maxFrame) {
                    drop("too big")
                    start = -1
                    scan = len
                }
                return
            }
            val end = eoi + 2
            frameBytes += (end - start).toLong()
            deliveredInBuffer += end - start
            validFrames++
            lastFrameNanos = System.nanoTime()
            onFrame(buf.copyOfRange(start, end))
            start = -1
            scan = end
        }
    }

    /** Deja al principio del buffer lo unico que puede servir: el cuadro a medias o los ultimos bytes. */
    private fun compact() {
        val keep = if (start >= 0) start else scan
        if (keep <= 0) return
        // Lo que se tira y no era parte de un cuadro es ruido: cabeceras, texto del firmware o basura
        skippedBytes += (keep - deliveredInBuffer).coerceAtLeast(0).toLong()
        deliveredInBuffer = 0
        System.arraycopy(buf, keep, buf, 0, len - keep)
        len -= keep
        if (start >= 0) start -= keep
        scan -= keep
    }

    private fun indexOfSoi(from: Int): Int {
        var i = maxOf(0, from)
        while (i + 2 < len) {
            if (buf[i] == FF && buf[i + 1] == SOI && buf[i + 2] == FF) return i
            i++
        }
        return -1
    }

    private fun indexOfEoi(from: Int): Int {
        var i = maxOf(0, from)
        while (i + 1 < len) {
            if (buf[i] == FF && buf[i + 1] == EOI) return i
            i++
        }
        return -1
    }

    /** Cabecera de OpenIris justo antes del cuadro: FF A0 FF A1 y dos bytes de tamano. */
    private fun hasOpenIrisHeader(soi: Int): Boolean =
        soi >= 6 && buf[soi - 6] == FF && buf[soi - 5] == HDR0 && buf[soi - 4] == FF && buf[soi - 3] == HDR1

    private fun drop(reason: String) {
        lastDropReason = reason
        onDropped()
    }

    companion object {
        private const val FF = 0xFF.toByte()
        private const val SOI = 0xD8.toByte()
        private const val EOI = 0xD9.toByte()
        private const val HDR0 = 0xA0.toByte()
        private const val HDR1 = 0xA1.toByte()

        /**
         * Tamano de la imagen, leido de la cabecera del JPEG (marcador SOF). Sirve para mostrar la
         * resolucion de las placas de serie, que no la declaran por USB como las camaras UVC.
         */
        fun jpegSize(jpeg: ByteArray): Pair<Int, Int>? {
            var i = 2
            while (i + 3 < jpeg.size) {
                if (jpeg[i] != FF) {
                    i++
                    continue
                }
                val marker = jpeg[i + 1].toInt() and 0xFF
                // Sin datos: relleno, inicio de imagen o reinicio
                if (marker == 0xFF || marker == 0xD8 || marker in 0xD0..0xD9) {
                    i += 2
                    continue
                }
                val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
                // SOF0 a SOF15, menos los que no describen la imagen (DHT, JPG, DAC)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (i + 9 >= jpeg.size) return null
                    val height = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                    val width = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                    return if (width > 0 && height > 0) width to height else null
                }
                if (marker == 0xDA || length < 2) return null // empiezan los datos comprimidos
                i += 2 + length
            }
            return null
        }
    }
}
