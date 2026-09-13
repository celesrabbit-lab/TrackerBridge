package dev.rabbit.trackerbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Process
import android.util.Log
import java.io.IOException

/**
 * Lee una camara UVC (MJPEG, endpoint bulk) directamente por la API USB host de Android,
 * sin pasar por el sistema de camaras. Asi sigue funcionando con la app en segundo plano.
 */
class UvcCamera(
    private val usbManager: UsbManager,
    val device: UsbDevice,
    val info: UvcInfo,
    private val frames: FrameBuffer,
) {
    enum class State { STARTING, STREAMING, RETRYING, STOPPED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var statusText = ""
        private set
    @Volatile var resolution = ""
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "uvc-${device.deviceName}").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(2000)
        thread = null
        state = State.STOPPED
        frames.resetStats()
    }

    private fun runLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        }
        while (running) {
            state = State.STARTING
            statusText = "Iniciando"
            try {
                openAndStream()
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "${device.productName}: ${e.message}", e)
                    statusText = e.message ?: e.javaClass.simpleName
                }
            }
            frames.resetStats()
            if (!running) break
            state = State.RETRYING
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
        state = State.STOPPED
    }

    private fun openAndStream() {
        val conn = usbManager.openDevice(device) ?: throw IOException("No se pudo abrir el USB (¿permiso?)")
        val claimed = mutableListOf<UsbInterface>()
        var endpoint: UsbEndpoint? = null
        try {
            val epDesc = info.bulkEndpoint()
                ?: throw IOException("La camara usa modo isocrono; este puente solo soporta bulk")

            findInterface(info.controlInterfaceId, 0)?.let { if (conn.claimInterface(it, true)) claimed += it }
            val vs0 = findInterface(info.streamingInterfaceId, 0)
                ?: throw IOException("No se encontro la interfaz de video")
            if (!conn.claimInterface(vs0, true)) throw IOException("No se pudo reclamar la interfaz de video")
            claimed += vs0

            val epIface = findInterface(info.streamingInterfaceId, epDesc.altSetting) ?: vs0
            if (epDesc.altSetting != 0) conn.setInterface(epIface)
            endpoint = (0 until epIface.endpointCount).map { epIface.getEndpoint(it) }
                .firstOrNull { it.address == epDesc.address && it.direction == UsbConstants.USB_DIR_IN }
                ?: throw IOException("No se encontro el endpoint de video")

            val frame = info.mjpegFrames.firstOrNull { it.width == 240 && it.height == 240 }
                ?: info.mjpegFrames.first()
            val interval = frame.intervals.filter { it > 0 }.minOrNull() ?: frame.defaultInterval
            val committed = negotiate(conn, frame, interval)

            val maxFrame = UvcDescriptors.u32(committed, 18)
            var maxPayload = if (committed.size >= 26) UvcDescriptors.u32(committed, 22) else 0
            if (maxPayload <= 0 || maxPayload > 1024 * 1024) {
                maxPayload = if (maxFrame in 1..(1024 * 1024)) maxFrame + 12 else 64 * 1024
            }
            val committedInterval = UvcDescriptors.u32(committed, 4).takeIf { it > 0 } ?: interval
            val fps = 10_000_000 / committedInterval.coerceAtLeast(1)
            resolution = "${frame.width}x${frame.height}@$fps"
            Log.i(TAG, "${device.productName}: UVC %04x, $resolution (pedido %d fps), payload $maxPayload, frame $maxFrame"
                .format(info.bcdUvc, 10_000_000 / interval.coerceAtLeast(1)))

            stream(conn, endpoint, maxPayload, maxFrame)
        } finally {
            endpoint?.let {
                // En modo bulk, CLEAR_FEATURE(ENDPOINT_HALT) le indica a la camara que deje de transmitir
                conn.controlTransfer(0x02, 0x01, 0, it.address, null, 0, 200)
            }
            claimed.forEach { conn.releaseInterface(it) }
            conn.close()
        }
    }

    private fun findInterface(id: Int, alt: Int): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == id && it.alternateSetting == alt }

    /** Probe/commit UVC. Prueba varias longitudes porque cada firmware acepta una distinta. */
    private fun negotiate(conn: UsbDeviceConnection, frame: UvcFrameDesc, interval: Int): ByteArray {
        val lengths = listOf(info.probeLength, 48, 34, 26).distinct()
        var lastError = "sin respuesta"
        for (len in lengths) {
            val probe = ByteArray(len)
            probe[2] = info.mjpegFormatIndex.toByte()
            probe[3] = frame.index.toByte()
            UvcDescriptors.putU32(probe, 4, interval)

            if (conn.controlTransfer(REQ_OUT, SET_CUR, VS_PROBE_CONTROL shl 8, info.streamingInterfaceId, probe, len, CTRL_TIMEOUT) < 0) {
                lastError = "SET_CUR probe ($len) fallo"
                continue
            }
            val cur = ByteArray(len)
            val got = conn.controlTransfer(REQ_IN, GET_CUR, VS_PROBE_CONTROL shl 8, info.streamingInterfaceId, cur, len, CTRL_TIMEOUT)
            val commit = if (got >= 26) cur else probe
            if (conn.controlTransfer(REQ_OUT, SET_CUR, VS_COMMIT_CONTROL shl 8, info.streamingInterfaceId, commit, len, CTRL_TIMEOUT) < 0) {
                lastError = "SET_CUR commit ($len) fallo"
                continue
            }
            return commit
        }
        throw IOException("La camara rechazo el formato: $lastError")
    }

    private fun stream(conn: UsbDeviceConnection, ep: UsbEndpoint, maxPayload: Int, maxFrame: Int) {
        // OpenIris usa payloads de 64 bytes: una llamada USB por payload es demasiado lenta (~30 fps).
        // Con un buffer grande, cada lectura igual termina en el paquete corto del final del cuadro.
        val readSize = roundUp(maxOf(maxPayload, READ_BUFFER), ep.maxPacketSize.coerceAtLeast(64))
        val readBuf = ByteArray(readSize)
        val parser = UvcPayloadParser(
            maxPayload = maxPayload,
            initialFrameCapacity = maxFrame.coerceIn(64 * 1024, 2 * 1024 * 1024),
            onFrame = { jpeg ->
                frames.publish(jpeg)
                if (state != State.STREAMING) {
                    state = State.STREAMING
                    statusText = "Transmitiendo"
                }
            },
            onDropped = { frames.countDropped() },
        )
        val started = System.nanoTime()
        var lastData = started
        var windowStart = started
        var windowReads = 0
        var windowBytes = 0L
        var windowFrames = parser.validFrames
        statusText = "Esperando imagen"
        while (running) {
            val n = conn.bulkTransfer(ep, readBuf, readSize, 500)
            val now = System.nanoTime()
            if (n > 0) {
                lastData = now
                windowReads++
                windowBytes += n
                parser.feed(readBuf, n, shortRead = n < readSize)
            } else if (now - lastData > STALL_NANOS) {
                throw IOException("La camara dejo de enviar imagen")
            }
            val lastGood = if (parser.lastFrameNanos != 0L) parser.lastFrameNanos else started
            if (now - lastGood > STALL_NANOS) {
                throw IOException("Llegan datos pero no imagenes validas")
            }
            if (now - windowStart >= STATS_NANOS) {
                // Solo al registro del sistema, para diagnosticar por adb
                val frameCount = parser.validFrames - windowFrames
                val perFrame = frameCount.coerceAtLeast(1).toDouble()
                Log.i(TAG, "${device.productName}: USB: %.1f fps · %.1f KB · %.1f lecturas/cuadro"
                    .format(frameCount * 1e9 / (now - windowStart), windowBytes / 1024.0 / perFrame, windowReads / perFrame))
                windowStart = now
                windowReads = 0
                windowBytes = 0
                windowFrames = parser.validFrames
            }
        }
    }

    private fun roundUp(value: Int, multiple: Int) = (value + multiple - 1) / multiple * multiple

    companion object {
        private const val TAG = "UvcCamera"
        private const val REQ_OUT = 0x21 // clase, interfaz, host -> dispositivo
        private const val REQ_IN = 0xA1
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x01
        private const val VS_COMMIT_CONTROL = 0x02
        private const val CTRL_TIMEOUT = 1000
        private const val STALL_NANOS = 3_000_000_000L
        private const val READ_BUFFER = 16 * 1024
        private const val STATS_NANOS = 10_000_000_000L
    }
}
