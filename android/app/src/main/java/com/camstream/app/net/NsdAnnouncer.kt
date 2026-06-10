package com.camstream.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Anuncia el servicio por mDNS/Bonjour (`_camstream._tcp`) para que el
 * receptor de Linux encuentre el teléfono en la red local sin escribir
 * la IP a mano.
 */
class NsdAnnouncer(context: Context, private val port: Int) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(onStateChanged: (registered: Boolean) -> Unit) {
        if (listener != null) return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "CamStream-${Build.MODEL.replace(' ', '-')}"
            serviceType = SERVICE_TYPE
            port = this@NsdAnnouncer.port
            setAttribute("path", "/cam")
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registrado: ${info.serviceName}")
                onStateChanged(true)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registro mDNS falló: $errorCode")
                onStateChanged(false)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                onStateChanged(false)
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Anular registro mDNS falló: $errorCode")
            }
        }
        listener = registrationListener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregister() {
        listener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Listener ya anulado", e)
            }
        }
        listener = null
    }

    companion object {
        private const val TAG = "NsdAnnouncer"
        const val SERVICE_TYPE = "_camstream._tcp."
    }
}
