package dev.rabbit.trackerbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

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
            Bridge.message = UiMessage(R.string.msg_need_usb_camera_permission)
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
        ipText.text = if (ip != null) getString(R.string.quest_ip, ip) else getString(R.string.no_wifi)
        toggleButton.setText(if (Bridge.serviceRunning) R.string.stop_bridge else R.string.start_bridge)
        val msg = Bridge.message
        messageText.text = msg?.let { getString(it.res, *it.args) }.orEmpty()
        messageText.visibility = if (msg == null) View.GONE else View.VISIBLE

        val slots = Bridge.snapshot()
        if (slots.map { it.key } != cards.keys.toList()) {
            cardsContainer.removeAllViews()
            cards.clear()
            slots.forEach { cards[it.key] = addCard() }
        }
        emptyText.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
        emptyText.setText(if (Bridge.serviceRunning) R.string.empty_running else R.string.empty_stopped)

        for (slot in slots) {
            val card = cards[slot.key] ?: continue
            val cam = slot.camera
            val streaming = cam?.state == UvcCamera.State.STREAMING
            card.title.text = slot.name
            card.title.setTextColor(if (cam != null) Color.WHITE else GRAY)
            card.url.text = "http://${ip ?: getString(R.string.quest_ip_placeholder)}:${slot.port}/"
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
        when (cam?.state) {
            null, UvcCamera.State.STOPPED -> parts += getString(R.string.status_disconnected)
            UvcCamera.State.STREAMING -> {
                parts += getString(R.string.status_connected)
                parts += String.format(Locale.US, "%.0f fps", slot.frames.fps)
            }
            UvcCamera.State.STARTING -> parts += getString(R.string.status_starting)
            UvcCamera.State.WAITING_FOR_IMAGE -> parts += getString(R.string.status_waiting)
            UvcCamera.State.RETRYING -> parts += getString(R.string.status_reconnecting, problemText(cam.problem))
        }
        parts += getString(if (slot.server.clientCount == 0) R.string.pc_not_connected else R.string.pc_connected)
        if (slot.serverFailed) parts += getString(R.string.port_error, slot.port)
        return parts.joinToString("  ·  ")
    }

    private fun problemText(problem: UvcCamera.Problem?): String = getString(
        when (problem) {
            UvcCamera.Problem.OPEN_FAILED -> R.string.problem_open_failed
            UvcCamera.Problem.ISOCHRONOUS -> R.string.problem_isochronous
            UvcCamera.Problem.NO_VIDEO_INTERFACE -> R.string.problem_no_interface
            UvcCamera.Problem.CLAIM_FAILED -> R.string.problem_claim
            UvcCamera.Problem.NO_ENDPOINT -> R.string.problem_no_endpoint
            UvcCamera.Problem.FORMAT_REJECTED -> R.string.problem_format
            UvcCamera.Problem.STOPPED_SENDING -> R.string.problem_stopped
            UvcCamera.Problem.INVALID_FRAMES -> R.string.problem_invalid
            UvcCamera.Problem.OTHER, null -> R.string.problem_other
        }
    )

    /** Idioma de la app, independiente del Quest: sistema, English o Español (Android 13+). */
    private fun showLanguagePicker() {
        if (Build.VERSION.SDK_INT < 33) return
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        val tags = arrayOf("", "en", "es")
        val labels = arrayOf(getString(R.string.language_system), "English", "Español")
        val current = localeManager.applicationLocales.toLanguageTags()
        val checked = tags.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                // Android recrea la pantalla con el idioma nuevo
                localeManager.applicationLocales = LocaleList.forLanguageTags(tags[which])
            }
            .show()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(BACKGROUND)
        }
        root.addView(text(getString(R.string.app_name), 28f, bold = true))
        root.addView(text(getString(R.string.subtitle), 16f, SUBTLE))
        ipText = text("", 20f).also {
            it.setPadding(0, dp(12), 0, dp(4))
            root.addView(it)
        }
        root.addView(text(getString(R.string.pc_hint), 15f, SUBTLE))

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
            setText(R.string.scan_cameras)
            setOnClickListener { if (hasCameraPermission()) startBridge(BridgeService.ACTION_SCAN) else ensurePermissionsAndStart() }
        })
        if (Build.VERSION.SDK_INT >= 33) {
            buttons.addView(Button(this).apply {
                text = "${getString(R.string.language)} / Language"
                setOnClickListener { showLanguagePicker() }
            })
        }
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
