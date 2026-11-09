package de.tomkainz.sonoslive

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * How the stream's end is announced over HTTP. Speakers disagree on what an endless
 * stream may look like: some hang up without a length, others after reaching one.
 */
enum class StreamFraming { NONE, LENGTH, CHUNKED }

/** Gets the audio without going through HTTP. Must not block: it runs on the audio clock. */
interface AudioSink {
    fun write(pcm: ByteArray)
    fun stats(): String
}

/** What to answer a request for the stream with. */
data class StreamPlan(
    val code: Int,
    val skip: Int = 0,
    val chunked: Boolean = false,
    val length: Long? = null,
    val contentRange: String? = null
)

/**
 * The live stream the speaker pulls: endless WAV (16-bit PCM) at /live.wav. For Sonos
 * without Content-Length; the connection only ends when the client disconnects.
 *
 * Built like sonos-stream.py from the Linux bridge, because that design has
 * proven itself with Sonos:
 * - Fan-out to any number of clients. The Sonos often opens the URL twice
 *   (probe, then playback), and both get the same stream.
 * - Every new client first gets the last [BURST_SECONDS] as a buffer.
 * - The stream stays on real time ([StreamTimeline]): audio that comes late is
 *   waited for, audio that never comes is replaced by silence of the same length.
 *   If no audio arrives at all for 1.5 s, silence goes out in real time, which
 *   keeps the Sonos connected even when nothing plays. A short stall of the ADB
 *   connection does not cut a hole into the music: the audio comes late, not lost.
 * - A client that cannot keep up loses old data instead of holding up the
 *   capture.
 */
class LiveStreamServer(private val preferredPort: Int = 8090) {

    companion object {
        const val RATE = 48000
        const val CHANNELS = 2
        const val BYTES_PER_SEC = RATE * CHANNELS * 2
        const val PATH = "/live.wav"
        const val PATH_L16 = "/live.l16"

        /** Just under 4 GiB, the most a WAV can say about itself: about 6.2 hours here. */
        const val WAV_LIMIT = 0xFFFFFFFEL
        const val HEADER_BYTES = 44

        private const val TICK_MS = 20L
        private const val TICK_BYTES = (BYTES_PER_SEC * TICK_MS / 1000).toInt()
        private const val BURST_SECONDS = 0.4
        /** A wait for audio this long goes into the log, with how far the stream is behind. */
        private const val GAP_LOG_MS = 300L
        private const val CLIENT_QUEUE = 256 // about 5 s, then data is dropped
        private const val MAX_STREAMS = 4
        private const val MAX_CONNECTIONS = 16

        /** The running server, so the log can say how it is doing. */
        @Volatile
        var active: LiveStreamServer? = null
            private set

        /** What a stream has sent against what real time asks for: under 100 means the network held it up. */
        fun clientFacts(seconds: Double, sent: Long, longestWriteMs: Long, dropped: Long): String {
            val share = if (seconds > 0) sent / (seconds * BYTES_PER_SEC) * 100 else 0.0
            return String.format(
                java.util.Locale.ROOT, "%.0f s, %.1f MB, %.1f%% of real time, longest write %d ms, %d chunks dropped",
                seconds, sent / 1_000_000.0, share, longestWriteMs, dropped
            )
        }

        /**
         * Answers [range] (the Range header, if any) for a stream that cannot seek. Only
         * the WAV header can be skipped: MPD asks for "bytes=44-" after probing it.
         */
        fun plan(framing: StreamFraming, limit: Long, range: String?, http11: Boolean, raw: Boolean): StreamPlan {
            val chunked = framing == StreamFraming.CHUNKED && http11
            val length = if (framing == StreamFraming.LENGTH) limit else null
            val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            val total = length ?: 0xFFFFFFFFL
            return when {
                range == null -> StreamPlan(200, chunked = chunked, length = length)
                start == 0L && length == null -> StreamPlan(200, chunked = chunked)
                start == 0L -> StreamPlan(206, length = length, contentRange = "bytes 0-${total - 1}/$total")
                raw || start > HEADER_BYTES -> StreamPlan(416, contentRange = "bytes */$total")
                else -> StreamPlan(
                    206, skip = start.toInt(), chunked = chunked, length = length?.minus(start),
                    contentRange = "bytes $start-${total - 1}/$total"
                )
            }
        }

        /** Copies [data], swapping each sample to big-endian, as L16 wants it. */
        fun swapped(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            var i = 0
            while (i + 1 < data.size) {
                out[i] = data[i + 1]
                out[i + 1] = data[i]
                i += 2
            }
            return out
        }

        fun wavHeader(): ByteArray {
            // Length fields at maximum: nobody knows how long the stream will run.
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(-1)
            b.put("WAVEfmt ".toByteArray()).putInt(16)
            b.putShort(1).putShort(CHANNELS.toShort()).putInt(RATE).putInt(BYTES_PER_SEC)
            b.putShort((CHANNELS * 2).toShort()).putShort(16)
            b.put("data".toByteArray()).putInt(-1)
            return b.array()
        }
    }

    @Volatile
    var port: Int = preferredPort
        private set

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val connections = AtomicInteger()

    /**
     * Who may fetch the stream. Anyone on the same Wi-Fi could otherwise
     * listen in; the service limits it to the Sonos speakers.
     */
    @Volatile
    var allowPeer: (String) -> Boolean = { true }

    /** Set by the speaker's driver before it asks the speaker to fetch the stream. */
    @Volatile
    var framing = StreamFraming.NONE
    @Volatile
    var contentLength = WAV_LIMIT
    @Volatile
    var contentType = "audio/wav"

    /** Renderers that care look for these DLNA headers. */
    @Volatile
    var dlna = false

    private val sinks = java.util.concurrent.CopyOnWriteArrayList<AudioSink>()

    fun addSink(sink: AudioSink) {
        sinks += sink
        publishClients()
    }

    fun removeSink(sink: AudioSink) {
        sinks -= sink
        publishClients()
    }

    /** Streams being fetched right now. */
    fun streamCount() = clients.size

    private fun publishClients() {
        AppState.clients.value = clients.size + sinks.size
    }

    /** Requests for the stream so far, allowed or not; for error reports. */
    val streamRequests = AtomicInteger()
    val refusedPeers: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    private val live = ArrayBlockingQueue<ByteArray>(512)
    private var remainder = ByteArray(0)

    private val clients = Collections.synchronizedSet(LinkedHashSet<Client>())
    private val history = ArrayDeque<ByteArray>()
    private var historyBytes = 0
    private val burstBytes = (BYTES_PER_SEC * BURST_SECONDS).toInt() / 4 * 4

    @Volatile
    var drops = 0L
        private set

    /** Waits for audio of [GAP_LOG_MS] and more, and the longest wait for audio. */
    @Volatile
    var gaps = 0L
        private set
    @Volatile
    var maxWaitMs = 0L
        private set

    /** From the [StreamTimeline]: silence put in, late audio dropped, how far behind real time. */
    @Volatile
    var silenceMs = 0L
        private set
    @Volatile
    var droppedLateMs = 0L
        private set
    @Volatile
    var behindMs = 0L
        private set

    private class Client(val socket: Socket, val peer: String) {
        val queue = ArrayBlockingQueue<ByteArray>(CLIENT_QUEUE)
        val startedAt = System.nanoTime()
        @Volatile
        var sent = 0L
        @Volatile
        var dropped = 0L
        /** The longest a single write took, since the last [stats] and overall. */
        @Volatile
        var longestWriteMs = 0L
        @Volatile
        var longestWriteEverMs = 0L

        val seconds get() = (System.nanoTime() - startedAt) / 1e9
    }

    /** One line for the log. Each call starts a new period for the longest write, so a stall can be dated. */
    fun stats(): String {
        val now = synchronized(clients) { clients.toList() }.joinToString("; ") {
            val line = "${it.peer} ${clientFacts(it.seconds, it.sent, it.longestWriteMs, it.dropped)}, " +
                "${it.queue.size} queued"
            it.longestWriteMs = 0
            line
        }
        val all = listOfNotNull(now.ifEmpty { null }) + sinks.map { it.stats() }
        return "${all.joinToString("; ").ifEmpty { "no client" }}. Gaps in the audio $gaps, " +
            "longest wait for audio $maxWaitMs ms, silence put in $silenceMs ms, " +
            "late audio dropped $droppedLateMs ms, $behindMs ms behind real time"
    }

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        var candidate = preferredPort
        while (true) {
            try {
                socket.bind(InetSocketAddress(candidate))
                port = candidate
                break
            } catch (e: IOException) {
                if (++candidate >= preferredPort + 20) {
                    runCatching { socket.close() }
                    throw IOException("No free port from $preferredPort")
                }
            }
        }
        serverSocket = socket
        running = true
        active = this
        thread("live-accept") { acceptLoop(socket) }
        thread("live-pump") { pumpLoop() }
    }

    fun stop() {
        if (active === this) active = null
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clients) {
            clients.forEach { runCatching { it.socket.close() } }
            clients.clear()
        }
        sinks.clear()
        pool.shutdownNow()
        AppState.clients.value = 0
    }

    /**
     * New capture data. It may come in chunks of any size; the remainder that
     * does not make up a whole frame (4 bytes) is held back, otherwise the
     * stereo channels would drift apart.
     */
    fun offer(data: ByteArray, length: Int = data.size) {
        if (!running || length <= 0) return
        val joined = if (remainder.isEmpty()) data.copyOf(length) else remainder + data.copyOf(length)
        val aligned = joined.size / 4 * 4
        remainder = joined.copyOfRange(aligned, joined.size)
        if (aligned == 0) return
        val chunk = if (aligned == joined.size) joined else joined.copyOf(aligned)
        if (!live.offer(chunk)) {
            live.poll()
            live.offer(chunk)
        }
    }

    // ---- Clock ------------------------------------------------------------

    private fun pumpLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val silence = ByteArray(TICK_BYTES)
        val timeline = StreamTimeline(RATE, nowMs())
        val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
        var lastLevelUpdate = 0L
        var peak = 0
        var lastData = 0L
        var gone = false
        var trimming = false
        while (running) {
            // Wakes at least every tick: the timeline decides when silence is due.
            val data = try {
                live.poll(TICK_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            val now = nowMs()
            if (data != null) {
                val wait = if (lastData > 0) now - lastData else 0
                if (lastData > 0) maxWaitMs = maxOf(maxWaitMs, wait)
                if (wait >= GAP_LOG_MS) {
                    gaps++
                    // Measured before this chunk counts. Late audio follows right after and
                    // makes up for it; what is still missing then gets silence ("Put in").
                    val behind = timeline.millis(timeline.behindFrames(now))
                    AppState.log("Audio gap: none for $wait ms from " +
                        "${clock.format(java.util.Date(System.currentTimeMillis() - wait))}, " +
                        "stream $behind ms behind real time" + if (gone) ", silence was fed in" else "")
                }
                gone = false
                lastData = now
                AppState.audioFlowing.value = true
                val frames = data.size / 4
                val keep = timeline.onAudio(frames, now)
                if (keep < frames && !trimming) {
                    AppState.log("Late audio: the stream ran ${timeline.millis(-timeline.behindFrames(now))} ms " +
                        "ahead of real time, dropping audio until it is back at 500 ms")
                }
                trimming = keep < frames
                if (keep > 0) {
                    val chunk = if (keep == frames) data else data.copyOf(keep * 4)
                    broadcast(chunk)
                    peak = maxOf(peak, peakOf(chunk))
                }
            }
            val due = timeline.silenceDue(now)
            if (due > 0) {
                if (!timeline.sourceGone(now)) {
                    AppState.log("Put in ${timeline.millis(due)} ms of silence for audio that did not come")
                } else if (!gone) {
                    gone = true
                    AppState.audioFlowing.value = false
                    if (lastData > 0) {
                        AppState.log("No audio for ${now - lastData} ms: silence fed in, " +
                            "${timeline.millis(due)} ms at once to catch up")
                    }
                }
                sendSilence(due, silence)
                peak = 0
            }
            behindMs = timeline.millis(timeline.behindFrames(now))
            silenceMs = timeline.millis(timeline.silenceFrames)
            droppedLateMs = timeline.millis(timeline.droppedFrames)
            if (now - lastLevelUpdate >= 100) {
                AppState.level.value = peak / 32768f
                peak = 0
                lastLevelUpdate = now
            }
        }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000

    /** [frames] of silence, in chunks of one tick like the rest of the stream. */
    private fun sendSilence(frames: Long, tick: ByteArray) {
        var left = frames * 4
        while (left > 0) {
            val n = minOf(left, tick.size.toLong()).toInt()
            broadcast(if (n == tick.size) tick else ByteArray(n))
            left -= n
        }
    }

    private fun peakOf(data: ByteArray): Int {
        var max = 0
        var i = 0
        // every 8th sample is enough for a level meter
        while (i + 1 < data.size) {
            val v = abs(((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt())
            if (v > max) max = v
            i += 16
        }
        return max
    }

    private fun broadcast(data: ByteArray) {
        val targets: List<Client>
        // Buffer and client list under the same lock: a new client gets every
        // chunk exactly once, either in the buffer or live.
        synchronized(history) {
            history.addLast(data)
            historyBytes += data.size
            while (historyBytes > burstBytes && history.isNotEmpty()) {
                historyBytes -= history.removeFirst().size
            }
            synchronized(clients) { targets = clients.toList() }
        }
        for (c in targets) {
            if (!c.queue.offer(data)) {
                drops++
                c.dropped++
                c.queue.poll()
                c.queue.offer(data)
            }
        }
        for (sink in sinks) sink.write(data)
    }

    // ---- HTTP -------------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) continue else break
            }
            // Bounded, so a flood of connections cannot exhaust threads and memory.
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(client)
                    } finally {
                        connections.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                connections.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val http11 = parts.getOrNull(2) != "HTTP/1.0"
            val headers = HashMap<String, String>()
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':', "").trim()
            }
            val peer = socket.inetAddress?.hostAddress ?: "?"
            val range = headers["range"]
            AppState.log("$peer  $method $path" + (range?.let { " ($it)" } ?: "") +
                (headers["user-agent"]?.let { ", $it" } ?: ""))

            when (path) {
                PATH, PATH_L16 -> {
                    streamRequests.incrementAndGet()
                    if (!allowPeer(peer)) {
                        refusedPeers += peer
                        AppState.log("$peer  refused (403): not a known speaker")
                        head(out, 403, "text/plain", 0)
                        return
                    }
                    if (clients.size >= MAX_STREAMS) {
                        head(out, 503, "text/plain", 0)
                        return
                    }
                    val raw = path == PATH_L16
                    val plan = plan(framing, contentLength, range, http11, raw)
                    val extra = buildList {
                        plan.contentRange?.let { add("Content-Range: $it") }
                        if (plan.chunked) add("Transfer-Encoding: chunked")
                        if (dlna) {
                            add("TransferMode.dlna.org: Streaming")
                            if (headers.containsKey("getcontentfeatures.dlna.org")) {
                                add("contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                            }
                        }
                    }
                    val type = if (raw) "audio/L16;rate=$RATE;channels=$CHANNELS" else contentType
                    head(out, plan.code, type, if (plan.code == 416) 0 else plan.length, extra)
                    if (method == "HEAD" || plan.code == 416) return
                    stream(socket, out, peer, plan, raw)
                }
                "/healthz" -> {
                    val body = ("{\"ok\":true,\"port\":$port,\"clients\":${clients.size}," +
                        "\"live\":${AppState.audioFlowing.value},\"drops\":$drops," +
                        "\"gaps\":$gaps,\"maxWaitMs\":$maxWaitMs,\"silenceMs\":$silenceMs," +
                        "\"droppedLateMs\":$droppedLateMs,\"behindMs\":$behindMs}").toByteArray()
                    head(out, 200, "application/json", body.size.toLong())
                    if (method != "HEAD") out.write(body)
                }
                else -> head(out, 404, "text/plain", 0)
            }
            out.flush()
        } catch (e: Exception) {
            // Aborts are normal: the Sonos disconnects on stop or source change.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun stream(socket: Socket, out: OutputStream, peer: String, plan: StreamPlan, raw: Boolean) {
        val client = Client(socket, peer)
        val limit = plan.length ?: Long.MAX_VALUE
        var written = 0L
        fun send(data: ByteArray, offset: Int = 0) {
            val bytes = if (raw) swapped(data) else data
            val n = bytes.size - offset
            if (n <= 0) return
            if (plan.chunked) {
                out.write("${n.toString(16)}\r\n".toByteArray())
                out.write(bytes, offset, n)
                out.write("\r\n".toByteArray())
            } else {
                out.write(bytes, offset, n)
            }
            written += n
        }
        val burst: List<ByteArray>
        synchronized(history) {
            burst = history.toList()
            clients.add(client)
        }
        publishClients()
        AppState.lastClient.value = peer
        var ending = "the stream was stopped"
        try {
            // Every client joins mid-stream and needs its own header.
            if (!raw) send(wavHeader(), plan.skip)
            for (chunk in burst) {
                send(chunk)
                client.sent += chunk.size
            }
            while (running) {
                // What it was promised is what it gets; it comes back for more.
                if (written >= limit) {
                    ending = "the announced length was reached"
                    break
                }
                val data = client.queue.poll(30, TimeUnit.SECONDS)
                if (data == null) {
                    ending = "no audio for 30 s"
                    break
                }
                val before = System.nanoTime()
                send(data)
                val tookMs = (System.nanoTime() - before) / 1_000_000
                if (tookMs > client.longestWriteMs) client.longestWriteMs = tookMs
                if (tookMs > client.longestWriteEverMs) client.longestWriteEverMs = tookMs
                client.sent += data.size
            }
        } catch (e: Exception) {
            // "Broken pipe" or "reset": the speaker hung up. "timed out": the network was gone.
            ending = "${e.javaClass.simpleName}: ${e.message}"
            throw e
        } finally {
            clients.remove(client)
            publishClients()
            AppState.log("$peer  stream ended, $ending. " +
                clientFacts(client.seconds, client.sent, client.longestWriteEverMs, client.dropped))
        }
    }

    private fun head(out: OutputStream, code: Int, type: String, length: Long?, extra: List<String> = emptyList()) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            416 -> "Range Not Satisfiable"
            403 -> "Forbidden"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $type\r\n")
        if (length != null) sb.append("Content-Length: $length\r\n")
        sb.append("Cache-Control: no-cache, no-store\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("Connection: close\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body, name).apply {
            isDaemon = true
            start()
        }
    }
}
