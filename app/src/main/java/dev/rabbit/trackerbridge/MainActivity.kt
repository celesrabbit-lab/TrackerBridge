package dev.rabbit.trackerbridge

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla liviana: solo nombres, direcciones y estado de cada camara. No muestra el video
 * para no gastar recursos del Quest; la imagen se ve en ETVR/Babble en la PC.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ipText: TextView
    private lateinit var messageText: TextView
    private lateinit var toggleButton: Button
    private lateinit var emptyText: TextView
    private lateinit var cardsContainer: LinearLayout
    private val cards = LinkedHashMap<String, CardViews>()
    private var permissionRequestInFlight = false

    private class CardViews(val title: TextView, val url: TextView, val status: TextView)

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        ensurePermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (hasCameraPermission()) startBridge(BridgeService.ACTION_SCAN)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    override fun onStop() {
        super.onStop()
        // La pantalla solo informa: al dejar de verse se cierra y libera su memoria.
        // El servicio sigue reenviando las camaras. No se cierra si hay un aviso de permiso abierto.
        if (!isChangingConfigurations && !permissionRequestInFlight && Bridge.pendingPermission == null) finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequestInFlight = false
        if (hasCameraPermission()) {
            startBridge(BridgeService.ACTION_START)
        } else {
            Bridge.message = "Sin el permiso de camaras USB el Quest no deja leer las camaras. Aceptalo para continuar."
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += USB_CAMERA_PERMISSION
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionRequestInFlight = true
            requestPermissions(needed.toTypedArray(), 1)
        }
        if (hasCameraPermission()) startBridge(BridgeService.ACTION_START)
    }

    private fun hasCameraPermission() =
        checkSelfPermission(USB_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun startBridge(action: String) {
        startForegroundService(Intent(this, BridgeService::class.java).setAction(action))
    }

    private fun refresh() {
        requestMissingUsbPermission()

        val ip = NetUtils.localIpv4()
        ipText.text = if (ip != null) "IP del Quest: $ip" else "El Quest no esta conectado a WiFi"
        toggleButton.text = if (Bridge.serviceRunning) "Detener puente" else "Iniciar puente"
        val msg = Bridge.message
        messageText.text = msg.orEmpty()
        messageText.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE

        val slots = Bridge.snapshot()
        if (slots.map { it.key } != cards.keys.toList()) {
            cardsContainer.removeAllViews()
            cards.clear()
            slots.forEach { cards[it.key] = addCard() }
        }
        emptyText.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (Bridge.serviceRunning) {
            "Conecta las camaras al hub del Quest. Si aparece un aviso de permiso USB, aceptalo " +
                "(marca \"usar siempre\" para que no vuelva a preguntar)."
        } else {
            "El puente esta detenido."
        }

        for (slot in slots) {
            val card = cards[slot.key] ?: continue
            val cam = slot.camera
            val streaming = cam?.state == UvcCamera.State.STREAMING
            card.title.text = slot.name
            card.title.setTextColor(if (cam != null) Color.WHITE else GRAY)
            card.url.text = "http://${ip ?: "IP-del-Quest"}:${slot.port}/"
            card.status.text = statusLine(slot)
            card.status.setTextColor(
                when {
                    streaming -> GREEN
                    cam != null -> YELLOW
                    else -> GRAY
                }
            )
        }
    }

    /** Pide el permiso USB desde la pantalla (primer plano), de a una camara por vez. */
    private fun requestMissingUsbPermission() {
        if (!Bridge.serviceRunning || !hasCameraPermission()) return
        val now = SystemClock.elapsedRealtime()
        if (Bridge.pendingPermission != null) {
            // Si el sistema nunca responde, se libera para poder reintentar
            if (now - Bridge.permissionRequestedAt < 30_000) return
            Bridge.pendingPermission = null
        }
        val usb = getSystemService(UsbManager::class.java)
        val device = usb.deviceList.values.firstOrNull {
            it.isVideoDevice() && !usb.hasPermission(it) && it.deviceName !in Bridge.deniedDevices
        } ?: return
        Bridge.pendingPermission = device.deviceName
        Bridge.permissionRequestedAt = now
        val pi = PendingIntent.getBroadcast(
            this, device.deviceId,
            Intent(BridgeService.ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usb.requestPermission(device, pi)
    }

    private fun statusLine(slot: CameraSlot): String {
        val cam = slot.camera
        val parts = mutableListOf<String>()
        when {
            cam == null -> parts += "● Desconectada"
            cam.state == UvcCamera.State.STREAMING -> {
                parts += "● Conectada"
                parts += "%.0f fps".format(slot.frames.fps)
            }
            else -> parts += "● ${cam.statusText}"
        }
        parts += if (slot.server.clientCount == 0) "PC sin conectar" else "PC conectada"
        slot.serverError?.let { parts += it }
        return parts.joinToString("  ·  ")
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(BACKGROUND)
        }
        root.addView(text("Tracker Bridge", 28f, bold = true))
        root.addView(text("Camaras USB del Quest → ETVR y Project Babble en la PC", 16f, SUBTLE))
        ipText = text("", 20f).also {
            it.setPadding(0, dp(12), 0, dp(4))
            root.addView(it)
        }
        root.addView(text("En la PC, pon cada direccion como camara en ETVR o Babble.", 15f, SUBTLE))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (Bridge.serviceRunning) {
                    stopService(Intent(this@MainActivity, BridgeService::class.java))
                } else {
                    ensurePermissionsAndStart()
                }
            }
        }
        buttons.addView(toggleButton)
        buttons.addView(Button(this).apply {
            text = "Buscar camaras"
            setOnClickListener { if (hasCameraPermission()) startBridge(BridgeService.ACTION_SCAN) else ensurePermissionsAndStart() }
        })
        root.addView(buttons)

        messageText = text("", 16f, YELLOW).also {
            it.setPadding(0, dp(4), 0, dp(8))
            root.addView(it)
        }
        emptyText = text("", 17f, SUBTLE).also {
            it.setPadding(0, dp(16), 0, 0)
            root.addView(it)
        }
        cardsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(cardsContainer)

        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(root)
        }
    }

    private fun addCard(): CardViews {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(CARD)
        }
        val title = text("", 22f, bold = true)
        val url = text("", 20f, ACCENT).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }
        val status = text("", 16f)
        card.addView(title)
        card.addView(url)
        card.addView(status)
        cardsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return CardViews(title, url, status)
    }

    private fun text(value: String, sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val BACKGROUND = Color.rgb(18, 22, 30)
        val CARD = Color.rgb(30, 38, 50)
        val SUBTLE = Color.rgb(170, 180, 195)
        val GRAY = Color.rgb(120, 130, 145)
        val ACCENT = Color.rgb(79, 195, 247)
        val GREEN = Color.rgb(102, 220, 130)
        val YELLOW = Color.rgb(255, 196, 0)
    }
}
