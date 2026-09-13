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
        startInForeground()
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
            NotificationChannel(CHANNEL_ID, "Puente de camaras", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tracker Bridge activo")
            .setContentText("Reenviando las camaras USB a la PC")
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
                Log.w(TAG, "WifiLock $mode no disponible", e)
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
        usbManager.deviceList.values.forEach { onAttached(it) }
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
            Log.i(TAG, "Permiso USB concedido para $label")
        } else {
            Bridge.deniedDevices += device.deviceName
            Bridge.message = if (elapsed < 1500) {
                "El Quest rechazo el permiso USB de $label sin mostrar aviso ($elapsed ms)"
            } else {
                "Permiso USB denegado para $label"
            }
            Log.w(TAG, "Permiso USB denegado para $label tras $elapsed ms")
        }
        scanDevices()
    }

    private fun onAttached(device: UsbDevice) {
        if (!device.isVideoDevice()) return
        if (Bridge.slotForDevice(device.deviceName)?.camera != null) return

        if (!usbManager.hasPermission(device)) {
            if (checkSelfPermission(USB_CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                Bridge.message = "Falta el permiso de camaras USB: abre Tracker Bridge y aceptalo"
            }
            // El permiso USB lo pide la pantalla de la app (en primer plano), no el servicio
            return
        }

        val raw = try {
            val conn = usbManager.openDevice(device) ?: throw IOException("openDevice devolvio null")
            try {
                conn.rawDescriptors
            } finally {
                conn.close()
            }
        } catch (e: Exception) {
            Bridge.message = "No se pudo abrir ${device.productName ?: device.deviceName}: ${e.message}"
            return
        }
        val info = UvcDescriptors.parse(raw)
        if (info == null) {
            Bridge.message = "${device.productName ?: device.deviceName} no es una camara MJPEG compatible"
            return
        }

        val serial = try {
            device.serialNumber
        } catch (_: SecurityException) {
            null
        }
        val name = device.productName?.trim().takeUnless { it.isNullOrEmpty() } ?: "Camara ${device.deviceId}"
        val baseKey = if (!serial.isNullOrBlank() && serial != DEFAULT_SERIAL) "sn:$serial" else "name:$name"
        detachMissing()
        val slot = Bridge.slotFor(this, baseKey, name)

        if (!slot.server.isRunning) {
            try {
                slot.server.start()
                slot.serverError = null
            } catch (e: IOException) {
                slot.serverError = "No se pudo abrir el puerto ${slot.port}: ${e.message}"
            }
        }

        val camera = UvcCamera(usbManager, device, info, slot.frames)
        slot.attach(device.deviceName, camera)
        camera.start()
        updateLocks()
        Log.i(TAG, "Camara '$name' ($baseKey) en puerto ${slot.port}")
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
        const val ACTION_USB_PERMISSION = "dev.rabbit.trackerbridge.USB_PERMISSION"
        /** Serial por defecto del firmware cuando no puede leer la MAC: no sirve para distinguir camaras. */
        private const val DEFAULT_SERIAL = "12345678"
        const val ACTION_START = "dev.rabbit.trackerbridge.START"
        const val ACTION_SCAN = "dev.rabbit.trackerbridge.SCAN"
    }
}
