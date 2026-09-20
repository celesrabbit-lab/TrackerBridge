package dev.rabbit.trackerbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** Chips USB-serie que llevan las placas ESP32 sin USB nativo (ESP32-CAM, DevKit y programadores). */
enum class SerialKind(val label: String) {
    CDC("CDC"), CH34X("CH34x"), CP210X("CP210x"), FTDI("FTDI")
}

/** Por donde llega el video de una placa de serie. */
data class SerialPort(
    val kind: SerialKind,
    /** Interfaz de control del CDC; -1 en los chips de vendor. */
    val controlInterfaceId: Int,
    val dataInterfaceId: Int,
    val readEndpointAddress: Int,
    val maxPacketSize: Int,
) {
    /** FTDI mete 2 bytes de estado al principio de cada paquete: hay que sacarlos. */
    val stripsStatusBytes: Boolean get() = kind == SerialKind.FTDI
}

/**
 * Lo justo para leer un puerto serie por USB: reconocer el chip, ponerle la velocidad y quedarse con
 * el endpoint de entrada. No se escribe nada a la placa (ETVR y Babble tampoco lo hacen): OpenIris en
 * modo cableado manda el video apenas arranca.
 */
object UsbSerial {
    private const val TIMEOUT = 1000

    /** Velocidades que se prueban, de la mas rapida a la mas lenta. OpenIris usa 3.000.000. */
    val BAUD_RATES = listOf(3_000_000, 2_000_000, 1_500_000, 921_600, 115_200)

    fun kindOf(device: UsbDevice): SerialKind? = find(device)?.kind

    /** Devuelve por donde leer, o null si el aparato no es un puerto serie conocido. */
    fun find(device: UsbDevice): SerialPort? {
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            .filter { it.alternateSetting == 0 }

        // CDC-ACM estandar: interfaz de control (clase 2, subclase 2) y otra de datos (clase 10)
        val control = interfaces.firstOrNull {
            it.interfaceClass == UsbConstants.USB_CLASS_COMM && it.interfaceSubclass == CDC_ACM
        }
        val cdcData = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && bulkIn(it) != null }
        if (control != null && cdcData != null) return port(SerialKind.CDC, control.id, cdcData)

        // Chips de vendor: una sola interfaz con endpoints bulk
        val kind = vendorKind(device.vendorId) ?: return null
        val data = interfaces.firstOrNull { bulkIn(it) != null } ?: return null
        return port(kind, -1, data)
    }

    private fun vendorKind(vendorId: Int): SerialKind? = when (vendorId) {
        0x1A86 -> SerialKind.CH34X // QinHeng: CH340, CH341
        0x10C4 -> SerialKind.CP210X // Silicon Labs: CP2102, CP2102N, CP2104
        0x0403 -> SerialKind.FTDI // FTDI: FT232R, FT231X, FT232H
        else -> null
    }

    private fun port(kind: SerialKind, controlId: Int, data: UsbInterface): SerialPort? {
        val ep = bulkIn(data) ?: return null
        return SerialPort(kind, controlId, data.id, ep.address, ep.maxPacketSize.coerceAtLeast(64))
    }

    private fun bulkIn(iface: UsbInterface): UsbEndpoint? =
        (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN
        }

    fun claim(conn: UsbDeviceConnection, device: UsbDevice, port: SerialPort): List<UsbInterface> {
        val claimed = mutableListOf<UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.alternateSetting != 0) continue
            if (iface.id != port.controlInterfaceId && iface.id != port.dataInterfaceId) continue
            if (conn.claimInterface(iface, true)) claimed += iface
        }
        return claimed
    }

    fun readEndpoint(device: UsbDevice, port: SerialPort): UsbEndpoint? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.alternateSetting != 0 || iface.id != port.dataInterfaceId) continue
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.address == port.readEndpointAddress) return ep
            }
        }
        return null
    }

    /** Configura el chip a [baud]. Devuelve null si salio bien, o el detalle del error. */
    fun configure(conn: UsbDeviceConnection, port: SerialPort, baud: Int): String? = when (port.kind) {
        SerialKind.CDC -> configureCdc(conn, port, baud)
        SerialKind.CH34X -> configureCh34x(conn, baud)
        SerialKind.CP210X -> configureCp210x(conn, port, baud)
        SerialKind.FTDI -> configureFtdi(conn, port, baud)
    }

    private fun out(conn: UsbDeviceConnection, type: Int, request: Int, value: Int, index: Int): Boolean =
        conn.controlTransfer(type, request, value, index, null, 0, TIMEOUT) >= 0

    private fun configureCdc(conn: UsbDeviceConnection, port: SerialPort, baud: Int): String? {
        val line = Cdc.lineCoding(baud)
        if (conn.controlTransfer(0x21, 0x20, 0, port.controlInterfaceId, line, line.size, TIMEOUT) < 0) {
            return "SET_LINE_CODING failed"
        }
        // DTR encendido: con USB nativo (ESP32-S3) el firmware no manda nada hasta que el host
        // "abre" el puerto. Estas placas no tienen el circuito de reinicio automatico, asi que no
        // hay riesgo de reiniciarlas.
        if (!out(conn, 0x21, 0x22, 0x0001, port.controlInterfaceId)) return "SET_CONTROL_LINE_STATE failed"
        return null
    }

    private fun configureCh34x(conn: UsbDeviceConnection, baud: Int): String? {
        val regs = Ch34x.baudRegisters(baud) ?: return "$baud baud not supported by CH34x"
        val version = ByteArray(2)
        if (conn.controlTransfer(0xC0, 0x5F, 0, 0, version, 2, TIMEOUT) < 0) return "version read failed"
        if (!out(conn, 0x40, 0xA1, 0, 0)) return "init failed"
        if (!out(conn, 0x40, 0x9A, 0x1312, regs.first)) return "baud (1312) failed"
        if (!out(conn, 0x40, 0x9A, 0x0F2C, regs.second)) return "baud (0f2c) failed"
        if (!out(conn, 0x40, 0x9A, 0x2518, 0x00C3)) return "line control failed" // 8 bits, sin paridad
        if (!out(conn, 0x40, 0xA1, 0x501F, 0xD90A)) return "flow control failed"
        // DTR y RTS apagados: en las placas con reinicio automatico, otras combinaciones reinician
        // el ESP32 o lo dejan en el gestor de arranque
        if (!out(conn, 0x40, 0xA4, 0x00FF, 0)) return "modem control failed"
        return null
    }

    private fun configureCp210x(conn: UsbDeviceConnection, port: SerialPort, baud: Int): String? {
        val iface = port.dataInterfaceId
        if (!out(conn, 0x41, 0x00, 0x0001, iface)) return "IFC_ENABLE failed"
        val data = Cp210x.baudPayload(baud)
        if (conn.controlTransfer(0x41, 0x1E, 0, iface, data, data.size, TIMEOUT) < 0) return "SET_BAUDRATE failed"
        if (!out(conn, 0x41, 0x03, 0x0800, iface)) return "SET_LINE_CTL failed" // 8 bits, sin paridad, 1 stop
        if (!out(conn, 0x41, 0x07, 0x0300, iface)) return "SET_MHS failed" // DTR y RTS apagados
        return null
    }

    private fun configureFtdi(conn: UsbDeviceConnection, port: SerialPort, baud: Int): String? {
        val index = port.dataInterfaceId + 1 // FTDI numera los puertos desde 1
        if (!out(conn, 0x40, 0x00, 0, index)) return "reset failed"
        val encoded = Ftdi.encodeBaud(baud) ?: return "$baud baud not supported by FTDI"
        if (!out(conn, 0x40, 0x03, encoded and 0xFFFF, ((encoded ushr 16) shl 8) or index)) return "SET_BAUDRATE failed"
        if (!out(conn, 0x40, 0x04, 0x0008, index)) return "SET_DATA failed" // 8 bits, sin paridad, 1 stop
        if (!out(conn, 0x40, 0x02, 0, index)) return "SET_FLOW_CTRL failed"
        if (!out(conn, 0x40, 0x01, 0x0300, index)) return "modem control failed" // DTR y RTS apagados
        return null
    }

    private const val CDC_ACM = 2

    /** Cuentas de cada chip, aparte para poder probarlas sin el aparato. */
    object Cdc {
        /** SET_LINE_CODING: velocidad (4 bytes), 1 bit de stop, sin paridad, 8 bits de datos. */
        fun lineCoding(baud: Int) = byteArrayOf(
            baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(), 0, 0, 8,
        )
    }

    object Ch34x {
        /** Los dos registros de velocidad del CH340, o null si esa velocidad no entra. */
        fun baudRegisters(baud: Int): Pair<Int, Int>? {
            if (baud <= 0) return null
            var factor: Int
            var divisor: Int
            if (baud == 921_600) {
                divisor = 7
                factor = 0xF300
            } else {
                factor = BAUD_BASE / baud
                divisor = 3
                while (factor > 0xFFF0 && divisor > 0) {
                    factor = factor shr 3
                    divisor--
                }
                if (factor > 0xFFF0) return null
                factor = 0x10000 - factor
            }
            divisor = divisor or 0x0080 // si no, el CH341 espera a llenar el buffer antes de mandar
            return ((factor and 0xFF00) or divisor) to (factor and 0xFF)
        }

        private const val BAUD_BASE = 1_532_620_800
    }

    object Cp210x {
        /** SET_BAUDRATE lleva la velocidad tal cual, en 4 bytes. */
        fun baudPayload(baud: Int) = byteArrayOf(
            baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(),
        )
    }

    object Ftdi {
        private val FRACTION = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)

        /**
         * Divisor del reloj de 3 MHz, en octavos, con la parte fraccionaria en los bits 14 a 16,
         * como lo arma libftdi. 3.000.000 y 2.000.000 tienen codigos propios.
         */
        fun encodeBaud(baud: Int): Int? {
            if (baud < 183 || baud > 3_000_000) return null
            val eighths = (24_000_000 + baud / 2) / baud
            val encoded = (eighths shr 3) or (FRACTION[eighths and 0x7] shl 14)
            return when (encoded) {
                1 -> 0 // 3 MBaud
                0x4001 -> 1 // 2 MBaud
                else -> encoded
            }
        }

        /**
         * Saca los 2 bytes de estado que el chip mete al principio de cada paquete. Se compacta en el
         * mismo arreglo (lo que se escribe siempre va detras de lo que se lee) y devuelve cuanto quedo.
         */
        fun strip(buf: ByteArray, n: Int, packetSize: Int): Int {
            if (packetSize <= 2 || n <= 0) return 0
            var read = 0
            var write = 0
            while (read < n) {
                val chunk = minOf(packetSize, n - read)
                if (chunk > 2) {
                    System.arraycopy(buf, read + 2, buf, write, chunk - 2)
                    write += chunk - 2
                }
                read += chunk
            }
            return write
        }
    }
}
