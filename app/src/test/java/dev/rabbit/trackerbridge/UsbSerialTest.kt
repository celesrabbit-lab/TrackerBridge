package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las cuentas de cada chip USB-serie. No hace falta el aparato: los valores estan comparados con los
 * de los controladores de Linux y de libftdi, que son los que usan ETVR y Babble en la PC.
 */
class UsbSerialTest {
    private fun hex(pair: Pair<Int, Int>) = "%04X/%02X".format(pair.first, pair.second)

    @Test
    fun velocidadesDelCh34x() {
        // 3 Mbaudios: la que usa OpenIris en modo cableado
        assertEquals("FE83/02", hex(UsbSerial.Ch34x.baudRegisters(3_000_000)!!))
        // 921600 tiene valores propios en el controlador
        assertEquals("F387/00", hex(UsbSerial.Ch34x.baudRegisters(921_600)!!))
        assertEquals("CC83/08", hex(UsbSerial.Ch34x.baudRegisters(115_200)!!))
        assertEquals("E683/04", hex(UsbSerial.Ch34x.baudRegisters(230_400)!!))

        // El bit 7 del divisor tiene que estar siempre: si no, el chip espera a llenar su buffer
        assertTrue(UsbSerial.Ch34x.baudRegisters(3_000_000)!!.first and 0x80 != 0)

        // Demasiado lenta para el divisor del chip
        assertNull(UsbSerial.Ch34x.baudRegisters(20))
        assertNull(UsbSerial.Ch34x.baudRegisters(0))
    }

    @Test
    fun velocidadesDelFtdi() {
        // Codigos especiales del reloj de 3 MHz
        assertEquals(0, UsbSerial.Ftdi.encodeBaud(3_000_000))
        assertEquals(1, UsbSerial.Ftdi.encodeBaud(2_000_000))
        assertEquals(2, UsbSerial.Ftdi.encodeBaud(1_500_000))
        // Con parte fraccionaria, como en libftdi
        assertEquals(0x8003, UsbSerial.Ftdi.encodeBaud(921_600))
        assertEquals(0x001A, UsbSerial.Ftdi.encodeBaud(115_200))
        assertEquals(0x4138, UsbSerial.Ftdi.encodeBaud(9_600))
        // Fuera del rango del chip
        assertNull(UsbSerial.Ftdi.encodeBaud(4_000_000))
        assertNull(UsbSerial.Ftdi.encodeBaud(100))
    }

    @Test
    fun elFtdiMandaDosBytesDeEstadoPorPaquete() {
        val packet = 64
        val data = ByteArray(3 * packet)
        for (i in data.indices) data[i] = (i % 251).toByte()
        val n = UsbSerial.Ftdi.strip(data, data.size, packet)
        assertEquals(3 * (packet - 2), n)
        // El primer byte util de cada paquete es el tercero del original
        assertEquals((2 % 251).toByte(), data[0])
        assertEquals(((packet + 2) % 251).toByte(), data[packet - 2])
        assertEquals(((2 * packet + 2) % 251).toByte(), data[2 * (packet - 2)])
    }

    @Test
    fun elUltimoPaqueteDelFtdiPuedeVenirCorto() {
        val packet = 64
        val data = ByteArray(packet + 10)
        for (i in data.indices) data[i] = i.toByte()
        assertEquals(packet - 2 + 8, UsbSerial.Ftdi.strip(data, data.size, packet))
        // Un paquete de solo estado no deja nada
        assertEquals(0, UsbSerial.Ftdi.strip(ByteArray(2), 2, packet))
        assertEquals(0, UsbSerial.Ftdi.strip(ByteArray(64), 0, packet))
    }

    @Test
    fun velocidadDelCp210xYDelCdc() {
        // El CP210x lleva la velocidad tal cual, en 4 bytes
        assertEquals(
            listOf(0xC0, 0xC6, 0x2D, 0x00),
            UsbSerial.Cp210x.baudPayload(3_000_000).map { it.toInt() and 0xFF },
        )
        // El CDC lleva ademas 1 bit de stop, sin paridad y 8 bits de datos
        assertEquals(
            listOf(0x00, 0xC2, 0x01, 0x00, 0, 0, 8),
            UsbSerial.Cdc.lineCoding(115_200).map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0xC0, 0xC6, 0x2D, 0x00, 0, 0, 8),
            UsbSerial.Cdc.lineCoding(3_000_000).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun lasVelocidadesQueSePruebanEmpiezanPorLaDeOpenIris() {
        assertEquals(3_000_000, UsbSerial.BAUD_RATES.first())
        assertEquals(115_200, UsbSerial.BAUD_RATES.last())
        assertEquals(UsbSerial.BAUD_RATES.sortedDescending(), UsbSerial.BAUD_RATES)
        // Todas tienen que poder configurarse en los tres chips con cuentas propias
        for (baud in UsbSerial.BAUD_RATES) {
            assertTrue("CH34x $baud", UsbSerial.Ch34x.baudRegisters(baud) != null)
            assertTrue("FTDI $baud", UsbSerial.Ftdi.encodeBaud(baud) != null)
        }
    }
}
