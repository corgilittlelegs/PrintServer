package dev.jaspreet.printserver.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdAdvertiser(context: Context) : DiscoveryAdvertiser {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrations = java.util.concurrent.CopyOnWriteArrayList<NsdManager.RegistrationListener>()

    override fun advertiseIpp(name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            // The ",_universal" subtype is how macOS's Add Printer picker positively
            // flags a Bonjour service as AirPrint-capable during discovery, before it
            // ever reads TXT records or does an IPP round trip. Without it, Auto
            // Select falls through to a generic (and here, doomed) driver-database
            // lookup instead of trusting the IPP-Everywhere self-declaration.
            serviceType = "_ipp._tcp,_universal"
            setPort(port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        register(info)
    }

    override fun advertiseRaw(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_pdl-datastream._tcp"
            setPort(port)
        }
        register(info)
    }

    override fun advertiseEscl(name: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_uscan._tcp"
            setPort(port)
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        register(info)
    }

    private fun register(info: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "Registered ${i.serviceName} ${i.serviceType}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) { Log.e(TAG, "Registration failed: $error") }
            override fun onServiceUnregistered(i: NsdServiceInfo) { Log.i(TAG, "Unregistered ${i.serviceName}") }
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) { Log.e(TAG, "Unregistration failed: $error") }
        }
        registrations += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun stopAll() {
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        registrations.clear()
    }

    private companion object { const val TAG = "NsdAdvertiser" }
}
