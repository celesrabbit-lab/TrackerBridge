package dev.rabbit.trackerbridge

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun UsbDevice.isVideoDevice(): Boolean =
    (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

/** Placas sin USB nativo (ESP32-CAM y demas): el video llega por un chip USB-serie. */
fun UsbDevice.isSerialDevice(): Boolean = UsbSerial.find(this) != null

/** Todo lo que la app puede intentar leer como camara. */
fun UsbDevice.isTrackerCandidate(): Boolean = isVideoDevice() || isSerialDevice()

/** Las interfaces que muestra Android, para el registro: "0/0 c2.2, 1/0 c10.0, 2/0 c14.1, 3/0 c14.2". */
fun UsbDevice.interfaceSummary(): String =
    (0 until interfaceCount).joinToString(", ") {
        val i = getInterface(it)
        "${i.id}/${i.alternateSetting} c${i.interfaceClass}.${i.interfaceSubclass}"
    }.ifEmpty { "none" }

/** Mensaje para la pantalla: guarda el texto como recurso y se traduce al mostrarse. */
class UiMessage(val res: Int, vararg val args: Any)

/** Una camara conocida. Su puerto HTTP sigue abierto aunque la camara se desconecte. */
class CameraSlot(val key: String, @Volatile var name: String, val port: Int) {
    val frames = FrameBuffer()
    val server = MjpegServer(port, frames)

    @Volatile var camera: TrackerCamera? = null
        private set
    @Volatile var deviceName: String? = null
        private set
    @Volatile var serverFailed = false

    /** Webcam o modulo DIY en espera de que el usuario acepte el aviso de seguridad. */
    @Volatile var blockedByRisk = false

    /** Modos de video que ofrece la camara, para elegirlos en la pantalla. */
    @Volatile var modes: List<VideoMode> = emptyList()

    /** Resolucion elegida a mano ("640x480"), o vacia para la automatica. */
    @Volatile var wantedResolution: String = ""

    /** Velocidad elegida a mano ("30"), o vacia para la automatica. */
    @Volatile var wantedFps: String = ""

    fun choice() = VideoChoice(wantedResolution, wantedFps)

    /** Las velocidades que ofrece la camara con la resolucion elegida, de mayor a menor. */
    fun fpsOptions(): List<Int> = modes
        .filter { wantedResolution.isEmpty() || it.resolutionKey == wantedResolution }
        .map { it.fps }.distinct().sortedDescending()

    /** Las resoluciones que ofrece la camara, de la mas chica a la mas grande. */
    fun resolutionOptions(): List<VideoMode> = modes.distinctBy { it.resolutionKey }

    fun attach(usbDeviceName: String, cam: TrackerCamera) {
        deviceName = usbDeviceName
        camera = cam
    }

    fun detach() {
        camera?.stop()
        camera = null
        deviceName = null
    }
}

/** Estado compartido entre el servicio y la pantalla. */
object Bridge {
    @Volatile var serviceRunning = false
    @Volatile var message: UiMessage? = null

    /** Dispositivo con aviso de permiso USB abierto (lo pide la pantalla, lo resuelve el servicio). */
    @Volatile var pendingPermission: String? = null
    @Volatile var permissionRequestedAt = 0L
    /** Dispositivos rechazados: no se vuelven a pedir solos para no entrar en bucle. */
    val deniedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * El usuario acepto el aviso de seguridad de las camaras que no son de ETVR/Babble. La app no
     * puede saber si una webcam o un modulo DIY es seguro para apuntarlo a los ojos, asi que no las
     * lee hasta que alguien lo acepte a proposito.
     */
    @Volatile var riskAccepted = false

    fun loadRiskAccepted(context: Context): Boolean {
        riskAccepted = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(RISK_KEY, false)
        return riskAccepted
    }

    fun loadChoice(context: Context, key: String): VideoChoice {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        return VideoChoice(
            prefs.getString(RESOLUTION_KEY + key, "").orEmpty(),
            prefs.getString(FPS_KEY + key, "").orEmpty(),
        )
    }

    fun saveChoice(context: Context, key: String, choice: VideoChoice) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putString(RESOLUTION_KEY + key, choice.resolution)
            .putString(FPS_KEY + key, choice.fps)
            .apply()
    }

    /**
     * Abrirse sola al conectar una camara (UsbAttachActivity). En Quest no molesta. En Pico, abrir
     * cualquier ventana, aunque sea invisible, saca al usuario del juego, asi que ahi viene apagado.
     */
    fun autoOpenEnabled(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(AUTO_OPEN_KEY, context.resources.getBoolean(R.bool.auto_open_default))

    fun setAutoOpen(context: Context, enabled: Boolean) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_OPEN_KEY, enabled).apply()
        applyAutoOpen(context)
    }

    /** Prende o apaga la ventana invisible de "USB conectado" segun lo elegido. */
    fun applyAutoOpen(context: Context) {
        val wanted = if (autoOpenEnabled(context)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(context, UsbAttachActivity::class.java)
        val pm = context.packageManager
        if (pm.getComponentEnabledSetting(component) != wanted) {
            pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }
    }

    /** Salida experimental para otras apps de PC, si esta encendida (la maneja el servicio). */
    @Volatile var lanBridge: LanBridgeOutput? = null

    fun lanBridgeEnabled(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(LAN_BRIDGE_KEY, false)

    fun setLanBridgeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putBoolean(LAN_BRIDGE_KEY, enabled).apply()
    }

    /** Identificador fijo de este visor como bridge: la app de PC lo usa para recordar la fuente elegida. */
    fun lanBridgeSourceId(context: Context): String {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        prefs.getString(LAN_BRIDGE_ID_KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(LAN_BRIDGE_ID_KEY, id).apply()
        return id
    }

    fun acceptRisk(context: Context) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putBoolean(RISK_KEY, true).apply()
        riskAccepted = true
    }

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
        val choice = loadChoice(context, key)
        slot.wantedResolution = choice.resolution
        slot.wantedFps = choice.fps
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

    private const val SETTINGS = "settings"
    private const val RISK_KEY = "diy_camera_risk_accepted"
    private const val RESOLUTION_KEY = "resolution:"
    private const val FPS_KEY = "fps:"
    private const val AUTO_OPEN_KEY = "auto_open_on_plug"
    private const val LAN_BRIDGE_KEY = "lan_bridge_output"
    private const val LAN_BRIDGE_ID_KEY = "lan_bridge_source_id"

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
