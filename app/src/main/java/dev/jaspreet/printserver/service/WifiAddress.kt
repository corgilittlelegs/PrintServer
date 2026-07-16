package dev.jaspreet.printserver.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object WifiAddress {
    /** IPv4 address of the Wi-Fi interface, or null when Wi-Fi is down. Servers bind here — never 0.0.0.0. */
    fun get(context: Context): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(wifi)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
    }
}
