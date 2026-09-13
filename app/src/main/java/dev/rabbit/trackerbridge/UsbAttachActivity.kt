package dev.rabbit.trackerbridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Recibe el aviso de "camara USB conectada" sin mostrar nada: despierta al servicio y se cierra.
 * Asi una camara que se reinicia no abre la ventana de la app encima del juego. Al abrirse por
 * este aviso, Android ademas le da a la app el permiso USB de esa camara.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(USB_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_SCAN))
        } else {
            // Primera vez: hace falta la pantalla para aceptar el permiso de camaras USB
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
