package de.tomkainz.sonoslive

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Speaker(
    val room: String,
    val ip: String,
    val udn: String,
    val model: String
)

/**
 * Sonos-Geraetesuche per SSDP: M-SEARCH an 239.255.255.250:1900, Antworten
 * einsammeln, Beschreibung jedes Geraets holen. Ab Android 17 faellt das unter
 * ACCESS_LOCAL_NETWORK; ohne die Berechtigung liefert der Socket EPERM.
 */
object Ssdp {

    private const val ADDR = "239.255.255.250"
    private const val PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    fun discover(context: Context, timeoutMs: Int = 3500): List<Speaker> {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sonos-live-ssdp")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val found = LinkedHashMap<String, Speaker>()
        try {
            val query = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $ADDR:$PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: $SEARCH_TARGET\r\n" +
                    "\r\n"
                ).toByteArray()

            DatagramSocket().use { socket ->
                socket.reuseAddress = true
                socket.soTimeout = 500
                val target = InetAddress.getByName(ADDR)

                // Mehrfach senden: UDP-Multicast geht im WLAN gern mal verloren.
                repeat(3) {
                    runCatching { socket.send(DatagramPacket(query, query.size, target, PORT)) }
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
                    if (found.containsKey(ip)) continue
                    val location = Regex("(?im)^LOCATION:\\s*(\\S+)")
                        .find(text)?.groupValues?.get(1) ?: continue
                    describe(location, ip)?.let { found[ip] = it }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        return visibleOnly(found.values.toList()).sortedBy { it.room.lowercase() }
    }

    /**
     * Rueckfall, wenn SSDP (Multicast) nichts bringt - etwa weil der Router
     * Multicast filtert: das eigene /24-Subnetz absuchen. Erst ein schneller
     * TCP-Test auf Port 1400, nur wer antwortet, wird vollstaendig abgefragt.
     * Gefundene Speaker werden sofort ueber [onFound] gemeldet.
     */
    fun scanSubnet(localIp: String, onFound: ((Speaker) -> Unit)? = null): List<Speaker> {
        val base = localIp.substringBeforeLast('.')
        val found = Collections.synchronizedList(mutableListOf<Speaker>())
        val pool = Executors.newFixedThreadPool(48)
        try {
            val tasks = (1..254).map { i ->
                pool.submit {
                    val ip = "$base.$i"
                    if (ip == localIp) return@submit
                    val reachable = try {
                        Socket().use { it.connect(InetSocketAddress(ip, 1400), 300); true }
                    } catch (e: Exception) {
                        false
                    }
                    if (reachable) probe(ip)?.let { found.add(it); onFound?.invoke(it) }
                }
            }
            tasks.forEach { runCatching { it.get(6, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return visibleOnly(found.distinctBy { it.udn }).sortedBy { it.room.lowercase() }
    }

    /** Fuer manuell eingetragene Adressen (und den Emulator, der kein Multicast kann). */
    fun probe(ip: String): Speaker? =
        describe("http://$ip:1400/xml/device_description.xml", ip)

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
     * Die zweite Box eines Stereopaars oder ein Sub antworten auch auf SSDP,
     * lassen sich aber nicht eigenstaendig bespielen. Die Topologie markiert sie
     * mit Invisible="1".
     */
    fun visibleOnly(list: List<Speaker>): List<Speaker> {
        val first = list.firstOrNull() ?: return list
        val topology = runCatching { Sonos(first).zoneGroupState() }.getOrNull() ?: return list
        val hidden = Regex("<ZoneGroupMember[^>]*UUID=\"([^\"]+)\"[^>]*Invisible=\"1\"")
            .findAll(topology).map { it.groupValues[1] }.toSet()
        return list.filterNot { it.udn in hidden }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
}

/**
 * Steuerung eines Speakers ueber UPnP/SOAP auf Port 1400. Funktioniert nur,
 * solange in der Sonos-App unter Konto > Datenschutz und Sicherheit >
 * Verbindungssicherheit "UPnP" eingeschaltet ist (Standard: an).
 */
class Sonos(val speaker: Speaker) {

    companion object {
        private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val ZGT = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        private const val AVT_PATH = "/MediaRenderer/AVTransport/Control"
        private const val RC_PATH = "/MediaRenderer/RenderingControl/Control"
        private const val ZGT_PATH = "/ZoneGroupTopology/Control"

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
    }

    // ---- Wiedergabe -------------------------------------------------------

    /**
     * Zeigt den Sonos auf unseren Live-Stream. Leere Metadaten wie bei der
     * Linux-Bridge: damit spielt der Sonos einen WAV-Stream unter einer
     * normalen http-URI zuverlaessig ab.
     */
    fun playStream(url: String) {
        avt(
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${esc(url)}</CurrentURI>" +
                "<CurrentURIMetaData></CurrentURIMetaData>"
        )
        play()
    }

    fun play() = avt("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>").let { }
    fun pause() = avt("Pause", "<InstanceID>0</InstanceID>").let { }
    fun stop() = avt("Stop", "<InstanceID>0</InstanceID>").let { }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING */
    fun transportState(): String =
        value(avt("GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState") ?: ""

    /** Die URI, die der Speaker gerade spielt (leer, wenn nichts gesetzt ist). */
    fun currentUri(): String =
        unesc(value(avt("GetMediaInfo", "<InstanceID>0</InstanceID>"), "CurrentURI") ?: "")

    // ---- Gruppen ----------------------------------------------------------

    fun zoneGroupState(): String =
        unesc(value(soap(ZGT, ZGT_PATH, "GetZoneGroupState", ""), "ZoneGroupState") ?: "")

    /**
     * In einer Gruppe nimmt nur der Koordinator eine Quelle an. Liefert die
     * IP des Koordinators der Gruppe, zu der dieser Speaker gehoert.
     */
    fun coordinatorIp(): String {
        val state = runCatching { zoneGroupState() }.getOrNull() ?: return speaker.ip
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

    // ---- Lautstaerke ------------------------------------------------------

    fun volume(): Int =
        value(rc("GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")
            ?.toIntOrNull() ?: 0

    fun setVolume(v: Int) = rc(
        "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
            "<DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>"
    ).let { }

    // ---- SOAP -------------------------------------------------------------

    private fun avt(action: String, body: String) = soap(AVT, AVT_PATH, action, body)
    private fun rc(action: String, body: String) = soap(RC, RC_PATH, action, body)

    private fun soap(service: String, path: String, action: String, body: String): String {
        val envelope =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$service\">$body</u:$action></s:Body></s:Envelope>"

        val conn = (URL("http://${speaker.ip}:1400$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$service#$action\"")
            connectTimeout = 4000
            readTimeout = 8000
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("$action: HTTP $code ${errorText(text)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun value(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun errorText(xml: String): String =
        value(xml, "errorDescription") ?: value(xml, "errorCode")?.let { "(UPnP-Fehler $it)" } ?: ""
}
