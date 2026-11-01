package de.tomkainz.sonoslive

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

object Net {

    /**
     * The Wi-Fi address of this phone. Exactly this one has to be in the stream URL
     * that we give the Sonos.
     *
     * Deliberately NOT via activeNetwork: as soon as mobile data provides the default
     * route, that returns the mobile address (unreachable for the Sonos).
     * So we pick the Wi-Fi or Ethernet interface specifically. A VPN also reports
     * the Wi-Fi transport underneath it, but its tunnel address is just as
     * unreachable, so VPNs are skipped. With [peer] (the speaker), an address in
     * the speaker's subnet wins.
     */
    fun localIp(context: Context, peer: String? = null): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null

        fun ipv4Of(network: Network): List<LinkAddress> =
            cm.getLinkProperties(network)?.linkAddresses.orEmpty().filter {
                val a = it.address
                a is Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress
            }

        val candidates = mutableListOf<LinkAddress>()
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (lan) candidates += ipv4Of(network)
        }
        val target = peer?.let { runCatching { InetAddress.getByName(it) }.getOrNull() as? Inet4Address }
        val best = target?.let { t -> candidates.firstOrNull { sameSubnet(it, t) } } ?: candidates.firstOrNull()
        return (best ?: cm.activeNetwork?.let { ipv4Of(it).firstOrNull() })?.address?.hostAddress
    }

    private fun sameSubnet(link: LinkAddress, other: Inet4Address): Boolean {
        val bits = link.prefixLength.coerceIn(0, 32)
        if (bits == 0) return true
        val mask = -1 shl (32 - bits)
        return (toInt(link.address as Inet4Address) and mask) == (toInt(other) and mask)
    }

    private fun toInt(a: Inet4Address): Int =
        a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
}
