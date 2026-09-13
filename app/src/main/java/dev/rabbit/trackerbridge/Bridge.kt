package dev.rabbit.trackerbridge

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Una camara conocida. Su puerto HTTP sigue abierto aunque la camara se desconecte. */
class CameraSlot(val key: String, @Volatile var name: String, val port: Int) {
    val frames = FrameBuffer()
    val server = MjpegServer(port, frames)

    @Volatile var camera: UvcCamera? = null
        private set
    @Volatile var deviceName: String? = null
        private set
    @Volatile var serverError: String? = null

    fun attach(usbDeviceName: String, cam: UvcCamera) {
        deviceName = usbDeviceName
        camera = cam
    }

    fun detach() {
        camera?.stop()
        camera = null
        deviceName = null
    }
}

/** Permiso de Horizon OS que UsbUserPermissionManager revisa antes de dar acceso a camaras USB. */
const val USB_CAMERA_PERMISSION = "horizonos.permission.USB_CAMERA"

fun UsbDevice.isVideoDevice(): Boolean =
    (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

/** Estado compartido entre el servicio y la pantalla. */
object Bridge {
    @Volatile var serviceRunning = false
    @Volatile var message: String? = null

    /** Dispositivo con aviso de permiso USB abierto (lo pide la pantalla, lo resuelve el servicio). */
    @Volatile var pendingPermission: String? = null
    @Volatile var permissionRequestedAt = 0L
    /** Dispositivos rechazados: no se vuelven a pedir solos para no entrar en bucle. */
    val deniedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val slots = LinkedHashMap<String, CameraSlot>()

    fun snapshot(): List<CameraSlot> = synchronized(this) { slots.values.toList() }

    fun slotForDevice(usbDeviceName: String): CameraSlot? =
        synchronized(this) { slots.values.firstOrNull { it.deviceName == usbDeviceName } }

    /**
     * Busca o crea el lugar de una camara. El puerto se recuerda por camara, asi la direccion
     * en ETVR/Babble no cambia entre sesiones.
     */
    fun slotFor(context: Context, baseKey: String, name: String): CameraSlot = synchronized(this) {
        var key = baseKey
        var n = 2
        // Dos camaras con la misma identidad (firmware sin numero de serie): separarlas
        while (slots[key]?.deviceName != null) key = "$baseKey#${n++}"
        slots[key]?.let {
            it.name = name
            return it
        }
        val slot = CameraSlot(key, name, assignPort(context, key, name))
        slots[key] = slot
        slot
    }

    fun clear() = synchronized(this) {
        slots.values.forEach {
            it.detach()
            it.server.stop()
        }
        slots.clear()
    }

    private fun assignPort(context: Context, key: String, name: String): Int {
        val prefs = context.getSharedPreferences("ports", Context.MODE_PRIVATE)
        val saved = prefs.getInt(key, 0)
        if (saved > 0) return saved

        val used = prefs.all.values.filterIsInstance<Int>().toSet()
        val lower = name.lowercase()
        var port = when {
            "left" in lower || "izq" in lower -> 8081
            "right" in lower || "derech" in lower -> 8082
            else -> 8083
        }
        while (port in used) port++
        prefs.edit().putInt(key, port).apply()
        return port
    }
}

object NetUtils {
    /** IPv4 del Quest en la red WiFi, o null si no hay. */
    fun localIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val candidates = interfaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
        val chosen = candidates.firstOrNull { it.first.startsWith("wlan") }
            ?: candidates.firstOrNull { it.second.isSiteLocalAddress }
        return chosen?.second?.hostAddress
    }
}
