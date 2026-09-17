package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {
    private val ms = 1_000_000L

    @Test
    fun intervalosCuadrosAtrasadosYTransferencias() {
        val stats = StreamStats(16_666_667L, "reads")
        var t = 1000 * ms
        repeat(10) {
            stats.onFrame(t)
            t += 16 * ms
        }
        // La camara se salto un cuadro: 32 ms entre dos cuadros
        t += 16 * ms
        stats.onFrame(t)
        stats.onTransfer(5 * ms)
        stats.onTransfer(3 * ms)
        stats.onData(22_528)
        stats.reads = 11
        stats.onDropped("no JPEG end")
        stats.onDropped("bad header")
        stats.onDropped("no JPEG end")

        val s = stats.summarize(1_000 * ms)
        assertTrue(s, s.startsWith("11.0 fps · 2.0 KB · 1.0 reads/frame"))
        assertTrue(s, " · interval p50 16.0 p95 16.0 max 32.0 ms, late 1" in s)
        assertTrue(s, " · transfer p50 3.0 max 5.0 ms (2)" in s)
        assertTrue(s, s.endsWith(" · dropped 3 (no JPEG end: 2, bad header: 1)"))
    }

    @Test
    fun laVentanaSiguienteEmpiezaDeCero() {
        val stats = StreamStats(8_333_333L, "URBs")
        stats.onFrame(100 * ms)
        stats.onFrame(108 * ms)
        stats.packetErrors = 3
        stats.summarize(1_000 * ms)

        // El intervalo con el ultimo cuadro de la ventana anterior sigue contando
        stats.onFrame(117 * ms)
        val s = stats.summarize(1_000 * ms)
        assertTrue(s, s.startsWith("1.0 fps"))
        assertTrue(s, "max 9.0 ms, late 0" in s)
        assertEquals(false, "packet errors" in s)
    }
}
