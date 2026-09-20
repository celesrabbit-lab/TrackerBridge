package dev.rabbit.trackerbridge

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Process
import android.util.Log
import java.io.IOException

/**
 * Placas sin USB nativo (ESP32-CAM y demas ESP32 clasicos): el video llega como JPEG por un puerto
 * serie, a traves del chip USB-serie de la placa. En la PC, ETVR y Babble lo leen del puerto COM; en
 * el visor lo lee esta clase y lo publica por HTTP igual que a las camaras UVC.
 *
 * La velocidad no se puede preguntar: se prueban las de [UsbSerial.BAUD_RATES] hasta que aparezca un
 * JPEG valido. Si con ninguna llega video, el aparato no es una camara y se deja de intentar.
 */
class SerialCamera(
    private val usbManager: UsbManager,
    val device: UsbDevice,
    private val frames: FrameBuffer,
) : TrackerCamera {
    private class CameraException(val problem: CameraProblem, message: String) : IOException(message)

    @Volatile override var state = CameraState.STOPPED
        private set
    @Volatile override var problem: CameraProblem? = null
        private set
    @Volatile override var resolution = ""
        private set
    @Volatile override var diagnostics = ""
        private set

    /** Los fps y el tamano los fija el firmware de la placa: no hay nada que negociar. */
    override fun reopenStream() = Unit

    @Volatile private var running = false
    private var thread: Thread? = null
    private var emptyCycles = 0

    override fun start() {
        if (running) return
        running = true
        emptyCycles = 0
        thread = Thread({ runLoop() }, "serial-${device.deviceName}").also { it.start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(2000)
        thread = null
        state = CameraState.STOPPED
        frames.resetStats()
    }

    private fun runLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        }
        while (running) {
            state = CameraState.STARTING
            try {
                if (openAndStream()) emptyCycles = 0 else emptyCycles++
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "${label()}: ${e.message}", e)
                    problem = (e as? CameraException)?.problem ?: CameraProblem.OTHER
                    diagnostics = e.message.orEmpty()
                    emptyCycles++
                }
            }
            frames.resetStats()
            if (!running) break
            // Nunca llego un JPEG: no es una camara (o esta apagada). Mejor dejar de molestar al USB.
            if (emptyCycles >= MAX_EMPTY_CYCLES) {
                problem = CameraProblem.NOT_A_TRACKER
                diagnostics = "no JPEG at ${UsbSerial.BAUD_RATES.joinToString("/")} baud"
                Log.w(TAG, "${label()}: no video after trying every baud rate $emptyCycles times")
                break
            }
            state = CameraState.RETRYING
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
        state = CameraState.STOPPED
        running = false
    }

    /** Devuelve true si llego video con alguna velocidad. */
    private fun openAndStream(): Boolean {
        val port = UsbSerial.find(device)
            ?: throw CameraException(CameraProblem.SERIAL_SETUP_FAILED, "No serial interface in the descriptors")
        val conn = usbManager.openDevice(device)
            ?: throw CameraException(CameraProblem.OPEN_FAILED, "openDevice returned null (missing permission?)")
        var claimed: List<UsbInterface> = emptyList()
        try {
            claimed = UsbSerial.claim(conn, device, port)
            if (claimed.isEmpty()) {
                throw CameraException(CameraProblem.CLAIM_FAILED, "Could not claim the ${port.kind.label} interface")
            }
            val endpoint = UsbSerial.readEndpoint(device, port)
                ?: throw CameraException(CameraProblem.NO_ENDPOINT, "Serial input endpoint not found")

            var lastError = ""
            for (baud in UsbSerial.BAUD_RATES) {
                if (!running) return false
                val error = UsbSerial.configure(conn, port, baud)
                if (error != null) {
                    lastError = "${port.kind.label} at $baud: $error"
                    Log.w(TAG, "${label()}: $lastError")
                    continue
                }
                diagnostics = "${port.kind.label} · $baud baud"
                Log.i(TAG, "${label()}: listening, $diagnostics")
                if (stream(conn, port, endpoint, baud)) return true
                if (!running) return false
            }
            if (lastError.isNotEmpty()) {
                throw CameraException(CameraProblem.SERIAL_SETUP_FAILED, lastError)
            }
            diagnostics = "${port.kind.label}: no JPEG at any baud rate"
            return false
        } finally {
            claimed.forEach { conn.releaseInterface(it) }
            conn.close()
        }
    }

    /**
     * Lee hasta que aparezca un cuadro valido; si no llega ninguno a tiempo, devuelve false para
     * probar la velocidad siguiente. Con video, se queda leyendo hasta que la placa se calle.
     */
    private fun stream(conn: UsbDeviceConnection, port: SerialPort, endpoint: UsbEndpoint, baud: Int): Boolean {
        val buf = ByteArray(READ_SIZE)
        val stats = StreamStats(NOMINAL_NANOS, "reads")
        lateinit var parser: SerialFrameParser
        parser = SerialFrameParser(
            onFrame = { jpeg ->
                if (resolution.isEmpty()) {
                    SerialFrameParser.jpegSize(jpeg)?.let {
                        resolution = "${it.first}x${it.second}"
                        diagnostics = "${port.kind.label} · $baud baud · $resolution"
                    }
                }
                frames.publish(jpeg)
                stats.onFrame(parser.lastFrameNanos)
                markStreaming()
            },
            onDropped = {
                frames.countDropped()
                stats.onDropped(parser.lastDropReason)
            },
        )
        val started = System.nanoTime()
        var windowStart = started
        var lastData = started
        state = CameraState.WAITING_FOR_IMAGE
        while (running) {
            val read = conn.bulkTransfer(endpoint, buf, buf.size, READ_TIMEOUT_MS)
            val now = System.nanoTime()
            if (read > 0) {
                lastData = now
                stats.reads++
                val n = if (port.stripsStatusBytes) UsbSerial.Ftdi.strip(buf, read, port.maxPacketSize) else read
                if (n > 0) {
                    stats.onData(n)
                    parser.feed(buf, n)
                }
            }
            if (parser.validFrames == 0L) {
                // Todavia sin imagen: quizas la velocidad esta mal, o la placa no es una camara
                if (now - started > SYNC_NANOS) {
                    Log.w(TAG, "${label()}: no JPEG at $baud baud (${parser.skippedBytes} bytes of noise)")
                    return false
                }
            } else {
                if (now - parser.lastFrameNanos > STALL_NANOS) {
                    throw CameraException(CameraProblem.STOPPED_SENDING, "The board stopped sending images")
                }
                if (now - lastData > STALL_NANOS) {
                    throw CameraException(CameraProblem.STOPPED_SENDING, "The board stopped sending data")
                }
            }
            if (now - windowStart >= STATS_NANOS) {
                Log.i(TAG, "${label()}: serial: ${stats.summarize(now - windowStart)}")
                windowStart = now
            }
        }
        return parser.validFrames > 0
    }

    private fun markStreaming() {
        if (state != CameraState.STREAMING) {
            state = CameraState.STREAMING
            problem = null
        }
    }

    private fun label() = device.productName ?: device.deviceName

    private companion object {
        const val TAG = "SerialCamera"
        const val READ_SIZE = 16 * 1024
        const val READ_TIMEOUT_MS = 200
        const val STALL_NANOS = 3_000_000_000L
        const val STATS_NANOS = 10_000_000_000L

        /** Cuanto se espera un JPEG antes de probar otra velocidad. */
        const val SYNC_NANOS = 2_500_000_000L

        /** Referencia para el registro: a 3 Mbaudios una placa OpenIris ronda los 30 fps. */
        const val NOMINAL_NANOS = 33_333_333L

        /** Vueltas completas sin recibir un solo JPEG antes de darse por vencido. */
        const val MAX_EMPTY_CYCLES = 3
    }
}
