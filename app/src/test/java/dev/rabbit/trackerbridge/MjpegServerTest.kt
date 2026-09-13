package dev.rabbit.trackerbridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread

class MjpegServerTest {
    private val port = 18081
    private val frames = FrameBuffer()
    private val server = MjpegServer(port, frames)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.stop()

    private fun fakeJpeg(i: Int) =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), i.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun readUntil(input: InputStream, delimiter: String): String {
        val sb = StringBuilder()
        while (!sb.endsWith(delimiter)) {
            val c = input.read()
            check(c >= 0) { "Conexion cerrada, recibido: $sb" }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    @Test
    fun streamConElFormatoDeOpenIris() {
        val jpegs = List(3) { fakeJpeg(it) }
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: quest\r\n\r\n".toByteArray())
            val input = DataInputStream(socket.getInputStream().buffered())

            val headers = readUntil(input, "\r\n\r\n")
            assertTrue(headers, headers.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(headers, "Content-Type: multipart/x-mixed-replace;boundary=123456789000000000000987654321\r\n" in headers)

            thread {
                Thread.sleep(150)
                jpegs.forEach {
                    frames.publish(it)
                    Thread.sleep(150)
                }
            }
            for (expected in jpegs) {
                val part = readUntil(input, "\r\n\r\n")
                assertTrue(part, part.startsWith("\r\n--123456789000000000000987654321\r\nContent-Type: image/jpeg\r\n"))
                val length = Regex("Content-Length: (\\d+)\r\n").find(part)!!.groupValues[1].toInt()
                val body = ByteArray(length)
                input.readFully(body)
                assertArrayEquals(expected, body)
            }
        }
    }

    @Test
    fun snapshotDevuelveElUltimoCuadro() {
        val jpeg = fakeJpeg(7)
        frames.publish(jpeg)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET /snapshot HTTP/1.1\r\n\r\n".toByteArray())
            val input = DataInputStream(socket.getInputStream().buffered())
            val headers = readUntil(input, "\r\n\r\n")
            assertTrue(headers, "Content-Type: image/jpeg\r\n" in headers)
            val body = ByteArray(Regex("Content-Length: (\\d+)").find(headers)!!.groupValues[1].toInt())
            input.readFully(body)
            assertArrayEquals(jpeg, body)
        }
    }
}
