package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class UvcPayloadParserTest {
    private val rnd = Random(1234)

    /** JPEG falso: FF D8, cuerpo sin bytes FF (como los datos con byte stuffing), FF D9. */
    private fun jpeg(size: Int): ByteArray {
        val b = ByteArray(size)
        for (i in b.indices) b[i] = rnd.nextInt(0, 0xFF).toByte()
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
        b[size - 2] = 0xFF.toByte(); b[size - 1] = 0xD9.toByte()
        return b
    }

    /** Divide un cuadro en payloads UVC de hasta [maxPayload] bytes, como TinyUSB. */
    private fun payloads(frame: ByteArray, fid: Int, maxPayload: Int, headerLen: Int = 2, error: Boolean = false): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off < frame.size) {
            val n = minOf(maxPayload - headerLen, frame.size - off)
            val last = off + n == frame.size
            val p = ByteArray(headerLen + n)
            p[0] = headerLen.toByte()
            var bfh = 0x80 or fid
            if (last) bfh = bfh or 0x02
            if (error) bfh = bfh or 0x40
            if (headerLen >= 6) bfh = bfh or 0x04
            if (headerLen >= 12) bfh = bfh or 0x08
            p[1] = bfh.toByte()
            System.arraycopy(frame, off, p, headerLen, n)
            out += p
            off += n
        }
        return out
    }

    /**
     * Simula lecturas bulk del host: cada transferencia se parte en paquetes de [packetSize];
     * una lectura termina con un paquete corto (o de largo cero si [zlp]) o con el buffer lleno.
     */
    private fun usbReads(transfers: List<ByteArray>, readSize: Int, packetSize: Int = 64, zlp: Boolean = false): List<Pair<ByteArray, Boolean>> {
        val packets = mutableListOf<ByteArray>()
        for (t in transfers) {
            var off = 0
            while (off < t.size) {
                val n = minOf(packetSize, t.size - off)
                packets += t.copyOfRange(off, off + n)
                off += n
            }
            if (zlp && t.size % packetSize == 0) packets += ByteArray(0)
        }
        val reads = mutableListOf<Pair<ByteArray, Boolean>>()
        val cur = ByteArrayOutputStream()
        for (p in packets) {
            cur.write(p)
            if (p.size < packetSize || cur.size() == readSize) {
                if (cur.size() > 0) reads += cur.toByteArray() to (cur.size() < readSize)
                cur.reset()
            }
        }
        if (cur.size() > 0) reads += cur.toByteArray() to false
        return reads
    }

    private fun run(frames: List<ByteArray>, maxPayload: Int, readSize: Int, headerLen: Int = 2, zlp: Boolean = false): List<ByteArray> {
        val transfers = frames.flatMapIndexed { i, f -> payloads(f, i % 2, maxPayload, headerLen) }
        val got = mutableListOf<ByteArray>()
        val parser = UvcPayloadParser(maxPayload, 64 * 1024, onFrame = { got += it })
        for ((data, short) in usbReads(transfers, readSize, zlp = zlp)) parser.feed(data, data.size, short)
        return got
    }

    private fun assertFrames(expected: List<ByteArray>, got: List<ByteArray>) {
        assertEquals("cantidad de cuadros", expected.size, got.size)
        expected.zip(got).forEachIndexed { i, (e, g) -> assertTrue("cuadro $i distinto", e.contentEquals(g)) }
    }

    @Test
    fun cuadrosDeUnPayloadConPaquetesCortos() {
        val frames = List(20) { jpeg(3000 + rnd.nextInt(6000)).let { if ((it.size + 2) % 64 == 0) jpeg(it.size + 1) else it } }
        assertFrames(frames, run(frames, maxPayload = 32768, readSize = 32768))
    }

    @Test
    fun cuadrosDeVariosPayloadsMultiploDe64() {
        val frames = List(10) { jpeg(5000 + rnd.nextInt(3000)) }
        assertFrames(frames, run(frames, maxPayload = 1024, readSize = 1024))
    }

    @Test
    fun cuadrosDeVariosPayloadsNoMultiploDe64() {
        val frames = List(10) { jpeg(5000 + rnd.nextInt(3000)) }
        assertFrames(frames, run(frames, maxPayload = 1000, readSize = 1024))
    }

    @Test
    fun payloadFinalMultiploDe64SinZlpSeJuntaConElSiguiente() {
        // 2 de cabecera + 4094 de datos = 4096: termina en paquete completo y la lectura se junta
        val frames = listOf(jpeg(4094), jpeg(3000), jpeg(4094), jpeg(4094), jpeg(2500))
        assertFrames(frames, run(frames, maxPayload = 32768, readSize = 32768))
    }

    @Test
    fun payloadsDe64BytesConLecturasGrandes() {
        // Como OpenIris en el Quest: dwMaxPayloadTransferSize = 64, cada paquete trae su cabecera.
        // 6200 y 6386 son multiplos de 62: el ultimo payload mide justo 64 y se junta con el siguiente.
        val frames = List(30) { jpeg(3000 + rnd.nextInt(6000)) } + listOf(jpeg(6200), jpeg(4000), jpeg(6386), jpeg(6200))
        assertFrames(frames, run(frames, maxPayload = 64, readSize = 16384))
    }

    @Test
    fun conZlp() {
        val frames = listOf(jpeg(4094), jpeg(3000), jpeg(4094), jpeg(2500))
        assertFrames(frames, run(frames, maxPayload = 32768, readSize = 32768, zlp = true))
    }

    @Test
    fun cabeceraDe12Bytes() {
        val frames = List(10) { jpeg(4000 + rnd.nextInt(4000)) }
        assertFrames(frames, run(frames, maxPayload = 2048, readSize = 2048, headerLen = 12))
    }

    @Test
    fun rellenoDespuesDelFinDeImagenSeRecorta() {
        val real = jpeg(3000)
        val padded = real + ByteArray(37)
        val got = mutableListOf<ByteArray>()
        val parser = UvcPayloadParser(32768, 64 * 1024, onFrame = { got += it })
        for (p in payloads(padded, 0, 32768)) parser.feed(p, p.size, true)
        assertFrames(listOf(real), got)
    }

    @Test
    fun bitDeErrorDescartaElCuadro() {
        val bad = jpeg(3000)
        val good = jpeg(3000)
        val got = mutableListOf<ByteArray>()
        var dropped = 0
        val parser = UvcPayloadParser(32768, 64 * 1024, onFrame = { got += it }, onDropped = { dropped++ })
        for (p in payloads(bad, 0, 32768, error = true) + payloads(good, 1, 32768)) parser.feed(p, p.size, true)
        assertFrames(listOf(good), got)
        assertEquals(1, dropped)
        assertEquals("camera error bit", parser.lastDropReason)
    }

    @Test
    fun basuraAlInicioSeRecupera() {
        val frames = List(5) { jpeg(3000 + it * 7) }
        val got = mutableListOf<ByteArray>()
        val parser = UvcPayloadParser(32768, 64 * 1024, onFrame = { got += it })
        val junk = ByteArray(100) // cabecera invalida (largo 0)
        parser.feed(junk, junk.size, true)
        frames.forEachIndexed { i, f -> for (p in payloads(f, i % 2, 32768)) parser.feed(p, p.size, true) }
        assertFrames(frames, got)
    }
}
