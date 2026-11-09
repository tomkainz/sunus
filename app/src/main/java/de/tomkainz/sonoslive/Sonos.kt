package de.tomkainz.sonoslive

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The speaker's sound settings. Night mode and speech enhancement only exist
 * on home theater speakers (Arc, Beam, Ray); elsewhere they are null.
 */
data class Eq(
    val bass: Int,
    val treble: Int,
    val loudness: Boolean,
    val night: Boolean? = null,
    val speech: Boolean? = null
)

/** Which picture a speaker gets. */
enum class SpeakerKind {
    BAR, SUB, BOX, TOWER, HOMEPOD, HOMEPOD_MINI;

    companion object {
        private val BARS = setOf("arc", "beam", "ray", "playbar", "playbase")
        private val BOXES = setOf("amp", "port", "connect", "boost", "bridge")

        fun of(model: String): SpeakerKind {
            val words = model.lowercase().split(Regex("[^a-z0-9]+"))
            return when {
                "homepod" in words -> if ("mini" in words) HOMEPOD_MINI else HOMEPOD
                words.any { it in BARS } -> BAR
                "sub" in words -> SUB
                words.any { it in BOXES } -> BOX
                else -> TOWER
            }
        }
    }
}

/** How a speaker gets the stream. The order is the preference when a device offers several. */
enum class SpeakerProtocol(val label: String?, val alpha: Boolean = false) {
    SONOS(null), UPNP("UPnP", alpha = true), CAST("Cast", alpha = true), AIRPLAY("AirPlay", alpha = true)
}

data class Speaker(
    val room: String,
    val ip: String,
    val udn: String,
    val model: String,
    val protocol: SpeakerProtocol = SpeakerProtocol.SONOS,
    /** UPnP: the description's URL. Cast and AirPlay: the port. */
    val address: String = "",
    /** AirPlay: what the speaker announces, see AirPlayFeatures. */
    val details: String = "",
    /** The same device's other ways in, for when the first will not play. */
    val alternatives: List<Speaker> = emptyList()
)

/**
 * Sonos device discovery via SSDP: M-SEARCH to 239.255.255.250:1900, collect
 * the replies, fetch each device's description. From Android 17 on this falls
 * under ACCESS_LOCAL_NETWORK; without that permission the socket returns EPERM.
 */
object Ssdp {

    private const val ADDR = "239.255.255.250"
    private const val PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun locationOf(answer: String): String? =
        Regex("(?im)^LOCATION:\\s*(\\S+)").find(answer)?.groupValues?.get(1)

    fun discover(context: Context, timeoutMs: Int = 3500): List<Speaker> {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sonos-live-ssdp")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        try {
            return search(timeoutMs)
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** The search itself. Without a [Context], so it can also run outside a phone. */
    fun search(timeoutMs: Int = 3500): List<Speaker> {
        val found = java.util.concurrent.ConcurrentHashMap<String, Speaker>()
        val asked = HashSet<String>()
        run {
            val queries = listOf(SEARCH_TARGET, MEDIA_RENDERER).map { target ->
                (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $ADDR:$PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 1\r\n" +
                        "ST: $target\r\n" +
                        "\r\n"
                    ).toByteArray()
            }
            val pool = Executors.newFixedThreadPool(6)

            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 500
                val target = InetAddress.getByName(ADDR)

                // Send several times: UDP multicast often gets lost on Wi-Fi.
                repeat(3) {
                    queries.forEach { query ->
                        runCatching { socket.send(DatagramPacket(query, query.size, target, PORT)) }
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length)
                    val ip = packet.address?.hostAddress ?: continue
                    val location = locationOf(text) ?: continue
                    // A device answers both searches, with the same place to look.
                    if (!asked.add(location)) continue
                    // One slow device must not hold up the others.
                    pool.execute { describeAny(location, ip)?.let { found[location] = it } }
                }
            }
            pool.shutdown()
            pool.awaitTermination(4, TimeUnit.SECONDS)
        }
        return visibleOnly(found.values.toList()).sortedBy { it.room.lowercase() }
    }

    /** For manually entered addresses (and the emulator, which cannot do multicast). */
    fun probe(ip: String): Speaker? =
        describe("http://$ip:1400/xml/device_description.xml", ip)

    /** A Sonos, or any other renderer. */
    private fun describeAny(location: String, ip: String): Speaker? = runCatching {
        val xml = UpnpDescription.get(location)
        if (UpnpDescription.isSonos(xml)) describe(location, ip)
        else UpnpDescription.parse(xml, location)?.let { UpnpDescription.speaker(it, location) }
    }.getOrNull()

    private fun describe(location: String, ip: String): Speaker? = try {
        val conn = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val udn = (tag(xml, "UDN") ?: "").removePrefix("uuid:")
        if (udn.isBlank()) null else Speaker(
            room = tag(xml, "roomName") ?: tag(xml, "friendlyName") ?: ip,
            ip = ip,
            udn = udn,
            model = tag(xml, "modelName") ?: "Sonos"
        )
    } catch (e: Exception) {
        null
    }

    /**
     * The second speaker of a stereo pair or a Sub also answer SSDP, but
     * cannot play on their own. The topology marks them with Invisible="1".
     */
    fun visibleOnly(list: List<Speaker>): List<Speaker> {
        val first = list.firstOrNull { it.protocol == SpeakerProtocol.SONOS } ?: return list
        val topology = runCatching { Sonos(first).zoneGroupState() }.getOrNull() ?: return list
        val hidden = Sonos.hiddenUdns(topology)
        return list.filterNot { it.udn in hidden }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
}

/**
 * Controls a speaker over UPnP/SOAP on port 1400. Only works as long as "UPnP"
 * is turned on in the Sonos app under Account > Privacy & Security >
 * Connection Security (default: on).
 */
class Sonos(val speaker: Speaker) : SpeakerControl {

    companion object {
        private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val ZGT = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        private const val AVT_PATH = "/MediaRenderer/AVTransport/Control"
        private const val RC_PATH = "/MediaRenderer/RenderingControl/Control"
        private const val ZGT_PATH = "/ZoneGroupTopology/Control"
        private const val NIGHT_MODE = "NightMode"
        private const val DIALOG_LEVEL = "DialogLevel"

        fun esc(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun unesc(s: String): String = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        /** Speakers that cannot play on their own (second of a stereo pair, Sub). */
        fun hiddenUdns(topology: String): Set<String> =
            Regex("<ZoneGroupMember[^>]*UUID=\"([^\"]+)\"[^>]*Invisible=\"1\"")
                .findAll(topology).map { it.groupValues[1] }.toSet()

        /** The IP of the coordinator of [speaker]'s group, read from the topology. */
        fun coordinatorIp(state: String, speaker: Speaker): String {
            val groups = Regex("<ZoneGroup [^>]*Coordinator=\"([^\"]+)\"[^>]*>(.*?)</ZoneGroup>",
                RegexOption.DOT_MATCHES_ALL).findAll(state)
            for (group in groups) {
                val members = group.groupValues[2]
                if (!members.contains("UUID=\"${speaker.udn}\"")) continue
                val coordinator = group.groupValues[1]
                if (coordinator == speaker.udn) return speaker.ip
                val location = Regex("<ZoneGroupMember[^>]*UUID=\"$coordinator\"[^>]*Location=\"http://([^:/\"]+)")
                    .find(members)?.groupValues?.get(1)
                return location ?: speaker.ip
            }
            return speaker.ip
        }

        fun xmlValue(xml: String, tag: String): String? =
            Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()
    }

    // ---- Playback ---------------------------------------------------------

    /**
     * Points the Sonos at our live stream. Empty metadata as in the Linux
     * bridge: that way the Sonos reliably plays a WAV stream from a plain
     * http URI.
     */
    fun playStream(url: String) {
        avt(
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${esc(url)}</CurrentURI>" +
                "<CurrentURIMetaData></CurrentURIMetaData>"
        )
        try {
            play()
        } catch (e: SocketTimeoutException) {
            // For a stream, the Sonos only answers Play once it has opened the
            // URL and buffered; that can take longer than the timeout. If it
            // is playing by now, that is fine.
            val state = runCatching { transportState() }.getOrDefault("")
            if (state != "PLAYING" && state != "TRANSITIONING") throw e
        }
    }

    fun play() = soap(AVT, AVT_PATH, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>", readTimeoutMs = 20_000)
        .let { }
    fun pause() = avt("Pause", "<InstanceID>0</InstanceID>").let { }
    fun stop() = avt("Stop", "<InstanceID>0</InstanceID>").let { }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING */
    fun transportState(): String =
        value(avt("GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState") ?: ""

    /** Why the speaker is in that state, if it says: "OK" or an error of its own. */
    fun transportStatus(): String =
        value(avt("GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportStatus") ?: ""

    /** The URI the speaker is currently playing (empty if none is set). */
    fun currentUri(): String =
        unesc(value(avt("GetMediaInfo", "<InstanceID>0</InstanceID>"), "CurrentURI") ?: "")

    // ---- Groups -----------------------------------------------------------

    fun zoneGroupState(): String =
        unesc(value(soap(ZGT, ZGT_PATH, "GetZoneGroupState", ""), "ZoneGroupState") ?: "")

    /**
     * In a group, only the coordinator accepts a source. Returns the IP of the
     * coordinator of the group this speaker belongs to.
     */
    fun coordinatorIp(): String {
        val state = runCatching { zoneGroupState() }.getOrNull() ?: return speaker.ip
        return coordinatorIp(state, speaker)
    }

    // ---- Volume -----------------------------------------------------------

    override fun volume(): Int =
        value(rc("GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")
            ?.toIntOrNull() ?: 0

    override fun setVolume(v: Int) = rc(
        "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
            "<DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>"
    ).let { }

    override fun muted(): Boolean =
        value(rc("GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentMute") == "1"

    override fun setMuted(on: Boolean) = rc(
        "SetMute",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
            "<DesiredMute>${if (on) 1 else 0}</DesiredMute>"
    ).let { }

    // ---- Sound (EQ) -------------------------------------------------------

    /** Bass and treble from -10 to +10, as in the Sonos app. */
    fun eq(): Eq = Eq(
        bass = value(rc("GetBass", "<InstanceID>0</InstanceID>"), "CurrentBass")?.toIntOrNull() ?: 0,
        treble = value(rc("GetTreble", "<InstanceID>0</InstanceID>"), "CurrentTreble")?.toIntOrNull() ?: 0,
        loudness = value(
            rc("GetLoudness", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentLoudness"
        ) == "1",
        night = homeTheater(NIGHT_MODE),
        speech = homeTheater(DIALOG_LEVEL)
    )

    /** Other speakers answer GetEQ with UPnP error 402 (checked on a Play:1). */
    private fun homeTheater(type: String): Boolean? = runCatching {
        value(rc("GetEQ", "<InstanceID>0</InstanceID><EQType>$type</EQType>"), "CurrentValue")?.let { it == "1" }
    }.getOrNull()

    fun setNightMode(on: Boolean) = setHomeTheater(NIGHT_MODE, on)
    fun setSpeech(on: Boolean) = setHomeTheater(DIALOG_LEVEL, on)

    private fun setHomeTheater(type: String, on: Boolean) = rc(
        "SetEQ",
        "<InstanceID>0</InstanceID><EQType>$type</EQType><DesiredValue>${if (on) 1 else 0}</DesiredValue>"
    ).let { }

    fun setBass(v: Int) =
        rc("SetBass", "<InstanceID>0</InstanceID><DesiredBass>${v.coerceIn(-10, 10)}</DesiredBass>").let { }

    fun setTreble(v: Int) =
        rc("SetTreble", "<InstanceID>0</InstanceID><DesiredTreble>${v.coerceIn(-10, 10)}</DesiredTreble>").let { }

    fun setLoudness(on: Boolean) = rc(
        "SetLoudness",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
            "<DesiredLoudness>${if (on) 1 else 0}</DesiredLoudness>"
    ).let { }

    // ---- SOAP -------------------------------------------------------------

    private fun avt(action: String, body: String) = soap(AVT, AVT_PATH, action, body)
    private fun rc(action: String, body: String) = soap(RC, RC_PATH, action, body)

    private fun soap(
        service: String,
        path: String,
        action: String,
        body: String,
        readTimeoutMs: Int = 8000
    ): String = Soap.call("http://${speaker.ip}:1400$path", service, action, body, readTimeoutMs)

    private fun value(xml: String, tag: String): String? = xmlValue(xml, tag)
}
