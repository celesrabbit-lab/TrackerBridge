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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var usbText: TextView
    private lateinit var cardsContainer: LinearLayout
    private val cards = LinkedHashMap<String, CardViews>()
    private var permissionRequestInFlight = false
    private var riskDialog: AlertDialog? = null
    private var riskDeclined = false

    private class CardViews(
        val title: TextView,
        val url: TextView,
        val status: TextView,
        val resolution: Button,
        val fps: Button,
    )

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deja la apertura automatica como se eligio (en Pico viene apagada)
        Bridge.applyAutoOpen(this)
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
        showRiskNoticeIfNeeded()

        val ip = NetUtils.localIpv4()
        ipText.text = if (ip != null) getString(R.string.quest_ip, ip) else getString(R.string.no_wifi)
        toggleButton.setText(if (Bridge.serviceRunning) R.string.stop_bridge else R.string.start_bridge)
        val msg = Bridge.message
        messageText.text = msg?.let { getString(it.res, *it.args) }.orEmpty()
        messageText.visibility = if (msg == null) View.GONE else View.VISIBLE

        val slots = Bridge.snapshot()
        showOtherUsbDevices(slots)
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
            val streaming = cam?.state == CameraState.STREAMING
            card.title.text = slot.name
            card.title.setTextColor(if (cam != null) Color.WHITE else GRAY)
            card.url.text = "http://${ip ?: getString(R.string.quest_ip_placeholder)}:${slot.port}/"
            card.status.text = statusLine(slot)
            // Cada boton aparece solo si la camara ofrece mas de una opcion
            val resolutions = slot.resolutionOptions()
            card.resolution.visibility = if (resolutions.size > 1) View.VISIBLE else View.GONE
            card.resolution.text = getString(
                R.string.res_button, slot.wantedResolution.ifEmpty { getString(R.string.value_auto) }
            )
            card.resolution.setOnClickListener { showResolutionPicker(slot) }
            val fpsOptions = slot.fpsOptions()
            card.fps.visibility = if (fpsOptions.size > 1) View.VISIBLE else View.GONE
            card.fps.text = getString(R.string.fps_button, slot.wantedFps.ifEmpty { getString(R.string.value_auto) })
            card.fps.setOnClickListener { showFpsPicker(slot, fpsOptions) }
            card.status.setTextColor(
                when {
                    streaming -> GREEN
                    cam != null || slot.blockedByRisk -> YELLOW
                    else -> GRAY
                }
            )
        }
    }

    /**
     * Lo que hay conectado y no es una camara en marcha. Con una captura de esta lista alcanza para
     * saber si el visor ve el aparato, que es para la app y si le falta el permiso USB.
     */
    private fun showOtherUsbDevices(slots: List<CameraSlot>) {
        val usb = getSystemService(UsbManager::class.java)
        val working = slots.mapNotNull { if (it.camera != null) it.deviceName else null }.toSet()
        val others = usb.deviceList.values.filter { it.deviceName !in working }
        usbText.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        if (others.isEmpty()) return
        usbText.text = others.joinToString("\n", getString(R.string.usb_devices_title) + "\n") {
            describeUsbDevice(it, usb.hasPermission(it))
        }
    }

    private fun describeUsbDevice(device: UsbDevice, hasPermission: Boolean): String {
        val kind = when {
            device.isVideoDevice() -> getString(R.string.usb_kind_uvc)
            else -> UsbSerial.kindOf(device)?.let { getString(R.string.usb_kind_serial, it.label) }
                ?: getString(
                    R.string.usb_kind_unknown,
                    if (device.interfaceCount > 0) device.getInterface(0).interfaceClass else device.deviceClass,
                )
        }
        val permission = getString(
            if (hasPermission) R.string.usb_permission_ok else R.string.usb_permission_missing
        )
        return "%s  %04x:%04x  %s  ·  %s".format(
            device.productName?.trim().takeUnless { it.isNullOrEmpty() } ?: device.deviceName,
            device.vendorId, device.productId, kind, permission,
        )
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
            it.isTrackerCandidate() && !usb.hasPermission(it) && it.deviceName !in Bridge.deniedDevices
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

    /**
     * Aviso de seguridad para las camaras que no son de ETVR/Babble: la app no puede saber si el
     * infrarrojo de una webcam o de un modulo DIY es seguro para apuntarlo a los ojos.
     */
    private fun showRiskNoticeIfNeeded() {
        if (Bridge.riskAccepted || riskDeclined || riskDialog?.isShowing == true) return
        if (Bridge.snapshot().none { it.blockedByRisk }) return
        riskDialog = AlertDialog.Builder(this)
            .setTitle(R.string.risk_title)
            .setMessage(R.string.risk_message)
            .setCancelable(false)
            .setPositiveButton(R.string.risk_accept) { _, _ ->
                Bridge.acceptRisk(this)
                startBridge(BridgeService.ACTION_SCAN)
            }
            .setNegativeButton(R.string.risk_decline) { _, _ -> riskDeclined = true }
            .show()
    }

    /** Elegir la resolucion a mano; los fps siguen por su lado. */
    private fun showResolutionPicker(slot: CameraSlot) {
        val options = slot.resolutionOptions()
        if (options.isEmpty()) return
        val values = listOf("") + options.map { it.resolutionKey }
        val labels = listOf(getString(R.string.res_auto)) +
            options.map { getString(R.string.res_item, it.width, it.height) }
        showChoicePicker(getString(R.string.res_title, slot.name), labels, values, slot.wantedResolution) { picked ->
            if (picked != slot.wantedResolution) {
                slot.wantedResolution = picked
                applyChoice(slot)
            }
        }
    }

    /** Elegir los fps a mano, entre los que ofrece la camara con la resolucion elegida. */
    private fun showFpsPicker(slot: CameraSlot, options: List<Int>) {
        if (options.isEmpty()) return
        val values = listOf("") + options.map { it.toString() }
        val labels = listOf(getString(R.string.fps_auto)) + options.map { getString(R.string.fps_item, it) }
        showChoicePicker(getString(R.string.fps_title, slot.name), labels, values, slot.wantedFps) { picked ->
            if (picked != slot.wantedFps) {
                slot.wantedFps = picked
                applyChoice(slot)
            }
        }
    }

    /** Guarda lo elegido y hace que la camara vuelva a negociar el video, sin reconectar nada. */
    private fun applyChoice(slot: CameraSlot) {
        Bridge.saveChoice(this, slot.key, slot.choice())
        slot.camera?.reopenStream()
    }

    /** Lista de opciones con el aviso arriba: automatico primero y despues lo que ofrece la camara. */
    private fun showChoicePicker(
        title: String,
        labels: List<String>,
        values: List<String>,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        content.addView(text(getString(R.string.mode_warning), 15f, SUBTLE))
        val group = RadioGroup(this)
        labels.forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                id = i + 1
                text = label
                textSize = 18f
            })
        }
        group.check(values.indexOf(current).coerceAtLeast(0) + 1)
        content.addView(group)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.mode_close, null)
            .show()
        group.setOnCheckedChangeListener { _, checked ->
            onPick(values.getOrNull(checked - 1).orEmpty())
            dialog.dismiss()
        }
    }

    private fun statusLine(slot: CameraSlot): String {
        if (slot.blockedByRisk) return getString(R.string.status_risk)
        val cam = slot.camera
        val parts = mutableListOf<String>()
        when (cam?.state) {
            null, CameraState.STOPPED -> parts += getString(R.string.status_disconnected)
            CameraState.STREAMING -> {
                parts += getString(R.string.status_connected)
                parts += String.format(Locale.US, "%.0f fps", slot.frames.fps)
            }
            CameraState.STARTING -> parts += getString(R.string.status_starting)
            CameraState.WAITING_FOR_IMAGE -> parts += getString(R.string.status_waiting)
            CameraState.RETRYING -> parts += getString(R.string.status_reconnecting, problemText(cam.problem))
        }
        // Webcams: modo USB o detalle del error, para que una captura de pantalla sirva de diagnostico
        val detail = cam?.diagnostics?.takeIf { it.isNotEmpty() } ?: cam?.resolution?.takeIf { it.isNotEmpty() }
        detail?.let { parts += it }
        parts += getString(if (slot.server.clientCount == 0) R.string.pc_not_connected else R.string.pc_connected)
        if (slot.serverFailed) parts += getString(R.string.port_error, slot.port)
        return parts.joinToString("  ·  ")
    }

    private fun problemText(problem: CameraProblem?): String = getString(
        when (problem) {
            CameraProblem.OPEN_FAILED -> R.string.problem_open_failed
            CameraProblem.ISOCHRONOUS -> R.string.problem_isochronous
            CameraProblem.NO_VIDEO_INTERFACE -> R.string.problem_no_interface
            CameraProblem.CLAIM_FAILED -> R.string.problem_claim
            CameraProblem.NO_ENDPOINT -> R.string.problem_no_endpoint
            CameraProblem.FORMAT_REJECTED -> R.string.problem_format
            CameraProblem.NO_BANDWIDTH -> R.string.problem_bandwidth
            CameraProblem.ISO_FAILED -> R.string.problem_iso_failed
            CameraProblem.STOPPED_SENDING -> R.string.problem_stopped
            CameraProblem.INVALID_FRAMES -> R.string.problem_invalid
            CameraProblem.SERIAL_SETUP_FAILED -> R.string.problem_serial_setup
            CameraProblem.NOT_A_TRACKER -> R.string.problem_not_tracker
            CameraProblem.OTHER, null -> R.string.problem_other
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

        // Solo en los visores donde abrirse al conectar una camara interrumpe el juego (Pico)
        if (resources.getBoolean(R.bool.auto_open_toggle_visible)) {
            root.addView(CheckBox(this).apply {
                setText(R.string.auto_open_label)
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = Bridge.autoOpenEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked -> Bridge.setAutoOpen(this@MainActivity, checked) }
            })
            root.addView(text(getString(R.string.auto_open_note), 14f, SUBTLE))
        }

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
        usbText = text("", 14f, SUBTLE).also {
            it.setPadding(0, dp(16), 0, 0)
            it.visibility = View.GONE
            root.addView(it)
        }

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
        val resolution = Button(this).apply { visibility = View.GONE }
        val fps = Button(this).apply { visibility = View.GONE }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(resolution)
            addView(fps)
        }
        card.addView(title)
        card.addView(url)
        card.addView(status)
        card.addView(buttons)
        cardsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return CardViews(title, url, status, resolution, fps)
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
