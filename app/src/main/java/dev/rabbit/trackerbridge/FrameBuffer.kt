package dev.rabbit.trackerbridge

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Guarda solo el ultimo JPEG de una camara. Los clientes lentos se saltan cuadros
 * en lugar de acumular retraso.
 */
class FrameBuffer {
    class Frame(val data: ByteArray, val seq: Long, val timestampNanos: Long)

    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private var latest: Frame? = null
    private var seq = 0L

    @Volatile var fps = 0f
        private set
    @Volatile var kbps = 0f
        private set
    @Volatile var droppedFrames = 0L
        private set

    // Solo el hilo de la camara toca la ventana de estadisticas
    private var windowStart = System.nanoTime()
    private var windowFrames = 0
    private var windowBytes = 0L

    fun publish(data: ByteArray) {
        val now = System.nanoTime()
        lock.withLock {
            seq++
            latest = Frame(data, seq, now)
            newFrame.signalAll()
        }
        windowFrames++
        windowBytes += data.size
        val elapsed = now - windowStart
        if (elapsed >= 1_000_000_000L) {
            fps = windowFrames * 1e9f / elapsed
            kbps = windowBytes * 8e6f / elapsed
            windowStart = now
            windowFrames = 0
            windowBytes = 0
        }
    }

    fun countDropped() {
        droppedFrames++
    }

    fun latest(): Frame? = lock.withLock { latest }

    /** Espera un cuadro con seq mayor que [lastSeq]. Devuelve null si se agota el tiempo. */
    fun awaitNewer(lastSeq: Long, timeoutMs: Long): Frame? {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (true) {
                val f = latest
                if (f != null && f.seq > lastSeq) return f
                if (remaining <= 0L) return null
                remaining = newFrame.awaitNanos(remaining)
            }
        }
    }

    /** Milisegundos desde el ultimo cuadro, o -1 si nunca llego ninguno. */
    fun lastFrameAgeMs(): Long {
        val f = latest() ?: return -1
        return (System.nanoTime() - f.timestampNanos) / 1_000_000L
    }

    fun resetStats() {
        fps = 0f
        kbps = 0f
    }
}
