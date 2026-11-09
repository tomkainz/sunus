package de.tomkainz.sonoslive

import android.content.Context
import android.net.wifi.WifiManager
import de.tomkainz.sonoslive.airplay.AirPlayFeatures
import de.tomkainz.sonoslive.airplay.Bplist
import de.tomkainz.sonoslive.airplay.RtspClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Finds every kind of speaker, and shows a device with several ways in only once. */
object Discovery {

    const val CAST_TYPE = "_googlecast._tcp.local"
    const val AIRPLAY_TYPE = "_airplay._tcp.local"
    const val RAOP_TYPE = "_raop._tcp.local"
    private const val RAOP_PORT = 5000
    private const val PASSWORD_FLAG = 0x80L

    /** Blocks for a few seconds. */
    fun search(context: Context): List<Speaker> {
        val localIp = Net.localIp(context)
        val found = Collections.synchronizedList(mutableListOf<Speaker>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ssdp = pool.submit { found += Ssdp.discover(context) }
            val mdns = pool.submit {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lock = wifi?.createMulticastLock("sunus-mdns")?.apply {
                    setReferenceCounted(true)
                    runCatching { acquire() }
                }
                try {
                    found += fromMdns(Mdns.browse(listOf(CAST_TYPE, AIRPLAY_TYPE, RAOP_TYPE), localIp))
                } catch (e: Exception) {
                    AppState.log("mDNS: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    runCatching { lock?.release() }
                }
            }
            runCatching { ssdp.get(8, TimeUnit.SECONDS) }
            runCatching { mdns.get(8, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        return merge(found.toList())
    }

    fun fromMdns(services: List<MdnsService>): List<Speaker> {
        val out = mutableListOf<Speaker>()
        for (s in services.filter { it.type.equals(CAST_TYPE, ignoreCase = true) }) {
            out += Speaker(
                room = s.txt["fn"]?.ifBlank { null } ?: s.instance,
                ip = s.ip,
                udn = "cast:" + (s.txt["id"]?.ifBlank { null } ?: s.instance),
                model = s.txt["md"]?.ifBlank { null } ?: "Google Cast",
                protocol = SpeakerProtocol.CAST,
                address = s.port.toString()
            )
        }
        val airplay = services.filter { it.type.equals(AIRPLAY_TYPE, ignoreCase = true) }
        val raop = services.filter { it.type.equals(RAOP_TYPE, ignoreCase = true) }
        for (s in airplay) {
            // The same device usually has a classic record too; AirPlay 2's says more.
            val classic = raop.firstOrNull { it.ip == s.ip }
            out += airPlaySpeaker(
                s.instance, s.ip, s.port,
                s.txt["deviceid"] ?: classic?.instance?.substringBefore('@') ?: s.instance,
                s.txt["model"] ?: classic?.txt?.get("am"),
                s.txt["features"] ?: classic?.txt?.get("ft"),
                wantsPassword(s.txt) || (classic != null && wantsPassword(classic.txt)),
                classic?.port
            )
        }
        for (s in raop.filter { r -> airplay.none { it.ip == r.ip } }) {
            out += airPlaySpeaker(
                s.instance.substringAfter('@'), s.ip, s.port, s.instance.substringBefore('@'),
                s.txt["am"], s.txt["ft"], wantsPassword(s.txt), s.port
            )
        }
        return out
    }

    /** Said outright, or as a bit in the flags. */
    private fun wantsPassword(txt: Map<String, String>): Boolean {
        val flags = (txt["sf"] ?: txt["flags"])?.removePrefix("0x")?.toLongOrNull(16) ?: 0
        return txt["pw"] == "true" || txt["pw"] == "1" || flags and PASSWORD_FLAG != 0L
    }

    private fun airPlaySpeaker(
        name: String, ip: String, port: Int, id: String, model: String?, features: String?, password: Boolean,
        classicPort: Int? = null
    ) = Speaker(
        room = name,
        ip = ip,
        udn = "airplay:$id",
        model = friendlyModel(model),
        protocol = SpeakerProtocol.AIRPLAY,
        address = port.toString(),
        details = AirPlayFeatures.toDetails(features, password, classicPort)
    )

    /** Apple's devices give an internal name. */
    fun friendlyModel(model: String?): String = when {
        model.isNullOrBlank() -> "AirPlay"
        model.startsWith("AudioAccessory1,") -> "HomePod"
        model.startsWith("AudioAccessory5,") -> "HomePod mini"
        model.startsWith("AudioAccessory6,") -> "HomePod"
        model.startsWith("AppleTV") -> "Apple TV"
        else -> model
    }

    /**
     * One row per device. Sonos keeps its own way, the one that has been proven; for the
     * others, the rest stays as a fallback for when the first way will not play. A Cast
     * group shares its address with a member, but is a speaker of its own.
     */
    fun merge(found: List<Speaker>): List<Speaker> {
        val sonosIps = found.filter { it.protocol == SpeakerProtocol.SONOS }.map { it.ip }.toSet()
        fun device(s: Speaker) = when {
            s.protocol == SpeakerProtocol.CAST && s.address.isNotEmpty() && s.address != CastChannel.DEFAULT_PORT.toString() ->
                "${s.ip}:${s.address}"
            else -> s.ip
        }
        return found
            // The first mention wins: a device that got a new address is still the same device.
            .distinctBy { it.udn }
            .filter { it.protocol == SpeakerProtocol.SONOS || it.ip !in sonosIps }
            .groupBy { if (it.protocol == SpeakerProtocol.SONOS) it.udn else device(it) }
            .values
            .map { same ->
                val ordered = same.sortedBy { it.protocol.ordinal }
                ordered.first().copy(alternatives = ordered.drop(1).map { it.copy(alternatives = emptyList()) })
            }
            .sortedBy { it.room.lowercase() }
    }

    /** Everything that answers at an address, for addresses typed in by hand. */
    fun probe(ip: String): List<Speaker> {
        val pool = Executors.newFixedThreadPool(4)
        val found = Collections.synchronizedList(mutableListOf<Speaker>())
        try {
            listOf(
                { Ssdp.probe(ip) },
                { probeUpnp(ip) },
                { probeCast(ip) },
                { probeAirPlay(ip) }
            ).map { pool.submit { runCatching { it() }.getOrNull()?.let { s -> found += s } } }
                .forEach { runCatching { it.get(8, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return merge(found.toList())
    }

    /**
     * Only asks that one address; many renderers answer a search sent directly to them.
     * The search goes out twice: with the address as HOST, as UDA 1.1 asks, and with the
     * multicast address. upmpdcli (libnpupnp) ignores a HOST that is neither its own IP
     * nor the multicast address, as when the user types a name instead of the IP.
     */
    fun probeUpnp(ip: String): Speaker? {
        val queries = listOf("$ip:1900", "239.255.255.250:1900").map { host ->
            (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $host\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ${Ssdp.MEDIA_RENDERER}\r\n\r\n"
                ).toByteArray()
        }
        val location = runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1200
                val target = InetAddress.getByName(ip)
                for (query in queries) socket.send(DatagramPacket(query, query.size, target, 1900))
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                Ssdp.locationOf(String(packet.data, 0, packet.length))
            }
        }.getOrNull()
        val candidates = listOfNotNull(location) + listOf(49152, 8080).map { "http://$ip:$it/description.xml" }
        for (url in candidates) {
            val device = UpnpDescription.fetch(url) ?: continue
            return UpnpDescription.speaker(device, url)
        }
        return null
    }

    fun probeCast(ip: String, port: Int = CastChannel.DEFAULT_PORT): Speaker? {
        if (!open(ip, port)) return null
        val status = runCatching { CastChannel(ip, port).use { it.receiverStatus() } }.getOrNull() ?: return null
        if (status["type"] != "RECEIVER_STATUS") return null
        val info = runCatching {
            MiniJson.parse(UpnpDescription.get("http://$ip:8008/setup/eureka_info")) as? Map<*, *>
        }.getOrNull()
        return Speaker(
            room = info?.get("name") as? String ?: ip,
            ip = ip,
            udn = "cast:" + (info?.get("ssdp_udn") as? String ?: ip),
            model = "Google Cast",
            protocol = SpeakerProtocol.CAST,
            address = port.toString()
        )
    }

    fun probeAirPlay(ip: String): Speaker? {
        for (port in listOf(de.tomkainz.sonoslive.airplay.AirPlayDriver.DEFAULT_PORT, RAOP_PORT)) {
            if (!open(ip, port)) continue
            val response = runCatching { RtspClient(ip, port).use { it.request("GET", "/info") } }.getOrNull() ?: continue
            if (!response.ok) {
                // It speaks the protocol, but /info is not for everyone. Worth listing nonetheless.
                if (response.header("server")?.contains("AirTunes") == true) {
                    return airPlaySpeaker(ip, ip, port, ip, null, null, false)
                }
                continue
            }
            val info = runCatching { Bplist.read(response.body) as? Map<*, *> }.getOrNull()
            val features = (info?.get("features") as? Long)?.let { "0x" + java.lang.Long.toHexString(it) }
            return airPlaySpeaker(
                info?.get("name") as? String ?: ip, ip, port,
                info?.get("deviceID") as? String ?: ip,
                info?.get("model") as? String, features, false
            )
        }
        return null
    }

    /** For networks that drop multicast: goes through the whole subnet. */
    fun scan(localIp: String, onFound: (Speaker) -> Unit): List<Speaker> {
        val base = localIp.substringBeforeLast('.')
        val found = Collections.synchronizedList(mutableListOf<Speaker>())
        val pool = Executors.newFixedThreadPool(48)
        val probes = listOf<Pair<Int, (String) -> Speaker?>>(
            1400 to { ip -> Ssdp.probe(ip) },
            CastChannel.DEFAULT_PORT to { ip -> probeCast(ip) },
            de.tomkainz.sonoslive.airplay.AirPlayDriver.DEFAULT_PORT to { ip -> probeAirPlay(ip) }
        )
        try {
            val tasks = (1..254).map { "$base.$it" }.filter { it != localIp }.flatMap { ip ->
                probes.map { (port, probe) ->
                    pool.submit {
                        if (open(ip, port)) {
                            probe(ip)?.let {
                                found += it
                                onFound(it)
                            }
                        }
                    }
                }
            }
            tasks.forEach { runCatching { it.get(8, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return merge(found.toList())
    }

    private fun open(ip: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 300) }
        true
    } catch (e: Exception) {
        false
    }
}
