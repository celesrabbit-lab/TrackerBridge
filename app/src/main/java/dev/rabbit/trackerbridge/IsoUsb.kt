package dev.rabbit.trackerbridge

/**
 * Transferencias USB isocronas con codigo nativo (iso_usb.c). La API USB de Android solo tiene control,
 * bulk e interrupt; las webcams UVC normales mandan el video por endpoints isocronos.
 */
internal object IsoUsb {
    val available: Boolean = try {
        System.loadLibrary("trackerbridge_iso")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Envia [urbCount] pedidos de [packetsPerUrb] paquetes de [packetSize] bytes al endpoint isocrono.
     * Devuelve un handle positivo, o -errno si fallo.
     */
    @JvmStatic
    external fun open(fd: Int, endpoint: Int, packetSize: Int, packetsPerUrb: Int, urbCount: Int): Long

    /**
     * Espera hasta [timeoutMs] a que termine un pedido y lo vuelve a enviar. Copia sus paquetes uno tras
     * otro en [data], y el largo y el estado de cada uno en [lengths] y [statuses].
     * Devuelve la cantidad de paquetes, 0 si se agoto el tiempo, o -errno si hubo un error.
     */
    @JvmStatic
    external fun read(handle: Long, data: ByteArray, lengths: IntArray, statuses: IntArray, timeoutMs: Int): Int

    /** Cancela los pedidos en curso y libera la memoria. */
    @JvmStatic
    external fun close(handle: Long)
}
