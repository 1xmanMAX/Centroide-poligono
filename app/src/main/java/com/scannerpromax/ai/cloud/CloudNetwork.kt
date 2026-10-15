package com.scannerpromax.ai.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** ¿Hay conexión utilizable para enviar páginas a la nube? ([unmeteredOnly] = solo Wi-Fi / red no medida). */
object CloudNetwork {
    fun isAvailable(context: Context, unmeteredOnly: Boolean): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!unmeteredOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        } catch (_: SecurityException) {
            true // sin permiso de estado de red: se intenta y, si falla, la cola espera la red
        }
    }
}
