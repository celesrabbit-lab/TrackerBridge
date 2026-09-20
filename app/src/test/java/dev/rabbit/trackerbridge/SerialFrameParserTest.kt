package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SerialFrameParserTest {
    private val rnd = Random(7)

    /** JPEG falso: FF D8 FF, cuerpo sin ningun FF (como los datos con byte stuffing) y FF D9. */
    private fun jpeg(size: Int): ByteArray {
        val b = ByteArray(size)
        for (i in b.indices) b[i] = rnd.nextInt(0, 0xFF).toByte() // 0..254: nunca FF
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte()
        b[size - 2] = 0xFF.toByte(); b[size - 1] = 0xD9.toByte()
        return b
    }

    /** Cabecera que OpenIris manda antes de cada cuadro: FF A0 FF A1 y el tamano. */
    private fun withHeader(frame: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xA0.toByte(), 0xFF.toByte(), 0xA1.toByte(),
            frame.size.toByte(), (frame.size shr 8).toByte()) + frame

    private fun collect(chunks: List<ByteArray>, readSize: Int = 4096): Pair<List<ByteArray>, SerialFrameParser> {
        val got = mutableListOf<ByteArray>()
        val parser = SerialFrameParser(onFrame = { got += it })
        val stream = chunks.reduce { a, b -> a + b }
        var off = 0
        while (off < stream.size) {
            val n = minOf(readSize, stream.size - off)
            parser.feed(stream.copyOfRange(off, off + n), n)
            off += n
        }
        return got to parser
    }

    private fun assertFrames(expected: List<ByteArray>, got: List<ByteArray>) {
        assertEquals("cantidad de cuadros", expected.size, got.size)
        expected.zip(got).forEachIndexed { i, (e, g) -> assertTrue("cuadro $i distinto", e.contentEquals(g)) }
    }

    @Test
    fun variosCuadrosSeguidos() {
        val frames = List(10) { jpeg(3000 + rnd.nextInt(5000)) }
        val (got, parser) = collect(frames)
        assertFrames(frames, got)
        assertEquals(10, parser.validFrames)
        assertEquals(0, parser.skippedBytes)
        assertFalse(parser.openIrisHeader)
    }

    @Test
    fun conLaCabeceraDeOpenIris() {
        val frames = List(5) { jpeg(4000) }
        val (got, parser) = collect(frames.map { withHeader(it) })
        assertFrames(frames, got)
        assertTrue(parser.openIrisHeader)
        // Las cabeceras no van dentro del JPEG: se descartan
        assertEquals(5 * 6, parser.skippedBytes)
    }

    @Test
    fun lecturasChicasQuePartenLasMarcas() {
        val frames = List(4) { jpeg(2000) }
        // De a 3 bytes: las marcas FF D8 FF y FF D9 caen partidas entre lecturas
        val (got, parser) = collect(frames.map { withHeader(it) }, readSize = 3)
        assertFrames(frames, got)
        assertEquals(4, parser.validFrames)
    }

    @Test
    fun lecturasDeAUnByte() {
        val frame = jpeg(600)
        val (got, _) = collect(listOf(frame), readSize = 1)
        assertFrames(listOf(frame), got)
    }

    @Test
    fun basuraAlArrancarYEntreCuadros() {
        // La placa escupe texto al encender, y a veces queda ruido entre cuadros
        val junk = "OpenIris booting...\r\n".toByteArray(Charsets.US_ASCII)
        val frames = List(3) { jpeg(2500) }
        val stream = listOf(junk) + frames.flatMap { listOf(it, junk) }
        val (got, parser) = collect(stream)
        assertFrames(frames, got)
        // Todo el ruido menos los 2 ultimos bytes, que quedan guardados por si la marca de inicio
        // del cuadro siguiente viene partida entre dos lecturas
        assertEquals(junk.size * 4L - 2, parser.skippedBytes)
    }

    @Test
    fun seEnganchaAMitadDeUnCuadro() {
        val frames = List(3) { jpeg(3000) }
        // El primer cuadro llega cortado (la app se conecto tarde): se tira y se sigue con los otros
        val cortado = frames[0].copyOfRange(1500, frames[0].size)
        val (got, _) = collect(listOf(cortado) + frames.drop(1))
        assertFrames(frames.drop(1), got)
    }

    @Test
    fun unCuadroSinFinNoCreceParaSiempre() {
        val got = mutableListOf<ByteArray>()
        var dropped = 0
        val parser = SerialFrameParser(maxFrame = 64 * 1024, onFrame = { got += it }, onDropped = { dropped++ })
        // Empieza un cuadro que nunca termina (la placa se colgo a mitad)
        val roto = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(200 * 1024)
        parser.feed(roto, roto.size)
        assertEquals(1, dropped)
        assertEquals("too big", parser.lastDropReason)

        // Y despues se recupera con el cuadro siguiente
        val bueno = jpeg(4000)
        parser.feed(bueno, bueno.size)
        assertFrames(listOf(bueno), got)
    }

    @Test
    fun tamanoDelJpeg() {
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            // APP0 (JFIF), 16 bytes con el largo incluido
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            // Tabla de cuantizacion
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x05, 0x00, 0x01, 0x02,
            // SOF0: 8 bits, alto 0x00F0 = 240, ancho 0x0140 = 320, 3 componentes
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x00, 0xF0.toByte(), 0x01, 0x40, 0x03,
            0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x0C,
        )
        assertEquals(320 to 240, SerialFrameParser.jpegSize(header))
        // Sin SOF no se puede saber
        assertNull(SerialFrameParser.jpegSize(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }
}
