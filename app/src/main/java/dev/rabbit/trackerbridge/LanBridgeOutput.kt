package dev.rabbit.trackerbridge

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dev.rabbit.trackerbridge.LanBridgeProtocol.Slot
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Salida experimental para otras apps de PC, las que esperan un "bridge" por UDP en vez de leer una
 * URL MJPEG (ver LanBridgeProtocol).
 *
 * Busca la app en la red, la saluda, le dice una vez por segundo que camaras tiene y le manda los mismos
 * JPEG que ya salen por MJPEG. Si esta apagada no abre nada, y lo de ETVR y Babble sigue igual, porque
 * solo lee los cuadros que ya estan en cada FrameBuffer.
 */
class LanBridgeOutput(context: Context) {
    enum class State { SEARCHING, CONNECTED }

    private val appContext = context.applicationContext

    @Volatile var state = State.SEARCHING
        private set

    /** La PC a la que se esta mandando (la que respondio al saludo). */
    @Volatile var pcAddress: InetAddress? = null
        private set

    /** Slots que se estan mandando ahora, para la pantalla. */
    @Volatile var activeSlots: Set<Slot> = emptySet()
        private set

    @Volatile private var running = false
    @Volatile private var lastAckNanos = 0L
    @Volatile private var slots: Map<Slot, CameraSlot> = emptyMap()
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val threads = mutableListOf<Thread>()
    private val seq = AtomicInteger(0)
    private lateinit var identity: LanBridgeProtocol.Identity

    fun start() {
        if (running) return
        identity = LanBridgeProtocol.Identity(
            sourceId = Bridge.lanBridgeSourceId(appContext),
            appVersion = LanBridgeProtocol.numericVersion(appVersion()),
            device = Build.DEVICE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
        val s = try {
            openSocket()
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the LAN bridge socket", e)
            return
        }
        socket = s
        running = true
        state = State.SEARCHING
        // Sin esto, varios WiFi descartan los broadcasts (el anuncio de la PC llega por broadcast)
        multicastLock = appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("TrackerBridge::lanbridge")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        threads += thread(name = "lan-receive", isDaemon = true) { receiveLoop(s) }
        threads += thread(name = "lan-control", isDaemon = true) { controlLoop(s) }
        for (slot in Slot.values()) {
            threads += thread(name = "lan-${slot.key}", isDaemon = true) { frameLoop(s, slot) }
        }
        Log.i(TAG, "LAN bridge output started on port ${s.localPort} as ${identity.sourceId}")
    }

    fun stop() {
        running = false
        socket?.close()
        threads.forEach { it.join(1500) }
        threads.clear()
        socket = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        pcAddress = null
        activeSlots = emptySet()
        Log.i(TAG, "LAN bridge output stopped")
    }

    /**
     * En el mismo puerto que la PC, asi llegan sus anuncios por broadcast. Si esta ocupado (por ejemplo,
     * por otra app de bridge abierta en el visor), cualquier puerto sirve: la PC responde al saludo en
     * el puerto de origen.
     */
    private fun openSocket(): DatagramSocket = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(LanBridgeProtocol.PORT))
        }
    } catch (e: Exception) {
        Log.i(TAG, "Port ${LanBridgeProtocol.PORT} busy, using any port: ${e.message}")
        DatagramSocket().apply { broadcast = true }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            packet.length = buf.size
            try {
                s.receive(packet)
            } catch (_: Exception) {
                if (!running) break
                continue
            }
            when {
                LanBridgeProtocol.isAck(buf, packet.length) -> {
                    if (pcAddress != packet.address) Log.i(TAG, "PC app answered from ${packet.address.hostAddress}")
                    pcAddress = packet.address
                    lastAckNanos = System.nanoTime()
                    state = State.CONNECTED
                }
                // El anuncio de la PC: se le puede saludar directo, sin esperar al broadcast
                LanBridgeProtocol.isAnnounce(buf, packet.length) -> if (pcAddress == null) pcAddress = packet.address
            }
        }
    }

    /** Una vez por segundo: saludo, y el estado de cada slot. Sin respuesta, se vuelve a buscar. */
    private fun controlLoop(s: DatagramSocket) {
        while (running) {
            val attached = Bridge.snapshot().filter { it.camera != null }
            slots = LanBridgeProtocol.assignSlots(attached, { it.name }, { it.port })
            val streaming = slots.filterValues { it.camera?.state == CameraState.STREAMING }.keys
            val answered = lastAckNanos != 0L && System.nanoTime() - lastAckNanos < LOST_NANOS
            if (!answered) state = State.SEARCHING
            activeSlots = if (answered) streaming else emptySet()
            val pc = pcAddress
            try {
                if (!answered) {
                    for (b in broadcastAddresses()) send(s, LanBridgeProtocol.PROBE, b)
                }
                if (pc != null) {
                    send(s, LanBridgeProtocol.PROBE, pc)
                    for (slot in Slot.values()) {
                        val json = LanBridgeProtocol.statusJson(slot, streaming, identity)
                        send(s, LanBridgeProtocol.statusPacket(slot, seq.incrementAndGet(), json), pc)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "LAN bridge control send failed: ${e.message}")
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** Cada cuadro nuevo de la camara de ese slot, entero en un paquete, solo si la PC respondio. */
    private fun frameLoop(s: DatagramSocket, slot: Slot) {
        var buffer: FrameBuffer? = null
        var lastSeq = 0L
        while (running) {
            val frames = slots[slot]?.frames
            if (frames !== buffer) {
                buffer = frames
                lastSeq = frames?.latest()?.seq ?: 0L
            }
            val pc = pcAddress
            if (frames == null || pc == null || state != State.CONNECTED) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            val f = frames.awaitNewer(lastSeq, 200) ?: continue
            lastSeq = f.seq
            val packet = LanBridgeProtocol.framePacket(slot, seq.incrementAndGet(), f.seq.toInt(), f.data) ?: continue
            try {
                send(s, packet, pc)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "LAN bridge frame send failed: ${e.message}")
            }
        }
    }

    private fun send(s: DatagramSocket, data: ByteArray, to: InetAddress) {
        s.send(DatagramPacket(data, data.size, to, LanBridgeProtocol.PORT))
    }

    /** El broadcast de la red WiFi y el general; algunos routers solo dejan pasar uno de los dos. */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNullTo(result) { it.broadcast }
        } catch (_: Exception) {
        }
        result += InetAddress.getByName("255.255.255.255")
        return result.distinct()
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private companion object {
        const val TAG = "LanBridge"
        /** Sin respuesta de la PC en este tiempo, se deja de mandar video y se vuelve a buscar. */
        const val LOST_NANOS = 5_000_000_000L
    }
}
