package dev.rabbit.trackerbridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Recibe el aviso de "camara USB conectada" sin mostrar nada: despierta al servicio y se cierra.
 * Asi una camara que se reinicia no abre la ventana de la app encima del juego. Al abrirse por
 * este aviso, Android ademas le da a la app el permiso USB de esa camara.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(USB_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startBridge()
        } else {
            // Primera vez: hace falta la pantalla para aceptar el permiso de camaras USB
            openApp()
        }
        finish()
    }

    /**
     * Desde Android 12 hay sistemas que no dejan arrancar un servicio en primer plano desde el fondo.
     * Si pasa, se intenta despertar al servicio que ya este corriendo, y como ultimo recurso se abre
     * la pantalla: cualquier cosa antes que cerrarse con un error delante del usuario.
     */
    private fun startBridge() {
        val intent = Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_SCAN)
        try {
            startForegroundService(intent)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the service from the USB event", e)
        }
        try {
            startService(intent)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not wake the service either", e)
        }
        openApp()
    }

    private fun openApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the app", e)
        }
    }

    private companion object {
        const val TAG = "UsbAttach"
    }
}
