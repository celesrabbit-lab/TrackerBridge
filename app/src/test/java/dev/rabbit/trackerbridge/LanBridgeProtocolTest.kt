package dev.rabbit.trackerbridge

import dev.rabbit.trackerbridge.LanBridgeProtocol.Slot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los bytes esperados salen de una captura de red real entre la app de PC y un bridge de Quest, y de
 * la prueba de caja negra con la que la app de PC mostro el video.
 */
class LanBridgeProtocolTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val identity = LanBridgeProtocol.Identity(
        sourceId = "4b5157f3-eb0a-4635-8495-466736de4760",
        appVersion = "0.10.0",
        device = "panther",
        manufacturer = "Oculus",
        model = "Quest 3S",
    )

    @Test
    fun cabeceraDeEstadoIgualALaCapturada() {
        // Paquetes de estado reales: slot, secuencia y largo del JSON
        assertArrayEquals(
            hex("50 54 01 02 00 00 21 4f 00 01 00 00 01 b2 00 00"),
            LanBridgeProtocol.header(LanBridgeProtocol.TYPE_STATUS, Slot.FACE, 0x214f, 434, 0),
        )
        assertArrayEquals(
            hex("50 54 01 02 01 00 21 50 00 01 00 00 02 58 00 00"),
            LanBridgeProtocol.header(LanBridgeProtocol.TYPE_STATUS, Slot.LEFT_EYE, 0x2150, 600, 0),
        )
        assertArrayEquals(
            hex("50 54 01 02 02 00 21 51 00 01 00 00 02 59 00 00"),
            LanBridgeProtocol.header(LanBridgeProtocol.TYPE_STATUS, Slot.RIGHT_EYE, 0x2151, 601, 0),
        )
    }

    @Test
    fun cuadroEnteroEnUnPaquete() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val p = LanBridgeProtocol.framePacket(Slot.RIGHT_EYE, 0x1234, 0x00AB, jpeg)!!
        assertArrayEquals(hex("50 54 01 01 02 00 12 34 00 01 00 00 00 08 00 ab"), p.copyOfRange(0, 16))
        assertArrayEquals(jpeg, p.copyOfRange(16, p.size))
        // La secuencia y el numero de cuadro dan la vuelta en 16 bits
        val wrapped = LanBridgeProtocol.framePacket(Slot.FACE, 0x1_0005, 0x2_0007, jpeg)!!
        assertArrayEquals(hex("00 05"), wrapped.copyOfRange(6, 8))
        assertArrayEquals(hex("00 07"), wrapped.copyOfRange(14, 16))
    }

    @Test
    fun cuadroDemasiadoGrande() {
        assertNull(LanBridgeProtocol.framePacket(Slot.FACE, 1, 1, ByteArray(70_000)))
        assertTrue(LanBridgeProtocol.framePacket(Slot.FACE, 1, 1, ByteArray(0xFFFF)) != null)
    }

    @Test
    fun jsonDeEstado() {
        val json = LanBridgeProtocol.statusJson(Slot.LEFT_EYE, listOf(Slot.RIGHT_EYE, Slot.LEFT_EYE), identity)
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        // Los slots conectados salen siempre en el mismo orden: cara, izquierdo, derecho
        assertTrue(json.contains("\"bridge_connected_slots\":[\"left_eye\",\"right_eye\"]"))
        assertTrue(json.contains("\"bridge_slot\":\"left_eye\""))
        assertTrue(json.contains("\"bridge_slot_connected\":true"))
        assertTrue(json.contains("\"bridge_protocol_version\":2"))
        assertTrue(json.contains("\"bridge_source_id\":\"4b5157f3-eb0a-4635-8495-466736de4760\""))
        assertTrue(json.contains("\"min_client_version\":\"7.2.0\""))
        // Se presenta como Tracker Bridge, con su version real
        assertTrue(json.contains("\"bridge_device_label\":\"Tracker Bridge (Quest 3S)\""))
        assertTrue(json.contains("\"bridge_app_version\":\"0.10.0\""))
        assertTrue(json.contains("\"android_app_version\":\"0.10.0\""))

        val face = LanBridgeProtocol.statusJson(Slot.FACE, listOf(Slot.LEFT_EYE), identity)
        assertTrue(face.contains("\"bridge_slot_connected\":false"))
        val none = LanBridgeProtocol.statusJson(Slot.FACE, emptyList(), identity)
        assertTrue(none.contains("\"bridge_connected_slots\":[]"))
    }

    @Test
    fun textosRarosNoRompenElJson() {
        val odd = LanBridgeProtocol.Identity("id", "1.0", "dev\"ice", "back\\slash", "model\nnew")
        val json = LanBridgeProtocol.statusJson(Slot.FACE, emptyList(), odd)
        assertTrue(json.contains("\"bridge_android_device\":\"dev\\\"ice\""))
        assertTrue(json.contains("\"bridge_android_manufacturer\":\"back\\\\slash\""))
        assertTrue(json.contains("\"bridge_android_model\":\"model\\u000anew\""))
        assertFalse(json.contains("\n"))
    }

    @Test
    fun versionNumerica() {
        assertEquals("0.10.0", LanBridgeProtocol.numericVersion("0.10.0-beta.1"))
        assertEquals("0.9.1", LanBridgeProtocol.numericVersion("0.9.1"))
        assertEquals("0", LanBridgeProtocol.numericVersion("beta"))
    }

    @Test
    fun reconoceElAckYElAnuncio() {
        val ack = "PTRACKER_ACK_V1".toByteArray()
        assertTrue(LanBridgeProtocol.isAck(ack + ByteArray(10), ack.size))
        assertFalse(LanBridgeProtocol.isAck("PTRACKER_PROBE_V1".toByteArray(), 17))

        // Comienzo real del anuncio de la app de PC
        val announce = "{\"action\":\"lan_announce\",\"client_version\":\"7.3.10\",\"hostname\":\"DESKTOP-R54PEO0\"}".toByteArray()
        assertTrue(LanBridgeProtocol.isAnnounce(announce, announce.size))
        val spaced = "{ \"action\" : \"lan_announce\" }".toByteArray()
        assertTrue(LanBridgeProtocol.isAnnounce(spaced, spaced.size))
        val other = "{\"action\":\"lan_network_hint\",\"service\":\"other\"}".toByteArray()
        assertFalse(LanBridgeProtocol.isAnnounce(other, other.size))
        assertFalse(LanBridgeProtocol.isAnnounce(ack, ack.size))
    }

    @Test
    fun slotsPorNombre() {
        data class Cam(val name: String, val port: Int)
        val cams = listOf(Cam("openiristracker", 8083), Cam("RightETVR", 8082), Cam("LeftETVR", 8081))
        val slots = LanBridgeProtocol.assignSlots(cams, { it.name }, { it.port })
        assertEquals("LeftETVR", slots[Slot.LEFT_EYE]?.name)
        assertEquals("RightETVR", slots[Slot.RIGHT_EYE]?.name)
        assertEquals("openiristracker", slots[Slot.FACE]?.name)

        // Dos camaras sin lado: la cara es la de puerto mas bajo, la otra no se manda
        val two = LanBridgeProtocol.assignSlots(
            listOf(Cam("webcam", 8085), Cam("boca", 8083)), { it.name }, { it.port },
        )
        assertEquals(mapOf(Slot.FACE to Cam("boca", 8083)), two)

        // En espanol tambien
        val es = LanBridgeProtocol.assignSlots(listOf(Cam("Ojo izq", 8081), Cam("Ojo derecho", 8082)), { it.name }, { it.port })
        assertEquals(setOf(Slot.LEFT_EYE, Slot.RIGHT_EYE), es.keys)
    }
}
