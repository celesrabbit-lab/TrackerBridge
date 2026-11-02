package dev.rabbit.trackerbridge

import android.content.ContentValues
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Registro de la app: va al del sistema y ademas guarda las ultimas lineas en memoria. En el Quest el
 * registro del sistema se llena rapido con mensajes de otras apps; asi lo importante sigue ahi cuando
 * alguien guarda el registro para mandarlo.
 */
object AppLog {
    private const val MAX_LINES = 600
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I", tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w(tag, msg, e)
        add("W", tag, if (e == null) msg else "$msg: $e")
    }

    private fun add(level: String, tag: String, msg: String) = synchronized(lines) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${time.format(Date())} $level $tag: $msg")
    }

    fun recent(): List<String> = synchronized(lines) { lines.toList() }
}

/** Guarda en Descargas un archivo de texto con el estado de la app y su registro, para diagnosticar. */
object DiagnosticLog {
    /** Devuelve el nombre del archivo guardado. Hace E/S: no llamar desde la pantalla. */
    fun save(context: Context): String {
        val name = "TrackerBridge-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
        val text = build(context)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the file")
        (resolver.openOutputStream(uri) ?: throw IOException("Could not open $uri")).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        return name
    }

    private fun build(context: Context): String = buildString {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        appendLine("Tracker Bridge $version (${context.packageName})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}")
        appendLine("Saved: ${Date()}")
        appendLine("Shown IP: ${NetUtils.localIpv4() ?: "none"}")
        appendLine()

        appendLine("== Network interfaces ==")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                val ips = ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress }
                if (ips.isNotEmpty()) appendLine("${ni.name} up=${ni.isUp}: ${ips.joinToString(", ")}")
            }
        } catch (e: Exception) {
            appendLine("error: $e")
        }
        appendLine()

        appendLine("== USB devices ==")
        val usb = context.getSystemService(UsbManager::class.java)
        val devices = usb?.deviceList?.values.orEmpty()
        if (devices.isEmpty()) appendLine("none")
        devices.forEach {
            appendLine(
                "%s %04x:%04x '%s' interfaces [%s] permission=%s".format(
                    Locale.ROOT, it.deviceName, it.vendorId, it.productId, it.productName ?: "",
                    it.interfaceSummary(), usb?.hasPermission(it),
                )
            )
        }
        appendLine()

        appendLine("== Cameras ==")
        appendLine("service running=${Bridge.serviceRunning}, lan output=${Bridge.lanBridge?.state ?: "off"}")
        val slots = Bridge.snapshot()
        if (slots.isEmpty()) appendLine("none")
        slots.forEach { slot ->
            val cam = slot.camera
            appendLine(
                "'${slot.name}' key=${slot.key} port=${slot.port} device=${slot.deviceName} " +
                    "state=${cam?.state} problem=${cam?.problem} mode=${cam?.resolution} diag=${cam?.diagnostics} " +
                    "fps=%.1f dropped=${slot.frames.droppedFrames} lastFrame=${slot.frames.lastFrameAgeMs()} ms ".format(Locale.ROOT, slot.frames.fps) +
                    "pcs=${slot.server.clientAddresses()} server=${slot.server.isRunning} portError=${slot.serverFailed}"
            )
        }
        appendLine()

        appendLine("== App log (most recent last) ==")
        AppLog.recent().forEach { appendLine(it) }
        appendLine()

        // Lo que quede en el registro del sistema de este proceso (errores de Android, cierres)
        appendLine("== System log for this process ==")
        appendLine(systemLog())
    }

    private fun systemLog(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        "not available: $e"
    }
}
