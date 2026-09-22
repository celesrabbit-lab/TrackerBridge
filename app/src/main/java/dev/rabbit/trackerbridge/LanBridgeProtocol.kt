package dev.rabbit.trackerbridge

/**
 * Formato de red de la salida experimental para otras apps de PC: las que no leen una URL MJPEG y en
 * cambio esperan que un "bridge" les mande las camaras por UDP en la red local.
 *
 * - El bridge manda PTRACKER_PROBE_V1 al puerto 45454 de la PC y la PC responde PTRACKER_ACK_V1.
 * - Una vez por segundo, un paquete de estado por slot: cabecera + JSON.
 * - Cada cuadro JPEG va entero en un solo datagrama: cabecera + JPEG.
 *
 * Cabecera, 16 bytes, numeros big-endian:
 *   0-1 "PT" · 2 version (1) · 3 tipo (1 cuadro, 2 estado) · 4 slot · 5 cero · 6-7 secuencia ·
 *   8-9 total de trozos (1) · 10-11 indice (0) · 12-13 largo del contenido · 14-15 numero de cuadro
 */
object LanBridgeProtocol {
    const val PORT = 45454
    const val HEADER_SIZE = 16
    const val TYPE_FRAME = 1
    const val TYPE_STATUS = 2

    /** El largo va en 16 bits: los cuadros mas grandes no entran (una camara de tracking manda 2 a 10 KB). */
    const val MAX_FRAME = 0xFFFF

    /** Version minima de la app de PC con la que se probo el formato. */
    const val MIN_CLIENT_VERSION = "7.2.0"

    val PROBE: ByteArray = "PTRACKER_PROBE_V1".toByteArray(Charsets.US_ASCII)
    val ACK: ByteArray = "PTRACKER_ACK_V1".toByteArray(Charsets.US_ASCII)

    enum class Slot(val id: Int, val key: String) {
        FACE(0, "face"), LEFT_EYE(1, "left_eye"), RIGHT_EYE(2, "right_eye")
    }

    fun header(type: Int, slot: Slot, seq: Int, length: Int, frameId: Int): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        h[0] = 'P'.code.toByte()
        h[1] = 'T'.code.toByte()
        h[2] = 1
        h[3] = type.toByte()
        h[4] = slot.id.toByte()
        putU16(h, 6, seq)
        putU16(h, 8, 1)
        putU16(h, 10, 0)
        putU16(h, 12, length)
        putU16(h, 14, frameId)
        return h
    }

    /** Un cuadro entero en un solo paquete, o null si es demasiado grande para el formato. */
    fun framePacket(slot: Slot, seq: Int, frameId: Int, jpeg: ByteArray): ByteArray? {
        if (jpeg.size > MAX_FRAME) return null
        return header(TYPE_FRAME, slot, seq, jpeg.size, frameId) + jpeg
    }

    fun statusPacket(slot: Slot, seq: Int, json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return header(TYPE_STATUS, slot, seq, body.size, 0) + body
    }

    /** Quien es el bridge y que camaras tiene. Se identifica como Tracker Bridge, con su version real. */
    class Identity(
        val sourceId: String,
        val appVersion: String,
        val device: String,
        val manufacturer: String,
        val model: String,
    )

    fun statusJson(slot: Slot, connected: Collection<Slot>, id: Identity): String {
        val slots = Slot.values().filter { it in connected }.joinToString(",") { quote(it.key) }
        return "{" +
            "\"bridge_connected_slots\":[$slots]," +
            "\"bridge_protocol_version\":2," +
            "\"bridge_source_id\":${quote(id.sourceId)}," +
            "\"bridge_slot_connected\":${slot in connected}," +
            "\"bridge_android_device\":${quote(id.device)}," +
            "\"bridge_slot\":${quote(slot.key)}," +
            "\"min_client_version\":${quote(MIN_CLIENT_VERSION)}," +
            "\"android_app_version\":${quote(id.appVersion)}," +
            "\"bridge_android_manufacturer\":${quote(id.manufacturer)}," +
            "\"bridge_device_label\":${quote("Tracker Bridge (${id.model})")}," +
            "\"bridge_app_version\":${quote(id.appVersion)}," +
            "\"bridge_android_model\":${quote(id.model)}," +
            "\"bridge_control_capabilities\":[]" +
            "}"
    }

    /** Solo la parte numerica de la version ("0.10.0-beta.1" -> "0.10.0"), que es lo que compara la PC. */
    fun numericVersion(versionName: String): String =
        Regex("""^\d+(\.\d+)*""").find(versionName)?.value ?: "0"

    fun isAck(data: ByteArray, length: Int): Boolean =
        length == ACK.size && (0 until length).all { data[it] == ACK[it] }

    /** El anuncio que la PC manda por broadcast: {"action":"lan_announce", ...}. */
    fun isAnnounce(data: ByteArray, length: Int): Boolean {
        if (length < 20 || data[0] != '{'.code.toByte()) return false
        val text = String(data, 0, minOf(length, 256), Charsets.UTF_8)
        return ANNOUNCE.containsMatchIn(text)
    }

    /**
     * A que slot va cada camara, por nombre, igual que los puertos: "Left" al ojo izquierdo, "Right"
     * al derecho, y la primera de las demas (por puerto) a la cara. Las que sobran no se mandan.
     */
    fun <T> assignSlots(cameras: List<T>, name: (T) -> String, port: (T) -> Int): Map<Slot, T> {
        val result = LinkedHashMap<Slot, T>()
        for (cam in cameras.sortedBy(port)) {
            val lower = name(cam).lowercase()
            val slot = when {
                "left" in lower || "izq" in lower -> Slot.LEFT_EYE
                "right" in lower || "derech" in lower -> Slot.RIGHT_EYE
                else -> Slot.FACE
            }
            if (slot !in result) result[slot] = cam
        }
        return result
    }

    private val ANNOUNCE = Regex(""""action"\s*:\s*"lan_announce"""")

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
