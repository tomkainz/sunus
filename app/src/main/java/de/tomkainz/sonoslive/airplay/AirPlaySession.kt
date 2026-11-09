package de.tomkainz.sonoslive.airplay

import de.tomkainz.sonoslive.AppState
import de.tomkainz.sonoslive.SpeakerRefused
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/** The two ways into an AirPlay speaker. */
enum class AirPlayMode { AIRPLAY_2, CLASSIC }

/** What a speaker says about itself in its Bonjour record. */
data class AirPlayFeatures(val bits: Long, val passwordRequired: Boolean, val classicPort: Int? = null) {
    val transientPairing get() = bits and (1L shl 48) != 0L

    /** Which way first. Both get a turn: speakers do not all keep to what they announce. */
    fun modes(): List<AirPlayMode> =
        if (transientPairing || bits == 0L) listOf(AirPlayMode.AIRPLAY_2, AirPlayMode.CLASSIC)
        else listOf(AirPlayMode.CLASSIC, AirPlayMode.AIRPLAY_2)

    companion object {
        /** "0x445F8A00,0x1C340": low half, then high half. A speaker's /info gives one number instead. */
        fun parseBits(text: String?): Long {
            if (text.isNullOrBlank()) return 0
            val parts = text.split(',').map { it.trim().removePrefix("0x").removePrefix("0X") }
            val first = parts[0].toULongOrNull(16)?.toLong() ?: return 0
            val second = parts.getOrNull(1)?.toULongOrNull(16)?.toLong() ?: return first
            return (second shl 32) or (first and 0xFFFFFFFFL)
        }

        /** Stored in [de.tomkainz.sonoslive.Speaker.details]: "features;pw;port of the classic service". */
        fun fromDetails(details: String): AirPlayFeatures {
            val parts = details.split(';')
            return AirPlayFeatures(parseBits(parts.getOrNull(0)), parts.getOrNull(1) == "1", parts.getOrNull(2)?.toIntOrNull())
        }

        fun toDetails(features: String?, password: Boolean, classicPort: Int? = null) =
            "${features.orEmpty()};${if (password) 1 else 0};${classicPort ?: ""}"
    }
}

/**
 * The control side of one AirPlay session: handshake, keep-alive, volume, goodbye.
 * The audio itself is [AirPlayStreamer]. Follows what pyatv (MIT) does on the wire, as
 * the other Android senders do: transient pairing, NTP time, "realtime" audio.
 */
class AirPlaySession(private val host: String, private val port: Int, val mode: AirPlayMode) : Closeable {

    private val rtsp = RtspClient(host, port)
    private val sessionId = (SecureRandom().nextInt() ushr 1).toLong()
    private val uri = "rtsp://${rtsp.localIp}/$sessionId"
    private var rtspSession = "0"
    private var eventSocket: Socket? = null
    private var secret: ByteArray? = null

    /** Encryption key for the audio; null if it goes out in the clear. */
    var audioKey: ByteArray? = null
        private set
    var dataPort = 0
        private set
    var controlPort = 0
        private set
    val ssrc: Int = sessionId.toInt()

    @Volatile
    var alive = true
        private set

    /** Pairing and setup. After this, send audio; [record] starts it. */
    fun setup(timingPort: Int, controlPortHere: Int) {
        if (mode == AirPlayMode.AIRPLAY_2) setupAirPlay2(timingPort, controlPortHere)
        else setupClassic(timingPort, controlPortHere)
    }

    // ---- AirPlay 2 --------------------------------------------------------

    private fun setupAirPlay2(timingPort: Int, controlPortHere: Int) {
        pairTransient()
        val session = rtsp.request(
            "SETUP", uri,
            body = Bplist.write(
                linkedMapOf(
                    "deviceID" to DEVICE_ID,
                    "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                    "timingPort" to timingPort,
                    "timingProtocol" to "NTP",
                    "isMultiSelectAirPlay" to true,
                    "groupContainsGroupLeader" to false,
                    "macAddress" to DEVICE_ID,
                    "model" to "iPhone14,3",
                    "name" to "Sunus",
                    "osBuildVersion" to "20F66",
                    "osName" to "iPhone OS",
                    "osVersion" to "16.5",
                    "senderSupportsRelay" to false,
                    "sourceVersion" to "690.7.1",
                    "statsCollectionEnabled" to false
                )
            ),
            contentType = PLIST
        )
        expect(session, "SETUP")
        session.header("session")?.let { rtspSession = it }
        val answer = plist(session)
        (answer["eventPort"] as? Long)?.toInt()?.takeIf { it > 0 }?.let { openEvents(it) }

        val key = audioKeyFrom(secret!!)
        val stream = rtsp.request(
            "SETUP", uri,
            body = Bplist.write(
                linkedMapOf(
                    "streams" to listOf(
                        linkedMapOf(
                            "audioFormat" to ALAC_44100_16_2,
                            "audioMode" to "default",
                            "controlPort" to controlPortHere,
                            "ct" to CT_ALAC,
                            "isMedia" to true,
                            "latencyMax" to 88200,
                            "latencyMin" to 11025,
                            "shk" to key,
                            "spf" to Alac.FRAMES,
                            "sr" to 44100,
                            "type" to REALTIME,
                            "supportsDynamicStreamID" to false,
                            "streamConnectionID" to sessionId
                        )
                    )
                )
            ),
            contentType = PLIST
        )
        expect(stream, "SETUP stream")
        val first = (plist(stream)["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw IOException("AirPlay: the speaker set up no stream")
        dataPort = (first["dataPort"] as? Long)?.toInt() ?: throw IOException("AirPlay: no data port")
        controlPort = (first["controlPort"] as? Long)?.toInt() ?: throw IOException("AirPlay: no control port")
        audioKey = key
    }

    /** Against [SrpClient]'s fixed secret: the speaker lets anyone on the network in, if set to. */
    private fun pairTransient() {
        val headers = mapOf("X-Apple-HKP" to "4", "Connection" to "keep-alive")
        val m1 = Tlv8.write(
            listOf(
                Tlv8.METHOD to byteArrayOf(0),
                Tlv8.STATE to byteArrayOf(1),
                Tlv8.FLAGS to byteArrayOf(TRANSIENT)
            )
        )
        val m2 = pairStep(headers, m1)
        val salt = m2[Tlv8.SALT] ?: throw IOException("AirPlay: no salt from the speaker")
        val key = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("AirPlay: no key from the speaker")
        val srp = SrpClient("Pair-Setup".toByteArray(), "3939".toByteArray())
        val proof = srp.respond(salt, key)
        val m3 = Tlv8.write(
            listOf(
                Tlv8.STATE to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to SrpClient.minimal(srp.publicKey),
                Tlv8.PROOF to proof
            )
        )
        val m4 = pairStep(headers, m3)
        val serverProof = m4[Tlv8.PROOF] ?: throw IOException("AirPlay: no proof from the speaker")
        if (!srp.verifyServer(serverProof)) throw IOException("AirPlay: the speaker's proof is wrong")
        val shared = srp.sessionKey
        secret = shared
        rtsp.encrypt(
            Hkdf.derive(CONTROL_SALT, CONTROL_WRITE, shared),
            Hkdf.derive(CONTROL_SALT, CONTROL_READ, shared)
        )
    }

    private fun pairStep(headers: Map<String, String>, body: ByteArray): Map<Int, ByteArray> {
        val response = rtsp.request("POST", "/pair-setup", headers, body, "application/octet-stream")
        expect(response, "pair-setup")
        val tlv = Tlv8.read(response.body)
        tlv[Tlv8.ERROR]?.let { error ->
            throw SpeakerRefused("AirPlay: pairing refused (${error.firstOrNull()})")
        }
        return tlv
    }

    /** The speaker's own channel. It must be open and answered, or the speaker ends the session. */
    private fun openEvents(eventPort: Int) {
        val shared = secret ?: return
        var socket: Socket? = null
        for (attempt in 1..5) {
            try {
                socket = Socket().apply { connect(InetSocketAddress(host, eventPort), 3000) }
                break
            } catch (e: IOException) {
                if (attempt == 5) throw e
                Thread.sleep(500)
            }
        }
        val connected = socket!!
        eventSocket = connected
        // Named from the speaker's side, which is why write and read look swapped.
        val stream = HapStream(
            connected.getInputStream(),
            Hkdf.derive(EVENTS_SALT, EVENTS_READ, shared),
            Hkdf.derive(EVENTS_SALT, EVENTS_WRITE, shared)
        )
        Thread({
            try {
                while (alive) answerEvent(connected, stream)
            } catch (e: Exception) {
                // Ends with the session.
            }
        }, "airplay-events").apply {
            isDaemon = true
            start()
        }
    }

    private fun answerEvent(socket: Socket, stream: HapStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            head.append(stream.read().toChar())
            if (head.length > 16384) throw IOException("AirPlay event too long")
        }
        val headers = head.lines().drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':', "").trim() }
        repeat(headers["content-length"]?.toIntOrNull() ?: 0) { stream.read() }
        val cseq = headers["cseq"]?.let { "CSeq: $it\r\n" }.orEmpty()
        val reply = "RTSP/1.0 200 OK\r\n${cseq}Content-Length: 0\r\nAudio-Latency: 0\r\n\r\n"
        socket.getOutputStream().apply {
            write(stream.seal(reply.toByteArray()))
            flush()
        }
    }

    // ---- Classic ----------------------------------------------------------

    private fun setupClassic(timingPort: Int, controlPortHere: Int) {
        val sdp = "v=0\r\n" +
            "o=iTunes $sessionId 0 IN IP4 ${rtsp.localIp}\r\n" +
            "s=iTunes\r\n" +
            "c=IN IP4 $host\r\n" +
            "t=0 0\r\n" +
            "m=audio 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 AppleLossless\r\n" +
            "a=fmtp:96 ${Alac.FMTP}\r\n"
        var announce = rtsp.request("ANNOUNCE", uri, body = sdp.toByteArray(), contentType = "application/sdp")
        if (announce.code == 403 || announce.code == 401) {
            // Denon and others want this first. It asks for nothing to be encrypted.
            val body = byteArrayOf(1) + CURVE25519_FIXED
            runCatching { rtsp.request("POST", "/auth-setup", body = body, contentType = "application/octet-stream") }
            announce = rtsp.request("ANNOUNCE", uri, body = sdp.toByteArray(), contentType = "application/sdp")
        }
        expect(announce, "ANNOUNCE")
        val setup = rtsp.request(
            "SETUP", uri,
            mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=$controlPortHere;timing_port=$timingPort"
            )
        )
        expect(setup, "SETUP")
        val transport = setup.header("transport").orEmpty()
        fun option(name: String) = Regex("$name=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()
        dataPort = option("server_port") ?: throw IOException("AirPlay: no server port")
        controlPort = option("control_port") ?: throw IOException("AirPlay: no control port")
        setup.header("session")?.let { rtspSession = it }
    }

    // ---- Both -------------------------------------------------------------

    /** Tells the speaker where the audio starts. */
    fun record(sequence: Int, timestamp: Long) {
        val info = mapOf(
            "Range" to "npt=0-",
            "Session" to rtspSession,
            "RTP-Info" to "seq=$sequence;rtptime=$timestamp"
        )
        // AirPlay 2 senders name the start in FLUSH only; classic ones do it here as well.
        expect(rtsp.request("RECORD", uri, if (mode == AirPlayMode.CLASSIC) info else emptyMap()), "RECORD")
        // Some speakers refuse this; nothing depends on it.
        runCatching { rtsp.request("FLUSH", uri, info) }
    }

    /** Null if the speaker does not say. */
    fun volumeDb(): Double? = runCatching {
        val response = rtsp.request("GET_PARAMETER", uri, body = "volume\r\n".toByteArray(), contentType = "text/parameters")
        if (!response.ok) return null
        Regex("volume:\\s*(-?[0-9.]+)").find(String(response.body))?.groupValues?.get(1)?.toDouble()
    }.getOrNull()

    fun setVolumeDb(db: Double) {
        rtsp.request(
            "SET_PARAMETER", uri,
            body = String.format(java.util.Locale.ROOT, "volume: %.6f\r\n", db).toByteArray(),
            contentType = "text/parameters"
        )
    }

    /**
     * False once the connection is gone, whatever else the speaker answers. Not every speaker
     * knows /feedback (classic receivers often answer 501), but the connection still has to be
     * used to notice that it broke, and to stay open at all.
     */
    fun keepAlive(): Boolean = try {
        if (feedbackWorks) {
            if (!rtsp.request("POST", "/feedback").ok && mode == AirPlayMode.CLASSIC) feedbackWorks = false
        } else {
            rtsp.request("OPTIONS", "*")
        }
        alive
    } catch (e: IOException) {
        AppState.log("AirPlay $host: connection lost: ${e.message}")
        alive = false
        false
    }

    private var feedbackWorks = true

    val keepAliveEveryMs: Long get() = if (mode == AirPlayMode.AIRPLAY_2) 2000 else 15_000

    /**
     * Goodbye matters: a HomePod left hanging refuses the next start for seconds. Also tried
     * after an error, as long as it does not hold things up.
     */
    override fun close() {
        alive = false
        runCatching {
            rtsp.timeoutMs = 1500
            rtsp.request("TEARDOWN", uri, mapOf("Session" to rtspSession))
        }
        runCatching { eventSocket?.close() }
        rtsp.close()
    }

    private fun expect(response: RtspResponse, what: String) {
        when {
            response.ok -> Unit
            // 453: busy with somebody else. The rest: not allowed to, as things are set up.
            response.code == 401 || response.code == 403 || response.code == 470 ->
                throw SpeakerRefused("AirPlay: $what refused (RTSP ${response.code})")
            else -> throw RtspError(response.code, "AirPlay $what")
        }
    }

    private fun plist(response: RtspResponse): Map<*, *> =
        Bplist.read(response.body) as? Map<*, *> ?: throw IOException("AirPlay: unreadable answer")

    companion object {
        private const val PLIST = "application/x-apple-binary-plist"
        private const val ALAC_44100_16_2 = 0x40000
        private const val CT_ALAC = 2
        private const val REALTIME = 96
        private const val TRANSIENT: Byte = 0x10
        private const val CONTROL_SALT = "Control-Salt"
        private const val CONTROL_WRITE = "Control-Write-Encryption-Key"
        private const val CONTROL_READ = "Control-Read-Encryption-Key"
        private const val EVENTS_SALT = "Events-Salt"
        private const val EVENTS_WRITE = "Events-Write-Encryption-Key"
        private const val EVENTS_READ = "Events-Read-Encryption-Key"

        /** Not a real address, and not tied to the phone. */
        private const val DEVICE_ID = "AA:BB:CC:DD:EE:FF"

        /** A public key for /auth-setup, which only checks that there is one. Same as OwnTone and pyatv send. */
        private val CURVE25519_FIXED = byteArrayOf(
            0x59, 0x02, 0xed.toByte(), 0xe9.toByte(), 0x0d, 0x4e, 0xf2.toByte(), 0xbd.toByte(),
            0x4c, 0xb6.toByte(), 0x8a.toByte(), 0x63, 0x30, 0x03, 0x82.toByte(), 0x07,
            0xa9.toByte(), 0x4d, 0xbd.toByte(), 0x50, 0xd8.toByte(), 0xaa.toByte(), 0x46, 0x5b,
            0x5d, 0x8c.toByte(), 0x01, 0x2a, 0x0c, 0x7e, 0x1d, 0x4e
        )

        private fun audioKeyFrom(shared: ByteArray) = shared.copyOf(32)

        /** What the volume slider means in AirPlay's decibels. */
        fun percentToDb(percent: Int): Double =
            if (percent <= 0) -144.0 else -30.0 + 30.0 * percent.coerceAtMost(100) / 100.0

        fun dbToPercent(db: Double): Int =
            if (db <= -30.0) 0 else Math.round((db + 30.0) / 30.0 * 100).toInt().coerceIn(0, 100)
    }
}
