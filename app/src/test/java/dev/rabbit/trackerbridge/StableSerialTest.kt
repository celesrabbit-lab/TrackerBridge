package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class StableSerialTest {
    @Test
    fun elArranqueDelEsp32UsaLaMismaClaveQueLaCamara() {
        // USB JTAG/serial debug unit (ROM del ESP32-S3) y OpenIris en modo UVC, misma placa
        assertEquals("A4CB8FB88728", stableSerial("A4:CB:8F:B8:87:28"))
        assertEquals("A4CB8FB88728", stableSerial("a4:cb:8f:b8:87:28"))
        assertEquals(stableSerial("A4CB8FB88728"), stableSerial("A4:CB:8F:B8:87:28"))
    }

    @Test
    fun losDemasNumerosDeSerieNoCambian() {
        // Cambiarlos moveria de puerto a camaras que ya estan configuradas en ETVR/Babble
        assertEquals("A4CB8FB88728", stableSerial("A4CB8FB88728"))
        assertEquals("12345678", stableSerial("12345678"))
        assertEquals("SN0001-ab", stableSerial("SN0001-ab"))
        assertEquals("A4:CB:8F", stableSerial("A4:CB:8F"))
    }
}
