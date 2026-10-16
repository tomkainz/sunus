package de.tomkainz.sonoslive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

object Net {

    /**
     * Die WLAN-Adresse dieses Handys. Genau die muss in der Stream-URL stehen,
     * die wir dem Sonos nennen.
     *
     * Bewusst NICHT ueber activeNetwork: sobald Mobilfunk die Standardroute
     * stellt, liefert das die (fuer den Sonos unerreichbare) Mobilfunkadresse.
     * Darum gezielt die WLAN- bzw. Ethernet-Schnittstelle.
     */
    fun localIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null

        fun ipv4Of(network: Network): String? =
            cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                ?.hostAddress

        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (lan) ipv4Of(network)?.let { return it }
        }
        return cm.activeNetwork?.let { ipv4Of(it) }
    }
}
