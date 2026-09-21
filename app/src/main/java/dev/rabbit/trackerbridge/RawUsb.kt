package dev.rabbit.trackerbridge

/**
 * USB directo por usbfs (raw_usb.c), por numero de interfaz y direccion de endpoint. Solo se usa
 * cuando Android no muestra una interfaz que si esta en los descriptores (visto en Pico con la
 * interfaz de video de las placas OpenIris): sin su UsbInterface, la API de Java no puede ni
 * reclamarla ni leer su endpoint.
 */
internal object RawUsb {
    val available: Boolean = try {
        System.loadLibrary("trackerbridge_iso")
        true
    } catch (_: Throwable) {
        false
    }

    /** Reclama la interfaz soltando el driver del kernel que la tenga. 0, o -errno. */
    @JvmStatic
    external fun claimInterface(fd: Int, iface: Int): Int

    @JvmStatic
    external fun releaseInterface(fd: Int, iface: Int): Int

    @JvmStatic
    external fun setInterface(fd: Int, iface: Int, alt: Int): Int

    /** Bytes leidos, 0 si se agoto el tiempo, o -errno. */
    @JvmStatic
    external fun bulkRead(fd: Int, endpoint: Int, data: ByteArray, length: Int, timeoutMs: Int): Int

    const val ENODEV = 19
    const val ESHUTDOWN = 108
}
