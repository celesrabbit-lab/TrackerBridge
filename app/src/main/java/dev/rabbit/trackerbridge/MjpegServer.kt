package dev.rabbit.trackerbridge

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Sirve una camara como stream MJPEG por HTTP, con el mismo formato que el firmware OpenIris
 * en modo WiFi. ETVR y Babble lo leen poniendo http://IP-del-Quest:puerto/ como direccion.
 */
class MjpegServer(val port: Int, private val frames: FrameBuffer) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<Socket>()

    val clientCount: Int get() = clients.size
    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running = true
        thread(name = "http-$port", isDaemon = true) {
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "http-$port-client", isDaemon = true) { handle(socket) }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        clients.forEach {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        clients.clear()
    }

    private fun handle(socket: Socket) {
        clients += socket
        try {
            socket.tcpNoDelay = true
            // Marca los paquetes como trafico de voz (WMM): el WiFi los prioriza frente a otros datos
            try {
                socket.trafficClass = 0xB8
            } catch (_: Exception) {
            }
            socket.soTimeout = 10_000
            // Buffer de envio chico: si la red se atrasa, se saltan cuadros en vez de acumular retraso
            socket.sendBufferSize = 64 * 1024

            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)

            when {
                path.startsWith("/favicon") -> sendText(out, 404, "Not Found", "")
                path.startsWith("/snapshot") || path.startsWith("/capture") || path.startsWith("/jpg") -> sendSnapshot(out)
                path.startsWith("/control") -> sendText(out, 200, "OK", "")
                else -> sendStream(out)
            }
        } catch (_: IOException) {
            // cliente desconectado
        } catch (e: Exception) {
            Log.w(TAG, "Error en cliente del puerto $port", e)
        } finally {
            clients -= socket
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun sendStream(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace;boundary=$BOUNDARY\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "X-Framerate: 60\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.flush()
        var lastSeq = 0L
        val digits = ByteArray(10)
        while (running) {
            val frame = frames.awaitNewer(lastSeq, 1000) ?: continue
            lastSeq = frame.seq
            // Mismo orden que el firmware OpenIris: separador, cabecera de la parte y JPEG.
            // La cabecera se arma con bytes fijos para no crear textos nuevos en cada cuadro.
            out.write(PART_PREFIX)
            writeDecimal(out, frame.data.size, digits)
            out.write(PART_SUFFIX)
            out.write(frame.data)
            out.flush()
        }
    }

    private fun sendSnapshot(out: OutputStream) {
        val frame = frames.latest() ?: frames.awaitNewer(0, 2000)
        if (frame == null) {
            sendText(out, 503, "Service Unavailable", "No image from the camera yet")
            return
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${frame.data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(frame.data)
        out.flush()
    }

    private fun sendText(out: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(bytes)
        out.flush()
    }

    private fun writeDecimal(out: OutputStream, value: Int, scratch: ByteArray) {
        var v = value
        var i = scratch.size
        do {
            scratch[--i] = ('0'.code + v % 10).toByte()
            v /= 10
        } while (v > 0)
        out.write(scratch, i, scratch.size - i)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "123456789000000000000987654321"
        private val PART_PREFIX =
            "\r\n--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ".toByteArray(Charsets.US_ASCII)
        private val PART_SUFFIX = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}
