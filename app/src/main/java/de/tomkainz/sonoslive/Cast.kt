package de.tomkainz.sonoslive

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** One message of the Cast protocol: a small protobuf with a JSON payload. */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, 1 shl 3)
        varint(out, 0) // protocol version CASTV2_1_0
        string(out, 2, source)
        string(out, 3, destination)
        string(out, 4, namespace)
        varint(out, 5 shl 3)
        varint(out, 0) // payload type STRING
        string(out, 6, payload)
        return out.toByteArray()
    }

    companion object {
        private fun string(out: ByteArrayOutputStream, field: Int, value: String) {
            val bytes = value.toByteArray()
            varint(out, (field shl 3) or 2)
            varint(out, bytes.size)
            out.write(bytes)
        }

        private fun varint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while (v >= 0x80) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
        }

        fun decode(data: ByteArray): CastMessage {
            var at = 0
            var source = ""
            var destination = ""
            var namespace = ""
            var payload = ""
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = data[at++].toInt()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (at < data.size) {
                val key = varint().toInt()
                when (key and 7) {
                    0 -> varint()
                    2 -> {
                        val length = varint().toInt()
                        val text = String(data, at, length)
                        at += length
                        when (key ushr 3) {
                            2 -> source = text
                            3 -> destination = text
                            4 -> namespace = text
                            6 -> payload = text
                        }
                    }
                    else -> throw IOException("Unknown wire type in a Cast message")
                }
            }
            return CastMessage(source, destination, namespace, payload)
        }
    }
}

/**
 * The connection to a Cast device: TLS on port 8009 with a certificate the device made
 * itself, so there is nothing to check it against. It only carries the stream's address
 * and the volume, on the local network.
 */
/** A request that nothing answered, which on Cast does not have to mean it failed. */
class NoAnswer(what: Any?) : IOException("No answer from the Cast device to $what")

class CastChannel(
    private val host: String,
    private val port: Int,
    private val onMessage: (CastMessage, Map<String, Any?>) -> Unit = { _, _ -> }
) : Closeable {

    private val socket: SSLSocket
    private val pending = ConcurrentHashMap<Long, ArrayBlockingQueue<Map<String, Any?>>>()
    private val nextRequest = AtomicInteger(1)
    private val writeLock = Any()

    @Volatile
    private var open = true

    /** When the device last said anything. It answers the ping, so silence means it is gone. */
    @Volatile
    var lastHeard = System.nanoTime()
        private set

    val alive: Boolean
        get() = open && (System.nanoTime() - lastHeard) / 1_000_000 < DEAD_AFTER_MS

    init {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(TrustAnyDevice), SecureRandom())
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 4000)
        socket = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        socket.soTimeout = 0
        socket.tcpNoDelay = true
        socket.startHandshake()
        send(SENDER, RECEIVER, NS_CONNECTION, mapOf("type" to "CONNECT"))
        thread("cast-read") { readLoop() }
        thread("cast-ping") { pingLoop() }
    }

    fun send(source: String, destination: String, namespace: String, body: Map<String, Any?>) {
        val message = CastMessage(source, destination, namespace, MiniJson.write(body)).encode()
        val framed = ByteArray(4 + message.size)
        framed[0] = (message.size ushr 24).toByte()
        framed[1] = (message.size ushr 16).toByte()
        framed[2] = (message.size ushr 8).toByte()
        framed[3] = message.size.toByte()
        message.copyInto(framed, 4)
        synchronized(writeLock) {
            socket.outputStream.write(framed)
            socket.outputStream.flush()
        }
    }

    /** Sends a request and waits for the answer with the same requestId. */
    fun request(destination: String, namespace: String, body: Map<String, Any?>, timeoutMs: Long = 8000): Map<String, Any?> {
        val id = nextRequest.getAndIncrement().toLong()
        val answer = ArrayBlockingQueue<Map<String, Any?>>(1)
        pending[id] = answer
        try {
            send(SENDER, destination, namespace, body + ("requestId" to id))
            return answer.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw NoAnswer(body["type"])
        } finally {
            pending.remove(id)
        }
    }

    /** For what is answered by a status to everyone, or not at all. Never id 0, which belongs to the device. */
    fun post(destination: String, namespace: String, body: Map<String, Any?>) {
        send(SENDER, destination, namespace, body + ("requestId" to nextRequest.getAndIncrement().toLong()))
    }

    fun receiverStatus(): Map<String, Any?> = request(RECEIVER, NS_RECEIVER, mapOf("type" to "GET_STATUS"))

    private fun readLoop() {
        try {
            val input = DataInputStream(socket.inputStream)
            while (open) {
                val length = input.readInt()
                if (length !in 0..MAX_MESSAGE) throw IOException("Cast message of $length bytes")
                val data = ByteArray(length)
                input.readFully(data)
                lastHeard = System.nanoTime()
                val message = CastMessage.decode(data)
                val body = runCatching { MiniJson.parse(message.payload) as? Map<*, *> }.getOrNull()
                    ?.entries?.associate { it.key.toString() to it.value } ?: continue
                if (message.namespace == NS_HEARTBEAT) {
                    if (body["type"] == "PING") send(SENDER, message.source, NS_HEARTBEAT, mapOf("type" to "PONG"))
                    continue
                }
                (body["requestId"] as? Number)?.toLong()?.let { pending[it]?.offer(body) }
                onMessage(message, body)
            }
        } catch (e: Exception) {
            if (open) AppState.log("Cast $host: connection lost: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            close()
        }
    }

    private fun pingLoop() {
        while (open) {
            runCatching { send(SENDER, RECEIVER, NS_HEARTBEAT, mapOf("type" to "PING")) }
            try {
                Thread.sleep(PING_EVERY_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    override fun close() {
        open = false
        runCatching { socket.close() }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body, name).apply {
            isDaemon = true
            start()
        }
    }

    /** The device's certificate is self-made; see the class comment. */
    @android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAnyDevice : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val SENDER = "sender-0"
        const val RECEIVER = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        const val DEFAULT_PORT = 8009
        private const val PING_EVERY_MS = 5000L
        private const val DEAD_AFTER_MS = 15_000L
        private const val MAX_MESSAGE = 1 shl 20
    }
}

private fun Any?.asMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

/** Reading the status messages; without a device, so it can be tested. */
object CastStatus {

    data class App(val appId: String, val sessionId: String, val transportId: String)

    fun apps(receiverStatus: Map<String, Any?>): List<App> {
        val status = receiverStatus["status"].asMap() ?: return emptyList()
        return (status["applications"] as? List<*>).orEmpty().mapNotNull { item ->
            val app = item.asMap() ?: return@mapNotNull null
            App(
                app["appId"] as? String ?: return@mapNotNull null,
                app["sessionId"] as? String ?: "",
                app["transportId"] as? String ?: ""
            )
        }
    }

    /** Level from 0 to 100, and whether it is muted. */
    fun volume(receiverStatus: Map<String, Any?>): Pair<Int, Boolean>? {
        val volume = receiverStatus["status"].asMap()?.get("volume").asMap() ?: return null
        val level = (volume["level"] as? Number)?.toDouble() ?: return null
        return Math.round(level * 100).toInt().coerceIn(0, 100) to (volume["muted"] == true)
    }

    data class Media(val state: String, val idleReason: String?)

    fun media(mediaStatus: Map<String, Any?>): Media? {
        val first = (mediaStatus["status"] as? List<*>)?.firstOrNull().asMap() ?: return null
        return Media(first["playerState"] as? String ?: return null, first["idleReason"] as? String)
    }

    /**
     * Which framing first. According to Music Assistant's experience (not documented by
     * Google, and not checked here): most devices stop after 5 to 10 minutes without a
     * length, while the 2026 speaker, Android TVs and groups stop after about 24 MiB with one.
     */
    fun framings(model: String): List<StreamFraming> {
        val m = model.lowercase()
        val noLength = listOf("google home speaker", "cast group", "shield", "android tv", "stereo pair")
        return if (noLength.any { it in m }) {
            listOf(StreamFraming.NONE, StreamFraming.CHUNKED, StreamFraming.LENGTH)
        } else {
            listOf(StreamFraming.LENGTH, StreamFraming.NONE, StreamFraming.CHUNKED)
        }
    }
}

/** Volume for the speaker in a short connection of its own; no app starts, so no chime. */
class CastControl(private val speaker: Speaker) : SpeakerControl {

    private val port get() = speaker.address.toIntOrNull() ?: CastChannel.DEFAULT_PORT

    private fun <T> connected(block: (CastChannel) -> T): T = CastChannel(speaker.ip, port).use(block)

    private fun volumeAndMute() = connected { CastStatus.volume(it.receiverStatus()) }

    override fun volume(): Int? = volumeAndMute()?.first
    override fun muted(): Boolean = volumeAndMute()?.second == true

    /** Waits a little for the answer, so the connection does not close under the message. */
    private fun set(volume: Map<String, Any?>) = connected {
        try {
            it.request(
                CastChannel.RECEIVER, CastChannel.NS_RECEIVER,
                mapOf("type" to "SET_VOLUME", "volume" to volume), timeoutMs = 2000
            )
        } catch (e: NoAnswer) {
            // It was sent, and some devices only tell everyone at once.
        }
    }.let { }

    override fun setVolume(v: Int) = set(mapOf("level" to v.coerceIn(0, 100) / 100.0))

    override fun setMuted(on: Boolean) = set(mapOf("muted" to on))
}

/**
 * Cast device: starts the Default Media Receiver, which needs no registration, and has
 * it load the stream. Its protocol is spoken directly because the Google SDK's terms
 * are at odds with what this app does.
 */
class CastDriver(private val speaker: Speaker, private val remembered: Remembered) : SpeakerDriver {

    private val port get() = speaker.address.toIntOrNull() ?: CastChannel.DEFAULT_PORT
    private val key get() = "cast.${speaker.udn}"

    @Volatile
    private var channel: CastChannel? = null
    @Volatile
    private var sessionId = ""
    private var transportId = ""
    @Volatile
    private var media: CastStatus.Media? = null
    @Volatile
    private var appGone = false
    @Volatile
    private var loadError: String? = null
    private var loadedAt = 0L
    private var lastAsked = 0L
    private var framings = emptyList<StreamFraming>()
    private var framingIndex = 0

    override fun start(stream: StreamEndpoint) {
        if (framings.isEmpty()) {
            framings = CastStatus.framings(speaker.model)
            framingIndex = framings.indexOfFirst { it.name == remembered.get(key) }.coerceAtLeast(0)
        }
        stream.server.framing = framings[framingIndex]
        stream.server.contentType = "audio/wav"
        stream.server.dlna = false
        // Long enough for half a day; a length of its own, unlike UPnP's WAV limit.
        stream.server.contentLength = TWELVE_HOURS
        val c = channel?.takeIf { it.alive } ?: CastChannel(speaker.ip, port, ::onStatus).also {
            channel?.close()
            channel = it
        }
        val app = launch(c)
        sessionId = app.sessionId
        transportId = app.transportId
        appGone = false
        media = null
        loadError = null
        c.send(CastChannel.SENDER, transportId, CastChannel.NS_CONNECTION, mapOf("type" to "CONNECT"))
        AppState.log("Cast ${speaker.ip}: load ${stream.url} (${framings[framingIndex]})")
        // Its answer is a status to everyone; refusals arrive in [onStatus] too.
        c.post(
            transportId, CastChannel.NS_MEDIA,
            mapOf(
                "type" to "LOAD",
                "autoplay" to true,
                "currentTime" to 0,
                "media" to mapOf(
                    "contentId" to stream.url,
                    "contentType" to "audio/wav",
                    "streamType" to "LIVE",
                    "metadata" to mapOf("metadataType" to 3, "title" to "Sunus")
                )
            )
        )
        loadedAt = now()
    }

    private fun launch(c: CastChannel): CastStatus.App {
        CastStatus.apps(c.receiverStatus()).firstOrNull { it.appId == CastChannel.DEFAULT_MEDIA_RECEIVER }?.let { return it }
        c.post(
            CastChannel.RECEIVER, CastChannel.NS_RECEIVER,
            mapOf("type" to "LAUNCH", "appId" to CastChannel.DEFAULT_MEDIA_RECEIVER)
        )
        // Devices may answer to everyone or to us, and starting takes a moment: just ask until it is there.
        repeat(20) {
            CastStatus.apps(c.receiverStatus()).firstOrNull {
                it.appId == CastChannel.DEFAULT_MEDIA_RECEIVER && it.transportId.isNotEmpty()
            }?.let { return it }
            Thread.sleep(500)
        }
        throw IOException("The Cast device did not start the media receiver")
    }

    private fun onStatus(message: CastMessage, body: Map<String, Any?>) {
        when (body["type"]) {
            "MEDIA_STATUS" -> CastStatus.media(body)?.let { media = it }
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "INVALID_PLAYER_STATE", "ERROR" ->
                loadError = "${body["type"]} ${body["reason"] ?: ""}".trim()
            "RECEIVER_STATUS" -> {
                // Another app on the device, or it was stopped: not this stream any more.
                if (sessionId.isNotEmpty() && CastStatus.apps(body).none { it.sessionId == sessionId }) appGone = true
            }
        }
    }

    override fun check(stream: StreamEndpoint): SpeakerCheck {
        val c = channel ?: return SpeakerCheck.Fine("")
        if (!c.alive) return SpeakerCheck.Restart("connection to the Cast device lost")
        if (appGone) return SpeakerCheck.Left
        // Status comes by itself, but asking keeps a device that went quiet from going unnoticed.
        if (now() - lastAsked >= STATUS_EVERY_MS && transportId.isNotEmpty()) {
            lastAsked = now()
            runCatching {
                c.post(transportId, CastChannel.NS_MEDIA, mapOf("type" to "GET_STATUS"))
            }
        }
        loadError?.let { return ended(it) }
        val waited = now() - loadedAt
        val status = media ?: return if (waited < LOAD_TIMEOUT_MS) SpeakerCheck.Fine("LOADING") else ended("no answer to LOAD")
        return when (status.state) {
            "IDLE" -> when (status.idleReason) {
                // Someone else did this on purpose.
                "CANCELLED", "INTERRUPTED" -> SpeakerCheck.Left
                // Normal for a moment, before it starts to buffer.
                null -> if (waited < LOAD_TIMEOUT_MS) SpeakerCheck.Fine("IDLE") else ended("idle since LOAD")
                else -> ended(status.idleReason)
            }
            "PLAYING" -> {
                if (now() - loadedAt >= PROVEN_AFTER_MS) remembered.set(key, framings[framingIndex].name)
                SpeakerCheck.Fine("PLAYING")
            }
            else -> SpeakerCheck.Fine(status.state)
        }
    }

    /** Stopped by itself. Soon after loading, the framing may be why, so the next one gets a turn. */
    private fun ended(reason: String): SpeakerCheck {
        val age = now() - loadedAt
        media = null
        loadError = null
        if (age >= PROVEN_AFTER_MS) return SpeakerCheck.Restart("Cast stopped after ${age / 1000} s: $reason")
        if (framingIndex + 1 >= framings.size) {
            return SpeakerCheck.Failed("Cast stopped after ${age / 1000} s with every framing: $reason")
        }
        framingIndex++
        return SpeakerCheck.Restart("Cast stopped after ${age / 1000} s: $reason, trying ${framings[framingIndex]}")
    }

    override fun stop() {
        val c = channel ?: return
        channel = null
        runCatching {
            if (sessionId.isNotEmpty()) {
                c.post(CastChannel.RECEIVER, CastChannel.NS_RECEIVER, mapOf("type" to "STOP", "sessionId" to sessionId))
            }
        }
        c.close()
    }

    /** A group's stream can be fetched by whichever member leads it right now. */
    override fun allowsPeer(peer: String) =
        AppState.speakers.value.any { it.ip == peer && it.protocol == SpeakerProtocol.CAST } || isCastDevice(peer)

    private val castPeers = ConcurrentHashMap<String, Boolean>()

    private fun isCastDevice(peer: String) = castPeers.getOrPut(peer) {
        runCatching { CastChannel(peer, CastChannel.DEFAULT_PORT).use { it.receiverStatus() }; true }.getOrDefault(false)
    }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
        const val STATUS_EVERY_MS = 15_000L
        const val PROVEN_AFTER_MS = 180_000L
        const val TWELVE_HOURS = 12L * 3600 * LiveStreamServer.BYTES_PER_SEC
    }
}
