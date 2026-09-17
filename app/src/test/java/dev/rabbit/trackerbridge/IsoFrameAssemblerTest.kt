package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class IsoFrameAssemblerTest {
    private val rnd = Random(4321)

    /** JPEG falso: FF D8, cuerpo sin bytes FF, FF D9. */
    private fun jpeg(size: Int): ByteArray {
        val b = ByteArray(size)
        for (i in b.indices) b[i] = rnd.nextInt(0, 0xFF).toByte()
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
        b[size - 2] = 0xFF.toByte(); b[size - 1] = 0xD9.toByte()
        return b
    }

    /** Paquete isocrono UVC: cabecera de [headerLen] bytes (12 = con PTS y SCR, como las webcams) + datos. */
    private fun packet(data: ByteArray, fid: Int, eof: Boolean, headerLen: Int = 12, error: Boolean = false): ByteArray {
        val p = ByteArray(headerLen + data.size)
        p[0] = headerLen.toByte()
        var bfh = 0x80 or fid
        if (headerLen >= 12) bfh = bfh or 0x0C
        if (eof) bfh = bfh or 0x02
        if (error) bfh = bfh or 0x40
        p[1] = bfh.toByte()
        data.copyInto(p, headerLen)
        return p
    }

    /** Parte un cuadro como una webcam: paquetes de hasta [packetSize] bytes, cada uno con su cabecera. */
    private fun packets(frame: ByteArray, fid: Int, packetSize: Int = 3072, eof: Boolean = true, error: Boolean = false): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off < frame.size) {
            val n = minOf(packetSize - 12, frame.size - off)
            val last = off + n == frame.size
            out += packet(frame.copyOfRange(off, off + n), fid, eof && last, error = error)
            off += n
        }
        return out
    }

    /** Entrega paquetes como el codigo nativo: datos compactados uno tras otro, con su largo y estado. */
    private fun deliver(assembler: IsoFrameAssembler, packets: List<ByteArray>, statuses: List<Int> = List(packets.size) { 0 }) {
        val data = ByteArray(packets.sumOf { it.size })
        var off = 0
        packets.forEachIndexed { i, p ->
            p.copyInto(data, off)
            assembler.onPacket(data, off, p.size, statuses[i])
            off += p.size
        }
    }

    private fun assertFrames(expected: List<ByteArray>, got: List<ByteArray>) {
        assertEquals("cantidad de cuadros", expected.size, got.size)
        expected.zip(got).forEachIndexed { i, (e, g) -> assertTrue("cuadro $i distinto", e.contentEquals(g)) }
    }

    @Test
    fun cuadrosConEofYPaquetesVacios() {
        val frames = List(10) { jpeg(5000 + rnd.nextInt(10000)) }
        val got = mutableListOf<ByteArray>()
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = { got += it })
        frames.forEachIndexed { i, f ->
            // Entre cuadros la camara manda paquetes vacios
            deliver(assembler, List(3) { ByteArray(0) } + packets(f, i % 2) + List(5) { ByteArray(0) })
        }
        assertFrames(frames, got)
    }

    @Test
    fun sinEofElCuadroTerminaCuandoCambiaElFid() {
        val frames = List(6) { jpeg(4000 + rnd.nextInt(8000)) }
        val got = mutableListOf<ByteArray>()
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = { got += it })
        frames.forEachIndexed { i, f -> deliver(assembler, packets(f, i % 2, eof = false)) }
        // El ultimo sigue abierto hasta que llegue el siguiente
        assertFrames(frames.dropLast(1), got)
    }

    @Test
    fun paqueteConErrorDescartaSoloEseCuadro() {
        val bad = jpeg(9000)
        val good = jpeg(9000)
        val got = mutableListOf<ByteArray>()
        var dropped = 0
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = { got += it }, onDropped = { dropped++ })
        val badPackets = packets(bad, 0)
        // Un paquete del medio se perdio en el USB (-EXDEV)
        deliver(assembler, badPackets, List(badPackets.size) { if (it == 1) -18 else 0 })
        deliver(assembler, packets(good, 1))
        assertFrames(listOf(good), got)
        assertEquals(1, dropped)
        assertEquals("USB packet error -18", assembler.lastDropReason)
    }

    @Test
    fun bitDeErrorDeLaCamaraDescartaElCuadro() {
        val bad = jpeg(6000)
        val good = jpeg(6000)
        val got = mutableListOf<ByteArray>()
        var dropped = 0
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = { got += it }, onDropped = { dropped++ })
        deliver(assembler, packets(bad, 0, error = true) + packets(good, 1))
        assertFrames(listOf(good), got)
        assertEquals(1, dropped)
    }

    @Test
    fun cabeceraSolaConEofCierraElCuadro() {
        val frame = jpeg(7000)
        val got = mutableListOf<ByteArray>()
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = { got += it })
        deliver(assembler, packets(frame, 1, eof = false) + packet(ByteArray(0), 1, eof = true))
        assertFrames(listOf(frame), got)
    }

    @Test
    fun rellenoDespuesDelFinDeImagenSeRecorta() {
        val real = jpeg(5000)
        val got = mutableListOf<ByteArray>()
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = { got += it })
        deliver(assembler, packets(real + ByteArray(300), 0))
        assertFrames(listOf(real), got)
    }

    @Test
    fun tiempoDeTransferenciaDesdeElPrimerPaquete() {
        val frame = jpeg(12000)
        val parts = packets(frame, 0)
        assertEquals(4, parts.size)
        val assembler = IsoFrameAssembler(64 * 1024, onFrame = {})
        assembler.beginBatch(1_000_000L)
        deliver(assembler, listOf(ByteArray(0)))
        assembler.beginBatch(2_000_000L)
        deliver(assembler, parts.take(2))
        assembler.beginBatch(5_000_000L)
        deliver(assembler, parts.drop(2))
        assertEquals(1L, assembler.validFrames)
        assertEquals(5_000_000L, assembler.lastFrameNanos)
        assertEquals(3_000_000L, assembler.lastTransferNanos)
    }
}
