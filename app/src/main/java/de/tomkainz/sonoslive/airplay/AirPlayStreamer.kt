package de.tomkainz.sonoslive.airplay

import de.tomkainz.sonoslive.AppState
import de.tomkainz.sonoslive.AudioSink
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.security.SecureRandom
import java.util.concurrent.locks.LockSupport

/**
 * Sends the audio: the live stream, resampled, in RTP packets at a steady pace, with
 * the time information the speaker needs to play them at the right moment. Resends what
 * got lost for as long as the speaker's latency leaves room for it.
 */
class AirPlayStreamer(private val host: String) : AudioSink {

    private val control = bindNear(6001)
    private val timing = bindNear(6011)
    private val random = SecureRandom()

    /**
     * Never blocks: audio that cannot leave right now is better lost, and asked for again,
     * than holding up everything after it. Replaced when it fills up and stays full.
     */
    private var audio: DatagramChannel? = null
    private var destination: InetSocketAddress? = null
    private val audioLock = Any()

    val controlPort: Int get() = control.localPort
    val timingPort: Int get() = timing.localPort

    private val resampler = Resampler()
    private val ring = AudioRing(44100 * 2)
    private val resampled = ShortArray(Resampler.outputRoom(16384))
    private val incoming = ShortArray(16384)

    private val backlog = arrayOfNulls<ByteArray>(BACKLOG)

    @Volatile
    private var sequence = random.nextInt(0x10000)
    @Volatile
    private var head = (random.nextInt() ushr 1).toLong()

    val firstSequence = sequence
    val firstTimestamp = head

    /** Which moment belongs to which sample. Worked out from it, so that the two always agree. */
    private class Anchor(val ntp: Long, val timestamp: Long)

    @Volatile
    private var anchor = Anchor(0, 0)

    @Volatile
    private var running = false
    private val threads = mutableListOf<Thread>()

    // For the log.
    @Volatile
    private var packets = 0L
    @Volatile
    private var resent = 0L
    @Volatile
    private var resendAsked = 0L
    @Volatile
    private var underruns = 0L
    @Volatile
    private var trims = 0L
    @Volatile
    private var timingAnswered = 0L
    @Volatile
    private var socketsReplaced = 0L
    private var fullSince = 0L

    @Volatile
    private var lastSent = System.nanoTime()
    private var sender: Thread? = null

    /** Does the speaker ask for the time? Without that, it cannot play in time. */
    val timingSeen get() = timingAnswered > 0

    /** How long since the last packet went out. The pace is 8 ms, so seconds mean something is stuck. */
    val silentForMs get() = (System.nanoTime() - lastSent) / 1_000_000

    /** Where the sending thread is, for the log when it stands still. */
    fun senderPosition(): String = sender?.let { t ->
        "${t.state} at " + t.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    } ?: "no thread"

    override fun write(pcm: ByteArray) {
        // Chunks are whole frames; split anything larger than the buffers here.
        var at = 0
        while (at < pcm.size) {
            val bytes = minOf(pcm.size - at, incoming.size * 2)
            var i = 0
            while (i < bytes) {
                incoming[i / 2] = ((pcm[at + i + 1].toInt() shl 8) or (pcm[at + i].toInt() and 0xFF)).toShort()
                i += 2
            }
            val n = resampler.process(incoming, bytes / 2, resampled)
            ring.write(resampled, n)
            at += bytes
        }
    }

    override fun stats(): String = "AirPlay $host: $packets packets, ${ring.frames() * 1000 / RATE} ms ready, " +
        "$resendAsked resend request(s), $resent resent, $underruns underrun(s), $trims trim(s), " +
        "$timingAnswered time request(s), $socketsReplaced socket(s) replaced, last packet $silentForMs ms ago"

    fun start(session: AirPlaySession) {
        running = true
        anchor = Anchor(NtpClock.now(), head)
        destination = InetSocketAddress(host, session.dataPort)
        synchronized(audioLock) { audio = openAudio() }
        thread("airplay-timing") { timingLoop() }
        thread("airplay-control") { controlLoop() }
        thread("airplay-sync") { syncLoop(session) }
        lastSent = System.nanoTime()
        thread("airplay-send") { sendLoop(session) }
        sender = threads.last()
    }

    fun stop() {
        running = false
        runCatching { control.close() }
        runCatching { timing.close() }
        synchronized(audioLock) {
            runCatching { audio?.close() }
            audio = null
        }
        threads.forEach { it.interrupt() }
    }

    // ---- Audio ------------------------------------------------------------

    private fun sendLoop(session: AirPlaySession) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val key = session.audioKey
        val cipher = key?.let { AirPlayCipher(it) }
        val ssrc = session.ssrc
        val frames = ShortArray(Alac.FRAMES * 2)
        val alac = ByteArray(Alac.SIZE)
        val interval = Alac.FRAMES * 1_000_000_000L / RATE
        var nonce = 0L
        var next = System.nanoTime()
        var filling = true
        var first = true
        while (running) {
            // Waits for a bit of a reserve, playing silence, so that short gaps in capture stay inaudible.
            if (filling && ring.frames() >= PREBUFFER_FRAMES) filling = false
            if (filling || !ring.read(frames, Alac.FRAMES)) {
                if (!filling) {
                    filling = true
                    underruns++
                }
                frames.fill(0)
            }
            // The phone's clock and capture's clock differ a little; this catches up over hours.
            if (ring.frames() > TRIM_ABOVE_FRAMES) {
                ring.trimTo(PREBUFFER_FRAMES)
                trims++
            }
            Alac.encode(frames, 0, alac)
            val packet = rtp(first, sequence, head, ssrc, alac, cipher, nonce++)
            first = false
            backlog[sequence and (BACKLOG - 1)] = packet
            sendAudio(packet)
            packets++
            lastSent = System.nanoTime()
            sequence = (sequence + 1) and 0xFFFF
            head = (head + Alac.FRAMES) and 0xFFFFFFFFL
            next += interval
            val wait = next - System.nanoTime()
            when {
                wait > 0 -> LockSupport.parkNanos(wait)
                // Far behind, say after the phone slept: do not rush a burst into the speaker,
                // and from here on this sample goes with this moment.
                wait < -MAX_BEHIND_NANOS -> {
                    next = System.nanoTime()
                    anchor = Anchor(NtpClock.now(), head)
                }
            }
        }
    }

    private fun openAudio(): DatagramChannel = DatagramChannel.open().apply {
        configureBlocking(false)
        connect(destination)
    }

    /**
     * In the emulator a socket's send buffer stopped draining after about 60,000 packets,
     * with the network itself still fine; a blocking send then hung for good. A speaker
     * does not know the port the audio comes from, so a new socket simply carries on.
     */
    private fun sendAudio(packet: ByteArray) {
        synchronized(audioLock) {
            try {
                val channel = audio ?: return
                if (channel.write(ByteBuffer.wrap(packet)) > 0) {
                    fullSince = 0
                    return
                }
                val now = System.nanoTime()
                if (fullSince == 0L) fullSince = now
                if (now - fullSince > SOCKET_STUCK_NANOS) {
                    fullSince = 0
                    socketsReplaced++
                    AppState.log("AirPlay $host: send buffer stuck after $packets packets, new socket")
                    runCatching { channel.close() }
                    audio = openAudio()
                    audio?.write(ByteBuffer.wrap(packet))
                }
            } catch (e: Exception) {
                // Wi-Fi drops for a moment; the speaker asks for what it missed.
            }
        }
    }

    // ---- Time -------------------------------------------------------------

    /** Once a second: which sample should sound at which moment. */
    private fun syncLoop(session: AirPlaySession) {
        val destination = InetSocketAddress(host, session.controlPort)
        var first = true
        while (running) {
            val position = head
            val from = anchor
            val packet = sync(first, position, ntpAt(from.ntp, from.timestamp, position))
            first = false
            try {
                control.send(DatagramPacket(packet, packet.size, destination))
                Thread.sleep(SYNC_EVERY_MS)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    /** The speaker keeps asking the time, to work out how far its clock is from ours. */
    private fun timingLoop() {
        val buffer = ByteArray(64)
        while (running) {
            val request = DatagramPacket(buffer, buffer.size)
            try {
                timing.receive(request)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                return
            }
            if (request.length < 32 || buffer[1].toInt() and 0x7F != TIMING_REQUEST) continue
            val answer = timingAnswer(buffer, NtpClock.now())
            runCatching { timing.send(DatagramPacket(answer, answer.size, request.socketAddress)) }
            timingAnswered++
        }
    }

    // ---- Lost packets -----------------------------------------------------

    private fun controlLoop() {
        val buffer = ByteArray(1500)
        while (running) {
            val request = DatagramPacket(buffer, buffer.size)
            try {
                control.receive(request)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                return
            }
            if (request.length < 8 || buffer[1].toInt() and 0x7F != RESEND_REQUEST) continue
            resendAsked++
            val firstLost = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
            val count = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
            for (i in 0 until count.coerceAtMost(BACKLOG)) {
                val wanted = (firstLost + i) and 0xFFFF
                val packet = backlog[wanted and (BACKLOG - 1)] ?: continue
                if (sequenceOf(packet) != wanted) continue
                val resend = ByteArray(4 + packet.size)
                resend[0] = 0x80.toByte()
                resend[1] = RESEND_ANSWER.toByte()
                resend[2] = packet[2]
                resend[3] = packet[3]
                packet.copyInto(resend, 4)
                runCatching { control.send(DatagramPacket(resend, resend.size, request.socketAddress)) }
                // As it was sent the first time, too. iOS only uses the way above, but OwnTone
                // met receivers that do not understand it, and a double is simply dropped.
                sendAudio(packet)
                resent++
            }
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        threads += Thread(body, name).apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        const val RATE = 44100
        private const val BACKLOG = 1024 // about 8 s
        private const val PREBUFFER_FRAMES = RATE / 5 // 200 ms
        private const val TRIM_ABOVE_FRAMES = RATE * 8 / 10
        private const val SYNC_EVERY_MS = 1000L
        private const val MAX_BEHIND_NANOS = 500_000_000L
        private const val SOCKET_STUCK_NANOS = 300_000_000L
        private const val TIMING_REQUEST = 0x52
        private const val RESEND_REQUEST = 0x55
        private const val RESEND_ANSWER = 0xD6

        /** How long after sending the speaker plays a sample: 1.5 s, as with the other senders. */
        const val LATENCY_FRAMES = 22050L + RATE

        /** Ports near a known number, so a firewall rule or a test setup can name them. */
        private fun bindNear(preferred: Int): DatagramSocket {
            for (port in preferred until preferred + 10) {
                try {
                    return DatagramSocket(port).apply { soTimeout = 1000 }
                } catch (e: BindException) {
                    continue
                }
            }
            return DatagramSocket().apply { soTimeout = 1000 }
        }

        /** The moment [timestamp] is due, counted from a known one, in NTP format. */
        fun ntpAt(anchorNtp: Long, anchorTimestamp: Long, timestamp: Long): Long {
            val frames = (timestamp - anchorTimestamp) and 0xFFFFFFFFL
            val seconds = frames / RATE
            val fraction = ((frames % RATE) shl 32) / RATE
            return anchorNtp + (seconds shl 32) + fraction
        }

        fun sequenceOf(packet: ByteArray) = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

        fun rtp(
            marker: Boolean, sequence: Int, timestamp: Long, ssrc: Int,
            payload: ByteArray, cipher: AirPlayCipher?, nonce: Long
        ): ByteArray {
            val header = ByteBuffer.allocate(12)
                .put(0x80.toByte())
                .put((if (marker) 0xE0 else 0x60).toByte())
                .putShort(sequence.toShort())
                .putInt(timestamp.toInt())
                .putInt(ssrc)
                .array()
            if (cipher == null) return header + payload
            // The AAD is the part of the header that does not change on a resend: time and source.
            val sealed = cipher.encrypt(nonce, payload, 0, payload.size, header.copyOfRange(4, 12))
            return header + sealed + AirPlayCipher.nonce(nonce).copyOfRange(4, 12)
        }

        fun sync(first: Boolean, head: Long, ntp: Long): ByteArray = ByteBuffer.allocate(20)
            .put((if (first) 0x90 else 0x80).toByte())
            .put(0xD4.toByte())
            .putShort(7)
            .putInt((head - LATENCY_FRAMES).toInt())
            .putLong(ntp)
            .putInt(head.toInt())
            .array()

        /** Mirrors the request's send time as the reference, per NTP. */
        fun timingAnswer(request: ByteArray, ntp: Long): ByteArray = ByteBuffer.allocate(32)
            .put(0x80.toByte())
            .put(0xD3.toByte())
            .putShort(7)
            .putInt(0)
            .put(request, 24, 8)
            .putLong(ntp)
            .putLong(ntp)
            .array()
    }
}
