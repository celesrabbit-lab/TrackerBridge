package dev.rabbit.trackerbridge

/** En que anda una camara. La pantalla pinta la tarjeta con esto. */
enum class CameraState { STARTING, WAITING_FOR_IMAGE, STREAMING, RETRYING, STOPPED }

/** Por que fallo el ultimo intento. La pantalla lo traduce al idioma elegido. */
enum class CameraProblem {
    OPEN_FAILED, ISOCHRONOUS, NO_VIDEO_INTERFACE, CLAIM_FAILED, NO_ENDPOINT,
    FORMAT_REJECTED, NO_BANDWIDTH, ISO_FAILED, STOPPED_SENDING, INVALID_FRAMES,
    SERIAL_SETUP_FAILED, NOT_A_TRACKER, OTHER,
}

/**
 * Una camara que manda cuadros JPEG, sea por video USB (UVC) o por puerto serie (placas ESP32 sin
 * USB nativo). El servicio y la pantalla solo ven esto.
 */
interface TrackerCamera {
    val state: CameraState
    val problem: CameraProblem?

    /** Lo que se negocio: "320x240@30", o el tamano del JPEG en las placas de serie. */
    val resolution: String

    /** Detalle tecnico para la tarjeta (modo USB, baudios) o el ultimo error, util en una captura. */
    val diagnostics: String

    fun start()
    fun stop()

    /** Volver a negociar el video (al cambiar resolucion o fps desde la pantalla). */
    fun reopenStream()
}
