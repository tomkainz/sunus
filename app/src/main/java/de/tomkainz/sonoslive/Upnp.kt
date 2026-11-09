package de.tomkainz.sonoslive

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** The parts of a UPnP device description that matter here. */
data class UpnpDevice(
    val udn: String,
    val name: String,
    val model: String,
    val avTransport: String,
    val rendering: String?,
    val renderingScpd: String?,
    val connectionManager: String?,
    val icon: String?
)

object UpnpDescription {

    private val SERVICE = Regex("<(?:\\w+:)?service(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?service>", RegexOption.DOT_MATCHES_ALL)

    /** Sonos also answers as a renderer; it has its own, better way. */
    fun isSonos(xml: String) = xml.contains("ZonePlayer") || tag(xml, "manufacturer")?.contains("Sonos") == true

    /** Null if it is not a media renderer one can send a stream to. */
    fun parse(xml: String, location: String): UpnpDevice? {
        if (!xml.contains("MediaRenderer")) return null
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        // Device blocks can nest. The details of the renderer come after its deviceType.
        val rest = xml.substring(xml.indexOf("MediaRenderer"))
        // Usually after it, but the order of a device's elements is free.
        val services = SERVICE.findAll(rest).map { it.groupValues[1] }.toList() +
            SERVICE.findAll(xml).map { it.groupValues[1] }.toList()
        fun service(kind: String) = services.firstOrNull { tag(it, "serviceType")?.contains(":$kind:") == true }
        fun url(path: String?) = path?.let { runCatching { URL(URL(base), Sonos.unesc(it)).toString() }.getOrNull() }
        val transport = url(service("AVTransport")?.let { tag(it, "controlURL") }) ?: return null
        val rendering = service("RenderingControl")
        return UpnpDevice(
            udn = (tag(rest, "UDN") ?: tag(xml, "UDN") ?: return null).removePrefix("uuid:"),
            name = Sonos.unesc(tag(rest, "friendlyName") ?: tag(xml, "friendlyName") ?: "UPnP"),
            model = Sonos.unesc(tag(rest, "modelName") ?: tag(xml, "modelName") ?: "UPnP"),
            avTransport = transport,
            rendering = url(rendering?.let { tag(it, "controlURL") }),
            renderingScpd = url(rendering?.let { tag(it, "SCPDURL") }),
            connectionManager = url(service("ConnectionManager")?.let { tag(it, "controlURL") }),
            icon = url(tag(xml, "iconList")?.let { tag(it, "url") })
        )
    }

    /** Fetches a description; null if there is none or it is not a renderer. */
    fun fetch(location: String): UpnpDevice? = runCatching {
        val xml = get(location)
        if (isSonos(xml)) null else parse(xml, location)
    }.getOrNull()

    fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun speaker(device: UpnpDevice, location: String) = Speaker(
        room = device.name,
        ip = URL(location).host,
        udn = device.udn,
        model = device.model,
        protocol = SpeakerProtocol.UPNP,
        address = location
    )

    fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
}

/** SOAP for any UPnP service. */
object Soap {

    fun call(url: String, service: String, action: String, body: String, readTimeoutMs: Int = 8000): String {
        val envelope =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:$action xmlns:u=\"$service\">$body</u:$action></s:Body></s:Envelope>"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$service#$action\"")
            connectTimeout = 4000
            readTimeout = readTimeoutMs
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("$action: HTTP $code ${errorText(text)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorText(xml: String): String =
        Sonos.xmlValue(xml, "errorDescription") ?: Sonos.xmlValue(xml, "errorCode")?.let { "(UPnP error $it)" } ?: ""
}

/** One way of offering the stream. Renderers disagree, so there are several to try. */
data class UpnpProfile(
    val name: String,
    val mime: String,
    val framing: StreamFraming,
    val metadata: Metadata,
    val raw: Boolean = false
) {
    enum class Metadata { NONE, PLAIN, DLNA }

    val path get() = if (raw) LiveStreamServer.PATH_L16 else LiveStreamServer.PATH

    fun protocolInfo(): String = when {
        raw -> "http-get:*:$mime:DLNA.ORG_PN=LPCM"
        metadata == Metadata.DLNA ->
            "http-get:*:$mime:DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        else -> "http-get:*:$mime:*"
    }

    fun didl(url: String): String {
        if (metadata == Metadata.NONE) return ""
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>Sunus</dc:title>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"${Sonos.esc(protocolInfo())}\">${Sonos.esc(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    companion object {
        private val WAV = listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")

        /**
         * What to try, most likely first. A huge Content-Length is what most other
         * projects start with for WAV: some renderers hang up without one.
         */
        fun ladder(sinkProtocols: String): List<UpnpProfile> {
            val offered = sinkProtocols.split(',').map { it.trim().split(':').getOrElse(2) { "" } }
            val wav = offered.firstNotNullOfOrNull { o -> WAV.firstOrNull { o.startsWith(it, ignoreCase = true) } } ?: WAV[0]
            val l16 = offered.firstOrNull { it.startsWith("audio/L16", ignoreCase = true) }
            return buildList {
                add(UpnpProfile("wav-length", wav, StreamFraming.LENGTH, Metadata.PLAIN))
                add(UpnpProfile("wav-length-dlna", wav, StreamFraming.LENGTH, Metadata.DLNA))
                add(UpnpProfile("wav-open", wav, StreamFraming.NONE, Metadata.PLAIN))
                add(UpnpProfile("wav-open-bare", wav, StreamFraming.NONE, Metadata.NONE))
                add(UpnpProfile("wav-chunked", wav, StreamFraming.CHUNKED, Metadata.PLAIN))
                if (l16 == null || l16.contains("48000") || !l16.contains("rate")) {
                    add(UpnpProfile("l16", "audio/L16;rate=48000;channels=2", StreamFraming.NONE, Metadata.PLAIN, raw = true))
                }
            }
        }
    }
}

/** Plain UPnP actions on a renderer. */
class UpnpRenderer(private val device: UpnpDevice) {

    fun sinkProtocols(): String = runCatching {
        device.connectionManager?.let {
            UpnpDescription.tag(Soap.call(it, CONNECTION_MANAGER, "GetProtocolInfo", ""), "Sink")
        }
    }.getOrNull().orEmpty().let(Sonos::unesc)

    fun setUri(url: String, metadata: String) = avt(
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID><CurrentURI>${Sonos.esc(url)}</CurrentURI>" +
            "<CurrentURIMetaData>${Sonos.esc(metadata)}</CurrentURIMetaData>"
    ).let { }

    fun play() {
        try {
            Soap.call(device.avTransport, AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>", readTimeoutMs = 20_000)
        } catch (e: SocketTimeoutException) {
            // Like Sonos, some answer Play only once they have buffered the stream.
            if (runCatching { transportState() }.getOrDefault("") !in setOf("PLAYING", "TRANSITIONING")) throw e
        }
    }

    fun stop() = avt("Stop", "<InstanceID>0</InstanceID>").let { }

    fun transportState(): String =
        UpnpDescription.tag(avt("GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState") ?: ""

    fun currentUri(): String =
        Sonos.unesc(UpnpDescription.tag(avt("GetMediaInfo", "<InstanceID>0</InstanceID>"), "CurrentURI") ?: "")

    private fun avt(action: String, body: String) = Soap.call(device.avTransport, AVT, action, body)

    companion object {
        const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }
}

class UpnpControl(private val speaker: Speaker) : SpeakerControl {

    private val device by lazy { UpnpDescription.fetch(speaker.address) ?: throw IOException("No description") }

    /** Renderers state their own scale, and not all of them end at 100. */
    private val maximum: Int by lazy {
        runCatching {
            val scpd = UpnpDescription.get(device.renderingScpd!!)
            Regex("<(?:\\w+:)?name>Volume</(?:\\w+:)?name>.*?<(?:\\w+:)?maximum>(\\d+)</", RegexOption.DOT_MATCHES_ALL)
                .find(scpd)?.groupValues?.get(1)?.toInt()
        }.getOrNull()?.takeIf { it > 0 } ?: 100
    }

    private fun rc(action: String, body: String): String =
        Soap.call(device.rendering ?: throw IOException("No volume control"), UpnpRenderer.RC, action, body)

    override fun volume(): Int? {
        if (device.rendering == null) return null
        val raw = UpnpDescription.tag(
            rc("GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume"
        )?.toIntOrNull() ?: return null
        return (raw * 100 / maximum).coerceIn(0, 100)
    }

    override fun setVolume(v: Int) {
        val raw = v.coerceIn(0, 100) * maximum / 100
        rc("SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$raw</DesiredVolume>")
    }

    override fun muted(): Boolean = UpnpDescription.tag(
        rc("GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentMute"
    ).let { it == "1" || it == "true" }

    override fun setMuted(on: Boolean) {
        rc("SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (on) 1 else 0}</DesiredMute>")
    }
}

/**
 * Any other UPnP renderer. Which way of offering the stream it takes is only known by
 * trying: the driver walks [UpnpProfile.ladder] until one keeps playing, and remembers it.
 */
class UpnpDriver(private val speaker: Speaker, private val remembered: Remembered) : SpeakerDriver {

    private var renderer: UpnpRenderer? = null
    private var ladder = emptyList<UpnpProfile>()
    private var index = 0
    private var startedAt = 0L
    private var requestsAtStart = 0
    private var stoppedSince = 0L
    private var proven = false
    private val key get() = "upnp.${speaker.udn}"

    override fun start(stream: StreamEndpoint) {
        val device = UpnpDescription.fetch(speaker.address) ?: throw IOException("No renderer at ${speaker.address}")
        val r = UpnpRenderer(device)
        renderer = r
        if (ladder.isEmpty()) {
            val sink = r.sinkProtocols()
            AppState.log("UPnP ${speaker.ip}: accepts ${sink.take(300).ifEmpty { "(does not say)" }}")
            // What worked last time goes first, and the others stay in line behind it.
            ladder = UpnpProfile.ladder(sink).sortedByDescending { it.name == remembered.get(key) }
            index = 0
        }
        var last: Exception? = null
        while (index < ladder.size) {
            try {
                play(r, stream, ladder[index])
                return
            } catch (e: Exception) {
                AppState.log("UPnP ${speaker.ip}: ${ladder[index].name} refused: ${e.message}")
                last = e
                // A speaker that cannot be reached will not take the next profile either.
                if (e is java.net.ConnectException || e is java.net.NoRouteToHostException) throw e
                index++
            }
        }
        index = 0
        throw last ?: IOException("No way of playing found")
    }

    private fun play(r: UpnpRenderer, stream: StreamEndpoint, profile: UpnpProfile) {
        AppState.log("UPnP ${speaker.ip}: trying ${profile.name}")
        stream.server.framing = profile.framing
        stream.server.contentLength = LiveStreamServer.WAV_LIMIT
        stream.server.contentType = profile.mime
        stream.server.dlna = true
        val url = stream.baseUrl + profile.path
        requestsAtStart = stream.server.streamRequests.get()
        startedAt = now()
        stoppedSince = 0
        proven = false
        // Renderers vary in what they take while busy, and some need a moment between commands.
        runCatching { r.stop() }
        Thread.sleep(200)
        r.setUri(url, profile.didl(url))
        Thread.sleep(200)
        r.play()
    }

    override fun check(stream: StreamEndpoint): SpeakerCheck {
        val r = renderer ?: return SpeakerCheck.Fine("")
        val state = r.transportState()
        // Not every renderer offers this, and without it the state still tells a lot.
        val current = runCatching { r.currentUri() }.getOrDefault("")
        val age = now() - startedAt
        val fetched = stream.server.streamRequests.get() > requestsAtStart
        // Some renderers report the address in a form of their own, so a different address
        // alone proves little. Once it has fetched the stream and stopped, it has left.
        if (fetched && current.isNotEmpty() && !current.startsWith(stream.baseUrl) && stream.server.streamCount() == 0) {
            return SpeakerCheck.Left
        }
        return when {
            state == "PLAYING" -> {
                stoppedSince = 0
                when {
                    !proven && fetched && stream.server.streamCount() > 0 && age >= PROVEN_AFTER_MS -> {
                        proven = true
                        remembered.set(key, ladder[index].name)
                        AppState.log("UPnP ${speaker.ip}: ${ladder[index].name} works")
                        SpeakerCheck.Fine(state)
                    }
                    // Says it plays, but never came for the stream.
                    !fetched && age >= NO_FETCH_MS -> next("playing without fetching the stream")
                    else -> SpeakerCheck.Fine(state)
                }
            }
            state == "TRANSITIONING" ->
                if (!fetched && age >= NO_FETCH_MS) next("still loading, no fetch") else SpeakerCheck.Fine(state)
            state == "STOPPED" || state == "NO_MEDIA_PRESENT" -> {
                if (stoppedSince == 0L) stoppedSince = now()
                // A renderer can report STOPPED for a moment on its way to playing.
                when {
                    now() - stoppedSince < STOPPED_GRACE_MS -> SpeakerCheck.Fine(state)
                    proven -> SpeakerCheck.Restart("stopped after playing")
                    else -> next("stopped ($state)")
                }
            }
            // Paused on the speaker: the user's call.
            else -> SpeakerCheck.Fine(state)
        }
    }

    private fun next(reason: String): SpeakerCheck {
        AppState.log("UPnP ${speaker.ip}: ${ladder.getOrNull(index)?.name}: $reason")
        if (index + 1 >= ladder.size) return SpeakerCheck.Failed("No way of playing worked. Last: $reason")
        index++
        return SpeakerCheck.Restart(reason)
    }

    override fun stop() {
        renderer?.let { runCatching { it.stop() } }
        renderer = null
    }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        const val PROVEN_AFTER_MS = 15_000L
        const val NO_FETCH_MS = 15_000L
        const val STOPPED_GRACE_MS = 5_000L
    }
}
