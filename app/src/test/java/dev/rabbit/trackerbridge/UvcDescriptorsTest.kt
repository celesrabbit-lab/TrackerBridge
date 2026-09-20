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

    /**
     * Webcam Sunplus 1BCF:28C4 (UVC 1.0, isocrona, con microfono): descriptores reales, copiados del
     * registro de la app en el Quest. Es la webcam con la que se probo el modo isocrono.
     */
    private val sunplusWebcam = hex(
        """
        12 01 00 02 EF 02 01 40 CF 1B C4 28 19 02 01 02 03 01
        09 02 AB 04 04 01 00 80 FA
        08 0B 00 02 0E 03 00 04
        09 04 00 00 01 0E 01 00 04
        0D 24 01 00 01 6D 00 00 6C DC 02 01 01
        12 24 02 01 01 02 00 00 00 00 00 00 00 00 03 AA 00 02
        0B 24 05 02 01 00 40 02 7F 15 00
        1D 24 06 03 C3 85 B8 0F C2 68 47 45 90 F7 8F 47 57 9D 95 FC 05 01 02 04 1F 00 00 00 00
        1D 24 06 04 82 06 61 63 70 50 AB 49 B8 CC B3 85 5E 8D 22 1D 19 01 03 04 FF FF 77 07 00
        09 24 03 05 01 01 00 04 00
        07 05 87 03 10 00 08
        05 25 03 05 04
        09 04 01 00 00 0E 02 00 00
        0F 24 01 02 C7 01 81 00 05 01 00 00 01 04 00
        0B 24 06 01 07 01 01 00 00 00 00
        1E 24 07 01 01 00 08 00 06 00 00 00 5A 00 00 00 5A 00 00 60 00 15 16 05 00 01 15 16 05 00
        1E 24 07 02 01 80 07 38 04 00 80 53 3B 00 80 53 3B 00 48 3F 00 15 16 05 00 01 15 16 05 00
        1E 24 07 03 01 00 05 C0 03 00 00 28 23 00 00 28 23 00 80 25 00 15 16 05 00 01 15 16 05 00
        1E 24 07 04 01 00 05 D0 02 00 00 5E 1A 00 00 5E 1A 00 20 1C 00 15 16 05 00 01 15 16 05 00
        1E 24 07 05 01 80 02 E0 01 00 00 CA 08 00 00 CA 08 00 60 09 00 15 16 05 00 01 15 16 05 00
        1E 24 07 06 01 80 02 68 01 00 80 97 06 00 80 97 06 00 08 07 00 15 16 05 00 01 15 16 05 00
        1E 24 07 07 01 40 01 F0 00 00 80 32 02 00 80 32 02 00 58 02 00 15 16 05 00 01 15 16 05 00
        06 24 0D 01 01 04
        1B 24 04 02 06 59 55 59 32 00 00 10 00 80 00 00 AA 00 38 9B 71 10 01 00 00 00 00
        1E 24 05 02 01 80 07 38 04 00 40 E3 09 00 40 E3 09 00 48 3F 00 80 84 1E 00 01 80 84 1E 00
        1E 24 05 03 01 00 05 C0 03 00 00 DC 05 00 00 DC 05 00 80 25 00 80 84 1E 00 01 80 84 1E 00
        1E 24 05 04 01 00 05 D0 02 00 00 CA 08 00 00 CA 08 00 20 1C 00 40 42 0F 00 01 40 42 0F 00
        1E 24 05 05 01 80 02 E0 01 00 00 CA 08 00 00 CA 08 00 60 09 00 15 16 05 00 01 15 16 05 00
        1E 24 05 06 01 80 02 68 01 00 80 97 06 00 80 97 06 00 08 07 00 15 16 05 00 01 15 16 05 00
        1E 24 05 07 01 40 01 F0 00 00 80 32 02 00 80 32 02 00 58 02 00 15 16 05 00 01 15 16 05 00
        06 24 0D 01 01 04
        09 04 01 01 01 0E 02 00 00
        07 05 81 05 C0 00 01
        09 04 01 02 01 0E 02 00 00
        07 05 81 05 80 01 01
        09 04 01 03 01 0E 02 00 00
        07 05 81 05 00 02 01
        09 04 01 04 01 0E 02 00 00
        07 05 81 05 80 02 01
        09 04 01 05 01 0E 02 00 00
        07 05 81 05 20 03 01
        09 04 01 06 01 0E 02 00 00
        07 05 81 05 B0 03 01
        09 04 01 07 01 0E 02 00 00
        07 05 81 05 80 0A 01
        09 04 01 08 01 0E 02 00 00
        07 05 81 05 20 0B 01
        09 04 01 09 01 0E 02 00 00
        07 05 81 05 E0 0B 01
        09 04 01 0A 01 0E 02 00 00
        07 05 81 05 C0 13 01
        09 04 01 0B 01 0E 02 00 00
        07 05 81 05 FC 13 01
        08 0B 02 02 01 02 00 00
        09 04 02 00 00 01 01 00 00
        09 24 01 00 01 26 00 01 03
        0C 24 02 06 01 02 00 02 03 00 00 00
        08 24 06 07 06 01 03 00
        09 24 03 08 01 01 06 07 00
        09 04 03 00 00 01 02 00 00
        09 04 03 01 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 40 1F 00
        09 05 86 05 24 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 02 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 11 2B 00
        09 05 86 05 34 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 03 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 80 3E 00
        09 05 86 05 44 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 04 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 22 56 00
        09 05 86 05 60 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 05 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 C0 5D 00
        09 05 86 05 64 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 06 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 00 7D 00
        09 05 86 05 84 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 07 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 44 AC 00
        09 05 86 05 B8 00 04 00 00
        07 25 01 01 00 00 00
        09 04 03 08 01 01 02 00 00
        07 24 01 08 01 01 00
        0B 24 02 01 02 02 10 01 80 BB 00
        09 05 86 05 C4 00 04 00 00
        07 25 01 01 00 00 00
        """
    )

    @Test
    fun webcamSunplusReal() {
        val info = assertNotNullInfo(UvcDescriptors.parse(sunplusWebcam))
        assertEquals(0x0100, info.bcdUvc)
        assertEquals(26, info.probeLength)
        assertEquals(0, info.controlInterfaceId)
        assertEquals(1, info.streamingInterfaceId)
        assertEquals(1, info.mjpegFormatIndex)
        assertEquals(1213, sunplusWebcam.size)
        assertEquals(sunplusWebcam.size - 18, configLength(sunplusWebcam))

        assertEquals(7, info.mjpegFrames.size)
        assertEquals(2048 to 1536, info.mjpegFrames.first().let { it.width to it.height })
        assertEquals(320 to 240, info.mjpegFrames.last().let { it.width to it.height })
        assertTrue(info.mjpegFrames.all { it.intervals == listOf(333333) })

        // El microfono tambien manda por endpoints isocronos: no deben contarse como video
        assertTrue(info.endpoints.none { it.address == 0x86 })
        assertNull(info.bulkEndpoint())
        val iso = info.isoEndpoints()
        assertEquals((1..11).toList(), iso.map { it.altSetting })
        assertEquals(
            listOf(192, 384, 512, 640, 800, 944, 1280, 1600, 1984, 2880, 3060),
            iso.map { it.effectivePacketSize },
        )
        // Los tres ultimos usan varias transacciones por microtrama (bits 11-12 de wMaxPacketSize)
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 3, 3), iso.map { it.transactions })
        assertTrue(iso.all { it.address == 0x81 && it.interfaceId == 1 })

        // Lo que eligio la app en el Quest: 320x240 y el alt setting que pidio la camara (3060 B)
        val frame = info.frameClosestTo240()
        assertEquals(7, frame.index)
        assertEquals(320 to 240, frame.width to frame.height)
        assertEquals(11, info.isoEndpointFor(3060)?.altSetting)
        assertEquals(5, info.isoEndpointFor(700)?.altSetting)
        assertEquals(11, info.isoEndpointFor(99999)?.altSetting)
    }

    @Test
    fun modosDeVideoDeLaWebcam() {
        val modes = assertNotNullInfo(UvcDescriptors.parse(sunplusWebcam)).videoModes()
        assertEquals(7, modes.size)
        // De menor a mayor: la lista de la pantalla empieza por el modo mas liviano
        assertEquals("320x240@30", modes.first().key)
        assertEquals("2048x1536@30", modes.last().key)
        assertTrue(modes.all { it.fps == 30 })
        // Los modos sin comprimir (YUY2, hasta 10 fps) no se ofrecen: la app solo lee MJPEG
        assertTrue(modes.none { it.fps != 30 })
        assertEquals(7, modes.first().frameIndex)
        assertEquals(333333, modes.first().interval)
    }

    @Test
    fun modosDeVideoDeLasOtrasCamaras() {
        // Placa OpenIris: un solo modo, asi que la pantalla ni muestra el boton
        val openIris = assertNotNullInfo(UvcDescriptors.parse(openIrisLike)).videoModes()
        assertEquals(listOf("240x240@60"), openIris.map { it.key })

        // Sonix: varias velocidades en la misma resolucion, sin repetidos
        val sonix = assertNotNullInfo(UvcDescriptors.parse(sonixWebcam)).videoModes()
        assertEquals(sonix.map { it.key }.distinct(), sonix.map { it.key })
        assertTrue("320x240@120" in sonix.map { it.key })
        assertTrue("160x120@30" in sonix.map { it.key })
        assertTrue("160x120@5" in sonix.map { it.key })
        // Ordenados por tamano y despues por velocidad
        assertEquals(sonix.map { it.width * it.height }.sorted(), sonix.map { it.width * it.height })
    }

    @Test
    fun elegirResolucionYFpsPorSeparado() {
        val info = assertNotNullInfo(UvcDescriptors.parse(sonixWebcam))

        // Todo automatico: 320x240 (lo mas parecido a 240x240) a la velocidad mas alta que ofrece
        val auto = info.resolve(VideoChoice())
        assertEquals(320 to 240, auto.first.width to auto.first.height)
        assertEquals(83333, auto.second)

        // Solo la resolucion: la velocidad sigue siendo la mas alta de esa resolucion
        val chica = info.resolve(VideoChoice(resolution = "160x120"))
        assertEquals(160 to 120, chica.first.width to chica.first.height)
        assertEquals(333333, chica.second)

        // Las dos cosas a mano
        assertEquals(1000000, info.resolve(VideoChoice("160x120", "10")).second)
        assertEquals(2000000, info.resolve(VideoChoice("160x120", "5")).second)

        // Una velocidad que la camara no tiene: se queda con la mas parecida (12 esta mas cerca de 10)
        assertEquals(1000000, info.resolve(VideoChoice("160x120", "12")).second)

        // Solo los fps, con la resolucion automatica: 320x240 solo ofrece 120, y se queda con esa
        assertEquals(83333, info.resolve(VideoChoice(fps = "30")).second)

        // Una resolucion que no existe vuelve a la automatica
        val inventada = info.resolve(VideoChoice(resolution = "999x999"))
        assertEquals(320 to 240, inventada.first.width to inventada.first.height)
    }

    @Test
    fun opcionesQueMuestraLaPantalla() {
        val slot = CameraSlot("sn:test", "camara", 8081)
        slot.modes = assertNotNullInfo(UvcDescriptors.parse(sonixWebcam)).videoModes()
        assertEquals(listOf("160x120", "320x240", "640x480"), slot.resolutionOptions().map { it.resolutionKey })
        assertEquals(listOf(120, 30, 15, 10, 5), slot.fpsOptions())

        // Con una resolucion elegida, los fps son los de esa resolucion
        slot.wantedResolution = "160x120"
        assertEquals(listOf(30, 15, 10, 5), slot.fpsOptions())
        // 320x240 ofrece una sola velocidad: la pantalla esconde el boton de fps
        slot.wantedResolution = "320x240"
        assertEquals(listOf(120), slot.fpsOptions())

        // La webcam de verdad: siete resoluciones y una sola velocidad
        val webcam = CameraSlot("sn:webcam", "webcam", 8084)
        webcam.modes = assertNotNullInfo(UvcDescriptors.parse(sunplusWebcam)).videoModes()
        assertEquals(7, webcam.resolutionOptions().size)
        assertEquals(listOf(30), webcam.fpsOptions())
    }

    private fun assertNotNullInfo(info: UvcInfo?): UvcInfo {
        assertNotNull("no se reconocio la camara", info)
        return info!!
    }
}
