package dev.rabbit.trackerbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcDescriptorsTest {
    private fun hex(s: String): ByteArray =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Webcam HBVCAM GC0308 (puente Sonix 0C45:6366, UVC 1.0, isocrona), reconstruida del texto de
     * USB Device Tree Viewer que mando un tester. Ese texto no muestra dwMaxVideoFrameBufferSize:
     * el valor de cada cuadro es inventado (la app no lo usa).
     */
    private val sonixWebcam = hex(
        """
        12 01 00 02 EF 02 01 40 45 0C 66 63 00 01 02 01 03 01
        09 02 37 02 02 01 00 80 FA
        08 0B 00 02 0E 03 00 05
        09 04 00 00 01 0E 01 00 05
        0D 24 01 00 01 4D 00 C0 E1 E4 00 01 01
        09 24 03 04 01 01 00 03 00
        1A 24 06 03 70 33 F0 28 11 63 2E 4A BA 2C 68 90 EB 33 40 16 08 01 02 01 9F 00
        12 24 02 01 01 02 00 00 00 00 00 00 00 00 03 0E 00 00
        0B 24 05 02 01 00 00 02 7F 17 00
        07 05 83 03 10 00 06
        05 25 03 80 00
        09 04 01 00 00 0E 02 00 00
        0F 24 01 02 5B 01 81 00 04 02 01 01 01 00 00
        0B 24 06 01 04 00 01 00 00 00 00
        1E 24 07 01 00 40 01 F0 00 00 00 CA 08 00 00 CA 08 00 58 02 00 15 16 05 00 01 85 45 01 00
        1E 24 07 02 00 80 02 E0 01 00 00 CA 08 00 00 CA 08 00 60 09 00 15 16 05 00 01 15 16 05 00
        2A 24 07 03 00 A0 00 78 00 00 70 17 00 00 A0 8C 00 00 96 00 00 15 16 05 00 04 15 16 05 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00
        1E 24 07 04 00 40 01 F0 00 00 00 CA 08 00 00 CA 08 00 58 02 00 15 16 05 00 01 85 45 01 00
        12 24 03 00 03 40 01 F0 00 80 02 E0 01 A0 00 78 00 00
        1B 24 04 02 04 59 55 59 32 00 00 10 00 80 00 00 AA 00 38 9B 71 10 01 00 00 00 00
        1E 24 05 01 00 40 01 F0 00 00 80 32 02 00 80 32 02 00 58 02 00 15 16 05 00 01 15 16 05 00
        1E 24 05 02 00 80 02 E0 01 00 00 CA 08 00 00 CA 08 00 60 09 00 15 16 05 00 01 15 16 05 00
        1E 24 05 03 00 A0 00 78 00 00 A0 8C 00 00 A0 8C 00 00 96 00 00 15 16 05 00 01 15 16 05 00
        1E 24 05 04 00 40 01 F0 00 00 80 32 02 00 80 32 02 00 58 02 00 15 16 05 00 01 15 16 05 00
        12 24 03 00 03 80 02 E0 01 40 01 F0 00 A0 00 78 00 00
        06 24 0D 01 01 04
        09 04 01 01 01 0E 02 00 00 07 05 81 05 80 00 01
        09 04 01 02 01 0E 02 00 00 07 05 81 05 00 01 01
        09 04 01 03 01 0E 02 00 00 07 05 81 05 20 03 01
        09 04 01 04 01 0E 02 00 00 07 05 81 05 20 0B 01
        09 04 01 05 01 0E 02 00 00 07 05 81 05 20 13 01
        09 04 01 06 01 0E 02 00 00 07 05 81 05 00 14 01
        """
    )

    /**
     * Placa tipo OpenIris en modo UVC (sintetica): serie CDC primero y video con endpoint bulk de 64 bytes.
     * El endpoint bulk de entrada del CDC no debe confundirse con el de video.
     */
    private val openIrisLike = hex(
        """
        12 01 00 02 EF 02 01 40 3A 30 00 80 00 01 01 02 03 01
        09 02 CB 00 04 01 00 80 32
        08 0B 00 02 02 02 00 00
        09 04 00 00 01 02 02 00 00
        05 24 00 20 01
        05 24 01 00 03
        04 24 02 02
        05 24 06 00 01
        07 05 83 03 08 00 10
        09 04 01 00 02 0A 00 00 00
        07 05 04 02 40 00 00
        07 05 84 02 40 00 00
        08 0B 02 02 0E 03 00 00
        09 04 02 00 00 0E 01 00 00
        0D 24 01 50 01 28 00 00 6C DC 02 01 03
        12 24 02 01 01 02 00 00 00 00 00 00 00 00 03 00 00 00
        09 24 03 02 01 01 00 01 00
        09 04 03 00 01 0E 02 00 00
        0E 24 01 01 37 00 81 00 02 00 00 00 01 00
        0B 24 06 01 01 00 01 00 00 00 00
        1E 24 07 01 00 F0 00 F0 00 00 00 A0 00 00 00 A0 00 00 C2 01 00 0A 8B 02 00 01 0A 8B 02 00
        07 05 81 02 40 00 00
        """
    )

    private fun configLength(raw: ByteArray) = UvcDescriptors.u16(raw, 18 + 2)

    @Test
    fun reconstruccionCompleta() {
        // wTotalLength del descriptor de configuracion = bytes despues del descriptor de dispositivo
        assertEquals(567, configLength(sonixWebcam))
        assertEquals(567, sonixWebcam.size - 18)
        assertEquals(openIrisLike.size - 18, configLength(openIrisLike))
    }

    @Test
    fun webcamSonixIsocrona() {
        val info = assertNotNullInfo(UvcDescriptors.parse(sonixWebcam))
        assertEquals(0x0100, info.bcdUvc)
        assertEquals(26, info.probeLength)
        assertEquals(0, info.controlInterfaceId)
        assertEquals(1, info.streamingInterfaceId)
        assertEquals(1, info.mjpegFormatIndex)

        // Solo los cuadros MJPEG, no los YUY2
        assertEquals(listOf(1, 2, 3, 4), info.mjpegFrames.map { it.index })
        assertEquals(listOf(320 to 240, 640 to 480, 160 to 120, 320 to 240), info.mjpegFrames.map { it.width to it.height })
        assertEquals(listOf(83333), info.mjpegFrames[0].intervals)
        assertEquals(333333, info.mjpegFrames[0].defaultInterval)
        assertEquals(listOf(333333, 666666, 1000000, 2000000), info.mjpegFrames[2].intervals)

        assertNull(info.bulkEndpoint())
        val iso = info.isoEndpoints()
        assertEquals(listOf(1, 2, 3, 4, 5, 6), iso.map { it.altSetting })
        assertEquals(listOf(128, 256, 800, 1600, 2400, 3072), iso.map { it.effectivePacketSize })
        assertTrue(iso.all { it.address == 0x81 && it.interfaceId == 1 })

        // 320x240 a 120 fps: lo mas parecido a 240x240
        val frame = info.frameClosestTo240()
        assertEquals(1, frame.index)
        assertEquals(6, info.isoEndpointFor(3072)?.altSetting)
        assertEquals(4, info.isoEndpointFor(1000)?.altSetting)
        assertEquals(6, info.isoEndpointFor(8000)?.altSetting)
    }

    @Test
    fun placaOpenIrisBulk() {
        val info = assertNotNullInfo(UvcDescriptors.parse(openIrisLike))
        assertEquals(0x0150, info.bcdUvc)
        assertEquals(48, info.probeLength)
        assertEquals(2, info.controlInterfaceId)
        assertEquals(3, info.streamingInterfaceId)
        assertEquals(1, info.mjpegFrames.size)
        assertEquals(listOf(166666), info.mjpegFrames[0].intervals)
        assertEquals(240, info.frameClosestTo240().width)

        val bulk = info.bulkEndpoint()
        assertNotNull(bulk)
        assertEquals(0x81, bulk!!.address)
        assertEquals(0, bulk.altSetting)
        assertEquals(64, bulk.maxPacketSize)
        assertEquals(1, info.endpoints.size)
        assertTrue(info.isoEndpoints().isEmpty())
    }

    private fun assertNotNullInfo(info: UvcInfo?): UvcInfo {
        assertNotNull("no se reconocio la camara", info)
        return info!!
    }
}
