package dev.rabbit.trackerbridge

import java.util.Locale

/**
 * Estadisticas de una ventana de tiempo, solo para el registro del sistema (adb logcat): ritmo con que
 * llegan los cuadros por USB y cuanto tarda cada cuadro en pasar por el cable.
 */
class StreamStats(nominalIntervalNanos: Long, private val readsLabel: String) {
    /** Un intervalo 1,5 veces mas largo que el normal significa que la camara se salto un cuadro. */
    private val lateNanos = nominalIntervalNanos + nominalIntervalNanos / 2
    private val intervals = LongArray(MAX_SAMPLES)
    private var intervalCount = 0
    private val transfers = LongArray(MAX_SAMPLES)
    private var transferCount = 0
    private var lastFrameNanos = 0L
    private var frames = 0
    private var late = 0
    private var bytes = 0L

    private val dropReasons = LinkedHashMap<String, Int>()

    var reads = 0
    var packetErrors = 0

    fun onData(n: Int) {
        bytes += n
    }

    fun onDropped(reason: String) {
        dropReasons[reason] = (dropReasons[reason] ?: 0) + 1
    }

    fun onFrame(nanos: Long) {
        if (lastFrameNanos != 0L) {
            val interval = nanos - lastFrameNanos
            if (interval > lateNanos) late++
            if (intervalCount < MAX_SAMPLES) intervals[intervalCount++] = interval
        }
        lastFrameNanos = nanos
        frames++
    }

    fun onTransfer(nanos: Long) {
        if (nanos > 0 && transferCount < MAX_SAMPLES) transfers[transferCount++] = nanos
    }

    /** Texto de la ventana; despues reinicia los contadores (el ultimo cuadro queda para el proximo intervalo). */
    fun summarize(elapsedNanos: Long): String {
        val perFrame = frames.coerceAtLeast(1).toDouble()
        val sb = StringBuilder(String.format(
            Locale.ROOT, "%.1f fps · %.1f KB · %.1f %s/frame",
            frames * 1e9 / elapsedNanos.coerceAtLeast(1), bytes / 1024.0 / perFrame, reads / perFrame, readsLabel,
        ))
        if (intervalCount > 0) {
            intervals.sort(0, intervalCount)
            sb.append(String.format(
                Locale.ROOT, " · interval p50 %.1f p95 %.1f max %.1f ms, late %d",
                percentileMs(intervals, intervalCount, 50), percentileMs(intervals, intervalCount, 95),
                intervals[intervalCount - 1] / 1e6, late,
            ))
        }
        if (transferCount > 0) {
            transfers.sort(0, transferCount)
            sb.append(String.format(
                Locale.ROOT, " · transfer p50 %.1f max %.1f ms (%d)",
                percentileMs(transfers, transferCount, 50), transfers[transferCount - 1] / 1e6, transferCount,
            ))
        }
        if (packetErrors > 0) sb.append(" · packet errors $packetErrors")
        if (dropReasons.isNotEmpty()) {
            sb.append(" · dropped ").append(dropReasons.values.sum())
                .append(dropReasons.entries.joinToString(", ", " (", ")") { "${it.key}: ${it.value}" })
        }

        intervalCount = 0
        transferCount = 0
        frames = 0
        late = 0
        bytes = 0
        reads = 0
        packetErrors = 0
        dropReasons.clear()
        return sb.toString()
    }

    private fun percentileMs(values: LongArray, count: Int, p: Int) = values[(count - 1) * p / 100] / 1e6

    private companion object {
        const val MAX_SAMPLES = 4096
    }
}
