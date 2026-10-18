package de.tomkainz.sonoslive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

object Net {

    /**
     * The Wi-Fi address of this phone. Exactly this one has to be in the stream URL
     * that we give the Sonos.
     *
     * Deliberately NOT via activeNetwork: as soon as mobile data provides the default
     * route, that returns the mobile address (unreachable for the Sonos).
     * So we pick the Wi-Fi or Ethernet interface specifically.
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
