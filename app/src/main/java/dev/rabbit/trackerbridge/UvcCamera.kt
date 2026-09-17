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
import java.util.Locale

/**
 * Lee una camara UVC (MJPEG) directamente por la API USB host de Android, sin pasar por el sistema
 * de camaras. Asi sigue funcionando con la app en segundo plano. Las placas OpenIris (ETVR, Babble)
 * mandan el video por un endpoint bulk; las webcams normales, por uno isocrono (codigo nativo).
 */
class UvcCamera(
    private val usbManager: UsbManager,
    val device: UsbDevice,
    val info: UvcInfo,
    private val frames: FrameBuffer,
) {
    enum class State { STARTING, WAITING_FOR_IMAGE, STREAMING, RETRYING, STOPPED }

    /** Por que fallo el ultimo intento. La pantalla lo traduce al idioma elegido. */
    enum class Problem {
        OPEN_FAILED, ISOCHRONOUS, NO_VIDEO_INTERFACE, CLAIM_FAILED, NO_ENDPOINT,
        FORMAT_REJECTED, NO_BANDWIDTH, ISO_FAILED, STOPPED_SENDING, INVALID_FRAMES, OTHER,
    }

    private class CameraException(val problem: Problem, message: String) : IOException(message)

    @Volatile var state = State.STOPPED
        private set
    @Volatile var problem: Problem? = null
        private set
    @Volatile var resolution = ""
        private set

    /** Solo webcams (isocronas): modo USB elegido o detalle tecnico del ultimo error, para la pantalla. */
    @Volatile var diagnostics = ""
        private set

    private val isochronous = info.bulkEndpoint() == null

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
            try {
                openAndStream()
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "${device.productName}: ${e.message}", e)
                    problem = (e as? CameraException)?.problem ?: Problem.OTHER
                    if (isochronous) diagnostics = e.message.orEmpty()
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
        val conn = usbManager.openDevice(device)
            ?: throw CameraException(Problem.OPEN_FAILED, "openDevice returned null (missing permission?)")
        val claimed = mutableListOf<UsbInterface>()
        var bulkEndpoint: UsbEndpoint? = null
        var isoInterface: UsbInterface? = null
        try {
            val bulk = info.bulkEndpoint()
            if (bulk == null) {
                if (info.isoEndpoints().isEmpty()) throw CameraException(Problem.NO_ENDPOINT, "No video endpoint in the descriptors")
                if (!IsoUsb.available) throw CameraException(Problem.ISOCHRONOUS, "Isochronous transfers not available (native library missing)")
            }

            findInterface(info.controlInterfaceId, 0)?.let { if (conn.claimInterface(it, true)) claimed += it }
            val vs0 = findInterface(info.streamingInterfaceId, 0)
                ?: throw CameraException(Problem.NO_VIDEO_INTERFACE, "Video streaming interface not found")
            if (!conn.claimInterface(vs0, true)) {
                throw CameraException(Problem.CLAIM_FAILED, "Could not claim the video streaming interface")
            }
            claimed += vs0

            if (bulk != null) {
                val epIface = findInterface(info.streamingInterfaceId, bulk.altSetting) ?: vs0
                if (bulk.altSetting != 0) conn.setInterface(epIface)
                bulkEndpoint = (0 until epIface.endpointCount).map { epIface.getEndpoint(it) }
                    .firstOrNull { it.address == bulk.address && it.direction == UsbConstants.USB_DIR_IN }
                    ?: throw CameraException(Problem.NO_ENDPOINT, "Video endpoint not found")
            }

            val frame = info.frameClosestTo240()
            val interval = frame.intervals.filter { it > 0 }.minOrNull()
                ?: frame.minContinuousInterval.takeIf { it > 0 }
                ?: frame.defaultInterval
            val committed = negotiate(conn, frame, interval)

            val maxFrame = UvcDescriptors.u32(committed, 18)
            var maxPayload = if (committed.size >= 26) UvcDescriptors.u32(committed, 22) else 0
            if (maxPayload <= 0 || maxPayload > 1024 * 1024) {
                maxPayload = if (maxFrame in 1..(1024 * 1024)) maxFrame + 12 else 64 * 1024
            }
            val committedInterval = UvcDescriptors.u32(committed, 4).takeIf { it > 0 } ?: interval
            val fps = 10_000_000 / committedInterval.coerceAtLeast(1)
            resolution = "${frame.width}x${frame.height}@$fps"
            Log.i(TAG, "${device.productName}: UVC %04x, $resolution (requested %d fps), payload $maxPayload, frame $maxFrame"
                .format(Locale.ROOT, info.bcdUvc, 10_000_000 / interval.coerceAtLeast(1)))
            val nominalNanos = committedInterval * 100L

            if (bulkEndpoint != null) {
                stream(conn, bulkEndpoint, maxPayload, maxFrame, nominalNanos)
            } else {
                isoInterface = vs0
                streamIso(conn, maxPayload, maxFrame, nominalNanos)
            }
        } finally {
            bulkEndpoint?.let {
                // En modo bulk, CLEAR_FEATURE(ENDPOINT_HALT) le indica a la camara que deje de transmitir
                conn.controlTransfer(0x02, 0x01, 0, it.address, null, 0, 200)
            }
            // En modo isocrono, volver al alt setting 0 apaga el video y libera el ancho de banda
            isoInterface?.let { conn.setInterface(it) }
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
        var lastError = "no response"
        for (len in lengths) {
            val probe = ByteArray(len)
            probe[2] = info.mjpegFormatIndex.toByte()
            probe[3] = frame.index.toByte()
            UvcDescriptors.putU32(probe, 4, interval)

            if (conn.controlTransfer(REQ_OUT, SET_CUR, VS_PROBE_CONTROL shl 8, info.streamingInterfaceId, probe, len, CTRL_TIMEOUT) < 0) {
                lastError = "SET_CUR probe ($len) failed"
                continue
            }
            val cur = ByteArray(len)
            val got = conn.controlTransfer(REQ_IN, GET_CUR, VS_PROBE_CONTROL shl 8, info.streamingInterfaceId, cur, len, CTRL_TIMEOUT)
            val commit = if (got >= 26) cur else probe
            if (conn.controlTransfer(REQ_OUT, SET_CUR, VS_COMMIT_CONTROL shl 8, info.streamingInterfaceId, commit, len, CTRL_TIMEOUT) < 0) {
                lastError = "SET_CUR commit ($len) failed"
                continue
            }
            return commit
        }
        throw CameraException(Problem.FORMAT_REJECTED, "Camera rejected the video format: $lastError")
    }

    private fun markStreaming() {
        if (state != State.STREAMING) {
            state = State.STREAMING
            problem = null
        }
    }

    private fun stream(conn: UsbDeviceConnection, ep: UsbEndpoint, maxPayload: Int, maxFrame: Int, nominalNanos: Long) {
        // OpenIris usa payloads de 64 bytes: una llamada USB por payload es demasiado lenta (~30 fps).
        // Con un buffer grande, cada lectura igual termina en el paquete corto del final del cuadro.
        val readSize = roundUp(maxOf(maxPayload, READ_BUFFER), ep.maxPacketSize.coerceAtLeast(64))
        val packetSize = ep.maxPacketSize.coerceAtLeast(64)
        val readBuf = ByteArray(readSize)
        val stats = StreamStats(nominalNanos, "reads")
        lateinit var parser: UvcPayloadParser
        parser = UvcPayloadParser(
            maxPayload = maxPayload,
            initialFrameCapacity = maxFrame.coerceIn(64 * 1024, 2 * 1024 * 1024),
            onFrame = { jpeg ->
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
        var lastData = started
        var windowStart = started
        // Cada tanto, al empezar un cuadro se lee primero un solo paquete: marca cuando empezo a llegar,
        // y asi el registro muestra cuanto tarda un cuadro en pasar por el cable
        var framesUntilSample = TRANSFER_SAMPLE_EVERY
        var atFrameBoundary = false
        var sampleStart = 0L
        state = State.WAITING_FOR_IMAGE
        while (running) {
            val sampling = atFrameBoundary && sampleStart == 0L && framesUntilSample <= 0
            val size = if (sampling) packetSize else readSize
            val framesBefore = parser.validFrames
            val n = conn.bulkTransfer(ep, readBuf, size, 500)
            val now = System.nanoTime()
            if (n > 0) {
                lastData = now
                stats.reads++
                stats.onData(n)
                if (sampling) {
                    sampleStart = now
                    framesUntilSample = TRANSFER_SAMPLE_EVERY
                }
                parser.feed(readBuf, n, shortRead = n < size)
                val completed = parser.validFrames - framesBefore
                if (completed > 0) {
                    framesUntilSample -= completed.toInt()
                    if (sampleStart != 0L && !sampling) {
                        stats.onTransfer(parser.lastFrameNanos - sampleStart)
                        sampleStart = 0L
                    }
                }
                atFrameBoundary = completed > 0 && n < size
            } else if (now - lastData > STALL_NANOS) {
                throw CameraException(Problem.STOPPED_SENDING, "Camera stopped sending data")
            }
            val lastGood = if (parser.lastFrameNanos != 0L) parser.lastFrameNanos else started
            if (now - lastGood > STALL_NANOS) {
                throw CameraException(Problem.INVALID_FRAMES, "Receiving data but no valid frames")
            }
            if (now - windowStart >= STATS_NANOS) {
                // Solo al registro del sistema, para diagnosticar por adb
                Log.i(TAG, "${device.productName}: USB: ${stats.summarize(now - windowStart)}")
                windowStart = now
            }
        }
    }

    /**
     * Webcams: usa el alt setting mas chico que alcanza para el payload que pidio la camara. Si el USB
     * no tiene ancho de banda para ese (por ejemplo, otra webcam en el mismo hub), prueba los mas chicos.
     */
    private fun streamIso(conn: UsbDeviceConnection, maxPayload: Int, maxFrame: Int, nominalNanos: Long) {
        val candidates = info.isoEndpoints()
        val first = candidates.indexOfFirst { it.effectivePacketSize >= maxPayload }
            .let { if (it < 0) candidates.lastIndex else it }
        var bandwidthRefused = false
        var lastFailure = ""
        for (i in first downTo 0) {
            if (!running) return
            val ep = candidates[i]
            val alt = findInterface(info.streamingInterfaceId, ep.altSetting) ?: continue
            if (!conn.setInterface(alt)) {
                bandwidthRefused = true
                lastFailure = "alt ${ep.altSetting} (${ep.effectivePacketSize} B) refused"
                Log.w(TAG, "${device.productName}: $lastFailure")
                continue
            }
            val handle = IsoUsb.open(conn.fileDescriptor, ep.address, ep.effectivePacketSize, PACKETS_PER_URB, URB_COUNT)
            if (handle <= 0L) {
                if (handle == -ENOSPC.toLong()) bandwidthRefused = true
                lastFailure = "alt ${ep.altSetting} (${ep.effectivePacketSize} B): errno ${-handle}"
                Log.w(TAG, "${device.productName}: $lastFailure")
                continue
            }
            try {
                diagnostics = "$resolution · USB alt ${ep.altSetting} (${ep.effectivePacketSize} B)"
                Log.i(TAG, "${device.productName}: isochronous, $diagnostics, camera asked for $maxPayload B")
                readIso(handle, ep, maxFrame, nominalNanos)
            } finally {
                IsoUsb.close(handle)
            }
            return
        }
        throw CameraException(
            if (bandwidthRefused) Problem.NO_BANDWIDTH else Problem.ISO_FAILED,
            "Could not start isochronous video: $lastFailure",
        )
    }

    private fun readIso(handle: Long, ep: UvcStreamingEndpoint, maxFrame: Int, nominalNanos: Long) {
        val data = ByteArray(ep.effectivePacketSize * PACKETS_PER_URB)
        val lengths = IntArray(PACKETS_PER_URB)
        val statuses = IntArray(PACKETS_PER_URB)
        val stats = StreamStats(nominalNanos, "URBs")
        lateinit var assembler: IsoFrameAssembler
        assembler = IsoFrameAssembler(
            initialFrameCapacity = maxFrame.coerceIn(64 * 1024, 2 * 1024 * 1024),
            onFrame = { jpeg ->
                frames.publish(jpeg)
                stats.onFrame(assembler.lastFrameNanos)
                stats.onTransfer(assembler.lastTransferNanos)
                markStreaming()
            },
            onDropped = {
                frames.countDropped()
                stats.onDropped(assembler.lastDropReason)
            },
        )
        val started = System.nanoTime()
        var lastData = started
        var lastImageBytes = 0L
        var windowStart = started
        state = State.WAITING_FOR_IMAGE
        while (running) {
            val n = IsoUsb.read(handle, data, lengths, statuses, 100)
            val now = System.nanoTime()
            if (n < 0) {
                val problem = if (n == -ENODEV) Problem.STOPPED_SENDING else Problem.ISO_FAILED
                throw CameraException(problem, "Isochronous read failed (errno ${-n})")
            }
            if (n > 0) {
                stats.reads++
                assembler.beginBatch(now)
                var offset = 0
                for (i in 0 until n) {
                    if (statuses[i] != 0) stats.packetErrors++
                    assembler.onPacket(data, offset, lengths[i], statuses[i])
                    offset += lengths[i]
                }
                stats.onData(offset)
            }
            if (assembler.imageBytes != lastImageBytes) {
                lastImageBytes = assembler.imageBytes
                lastData = now
            } else if (now - lastData > STALL_NANOS) {
                throw CameraException(Problem.STOPPED_SENDING, "Camera stopped sending data")
            }
            val lastGood = if (assembler.lastFrameNanos != 0L) assembler.lastFrameNanos else started
            if (now - lastGood > STALL_NANOS) {
                throw CameraException(Problem.INVALID_FRAMES, "Receiving data but no valid frames")
            }
            if (now - windowStart >= STATS_NANOS) {
                Log.i(TAG, "${device.productName}: USB: ${stats.summarize(now - windowStart)}")
                windowStart = now
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
        private const val TRANSFER_SAMPLE_EVERY = 30

        // En USB 2.0 cada paquete isocrono es 1/8 ms: con 8 por pedido, un cuadro espera 1 ms como mucho
        private const val PACKETS_PER_URB = 8
        // 32 ms de pedidos en cola, por si el hilo se atrasa un momento
        private const val URB_COUNT = 32
        private const val ENODEV = 19
        private const val ENOSPC = 28
    }
}
