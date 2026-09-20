package dev.rabbit.trackerbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.IOException

/**
 * Servicio en primer plano: detecta las camaras USB, las lee y las publica por HTTP.
 * Sigue corriendo mientras usas Steam Link u otra app en el Quest.
 */
class BridgeService : Service() {
    private lateinit var usbManager: UsbManager
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var setupDone = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                ACTION_USB_PERMISSION -> onPermissionResult(device, intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(UsbManager::class.java)
        workerThread = HandlerThread("bridge-usb").also { it.start() }
        worker = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: Exception) {
            // Android 12+ puede negar el primer plano si el arranque vino del fondo. Rendirse en
            // silencio es mejor que cerrar la app: al abrirla, el puente arranca igual.
            Log.w(TAG, "Could not start in the foreground", e)
            Bridge.message = UiMessage(R.string.msg_service_blocked)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!setupDone) {
            setupDone = true
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter, null, worker)
            }
            Bridge.serviceRunning = true
            Bridge.message = null
        }
        val rescan = intent?.action == ACTION_SCAN
        worker.post {
            if (rescan) {
                Bridge.pendingPermission = null
                Bridge.deniedDevices.clear()
                Bridge.message = null
            }
            scanDevices()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Bridge.serviceRunning = false
        if (setupDone) unregisterReceiver(usbReceiver)
        worker.post {
            Bridge.clear()
            releaseLocks()
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    /** CPU y WiFi despiertos solo mientras haya alguna camara conectada, para no gastar bateria en vano. */
    private fun updateLocks() {
        if (Bridge.snapshot().any { it.camera != null }) acquireLocks() else releaseLocks()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerBridge::usb").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
        // Evita que el WiFi entre en ahorro de energia y agregue latencia
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        for (mode in modes) {
            try {
                wm.createWifiLock(mode, "TrackerBridge::wifi$mode").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                    wifiLocks += it
                }
            } catch (e: Exception) {
                Log.w(TAG, "WifiLock $mode not available", e)
            }
        }
    }

    private fun releaseLocks() {
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun scanDevices() {
        detachMissing()
        val devices = usbManager.deviceList.values
        // Todo lo que ve el sistema, para diagnosticar con un registro cuando algo no aparece
        Log.i(TAG, "USB devices: " + devices.joinToString(", ") {
            "%04x:%04x %s".format(it.vendorId, it.productId, it.productName ?: it.deviceName)
        }.ifEmpty { "none" })
        devices.forEach { onAttached(it) }
    }

    /** Por si se perdio un aviso de desconexion: suelta las camaras que ya no estan conectadas. */
    private fun detachMissing() {
        val present = usbManager.deviceList.keys
        Bridge.snapshot().forEach { slot ->
            val name = slot.deviceName
            if (name != null && name !in present) slot.detach()
        }
        updateLocks()
    }

    private fun onPermissionResult(device: UsbDevice, intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val elapsed = SystemClock.elapsedRealtime() - Bridge.permissionRequestedAt
        if (Bridge.pendingPermission == device.deviceName) Bridge.pendingPermission = null
        val label = device.productName ?: device.deviceName
        if (granted) {
            Bridge.message = null
            Log.i(TAG, "USB permission granted for $label")
        } else {
            Bridge.deniedDevices += device.deviceName
            Bridge.message = if (elapsed < 1500) {
                UiMessage(R.string.msg_usb_denied_no_prompt, label, elapsed)
            } else {
                UiMessage(R.string.msg_usb_denied, label)
            }
            Log.w(TAG, "USB permission denied for $label after $elapsed ms")
        }
        scanDevices()
    }

    private fun onAttached(device: UsbDevice) {
        val video = device.isVideoDevice()
        if (!video && !device.isSerialDevice()) return
        if (Bridge.slotForDevice(device.deviceName)?.camera != null) return

        if (!usbManager.hasPermission(device)) {
            if (checkSelfPermission(USB_CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                Bridge.message = UiMessage(R.string.msg_missing_permission_open_app)
            }
            // El permiso USB lo pide la pantalla de la app (en primer plano), no el servicio
            return
        }

        val label = device.productName ?: device.deviceName
        val raw = try {
            val conn = usbManager.openDevice(device) ?: throw IOException("openDevice returned null")
            try {
                conn.rawDescriptors
            } finally {
                conn.close()
            }
        } catch (e: Exception) {
            Bridge.message = UiMessage(R.string.msg_open_failed, label, e.message ?: e.javaClass.simpleName)
            return
        }
        // Descriptores crudos en el registro: permiten armar pruebas con camaras que no tenemos a mano
        Log.i(TAG, "USB descriptors of '$label': ${raw?.joinToString("") { "%02x".format(it) }}")

        // Las camaras USB de video se leen con UVC; las placas ESP32 sin USB nativo, por puerto serie
        val info = if (video) UvcDescriptors.parse(raw) else null
        if (video && info == null) {
            Bridge.message = UiMessage(R.string.msg_not_mjpeg, label)
            return
        }

        val serial = try {
            device.serialNumber
        } catch (_: SecurityException) {
            null
        }
        val serialKind = if (video) null else UsbSerial.kindOf(device)
        val name = device.productName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: serialKind?.let { getString(R.string.serial_default_name, it.label) }
            ?: getString(R.string.camera_default_name, device.deviceId)
        val baseKey = if (!serial.isNullOrBlank() && serial != DEFAULT_SERIAL) "sn:$serial" else "name:$name"
        detachMissing()
        val slot = Bridge.slotFor(this, baseKey, name)

        // Las camaras de ETVR/Babble usan un endpoint bulk; cualquier otra (webcam, modulo DIY) espera
        // a que el usuario acepte el aviso de seguridad antes de encenderse.
        // Solo las webcams normales (video USB sin endpoint bulk) esperan el aviso de seguridad:
        // las placas de ETVR/Babble, por USB o por serie, son las de siempre
        slot.blockedByRisk = info != null && info.bulkEndpoint() == null && !Bridge.loadRiskAccepted(this)
        if (slot.blockedByRisk) {
            Log.i(TAG, "Camera '$name' is waiting for the safety notice to be accepted")
            updateLocks()
            return
        }

        if (!slot.server.isRunning) {
            try {
                slot.server.start()
                slot.serverFailed = false
            } catch (e: IOException) {
                slot.serverFailed = true
                Log.w(TAG, "Could not open port ${slot.port}", e)
            }
        }

        slot.modes = info?.videoModes().orEmpty()
        val camera: TrackerCamera = if (info != null) {
            UvcCamera(usbManager, device, info, slot.frames) { slot.choice() }
        } else {
            SerialCamera(usbManager, device, slot.frames)
        }
        slot.attach(device.deviceName, camera)
        camera.start()
        updateLocks()
        Log.i(TAG, "Camera '$name' ($baseKey) on port ${slot.port}, ${serialKind?.label ?: "UVC"}")
    }

    private fun onDetached(device: UsbDevice) {
        if (Bridge.pendingPermission == device.deviceName) Bridge.pendingPermission = null
        Bridge.deniedDevices -= device.deviceName
        Bridge.slotForDevice(device.deviceName)?.detach()
        updateLocks()
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
        /** Serial por defecto del firmware cuando no puede leer la MAC: no sirve para distinguir camaras. */
        private const val DEFAULT_SERIAL = "12345678"
        const val ACTION_USB_PERMISSION = "dev.rabbit.trackerbridge.USB_PERMISSION"
        const val ACTION_START = "dev.rabbit.trackerbridge.START"
        const val ACTION_SCAN = "dev.rabbit.trackerbridge.SCAN"
    }
}
