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
import kotlin.math.abs

/**
 * The live stream the Sonos pulls: endless WAV (16-bit PCM) at /live.wav,
 * without Content-Length; the connection only ends when the client disconnects.
 *
 * Built like sonos-stream.py from the Linux bridge, because that design has
 * proven itself with Sonos:
 * - Fan-out to any number of clients. The Sonos often opens the URL twice
 *   (probe, then playback), and both get the same stream.
 * - Every new client first gets the last [BURST_SECONDS] as a buffer.
 * - If no audio arrives for longer than [SILENCE_GRACE_MS], silence is fed in
 *   at real-time pace. That keeps the Sonos connected even when nothing plays.
 * - A client that cannot keep up loses old data instead of holding up the
 *   capture.
 */
class LiveStreamServer(private val preferredPort: Int = 8090) {

    companion object {
        const val RATE = 48000
        const val CHANNELS = 2
        const val BYTES_PER_SEC = RATE * CHANNELS * 2
        const val PATH = "/live.wav"

        private const val TICK_MS = 20L
        private const val TICK_BYTES = (BYTES_PER_SEC * TICK_MS / 1000).toInt()
        private const val BURST_SECONDS = 0.4
        private const val SILENCE_GRACE_MS = 250L
        private const val CLIENT_QUEUE = 256 // about 5 s, then data is dropped

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

    private val live = ArrayBlockingQueue<ByteArray>(512)
    private var remainder = ByteArray(0)

    private val clients = Collections.synchronizedSet(LinkedHashSet<Client>())
    private val history = ArrayDeque<ByteArray>()
    private var historyBytes = 0
    private val burstBytes = (BYTES_PER_SEC * BURST_SECONDS).toInt() / 4 * 4

    @Volatile
    var drops = 0L
        private set

    private class Client(val socket: Socket, val peer: String) {
        val queue = ArrayBlockingQueue<ByteArray>(CLIENT_QUEUE)
        @Volatile
        var sent = 0L
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
                if (++candidate >= preferredPort + 20) throw IOException("No free port from $preferredPort")
            }
        }
        serverSocket = socket
        running = true
        thread("live-accept") { acceptLoop(socket) }
        thread("live-pump") { pumpLoop() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(clients) {
            clients.forEach { runCatching { it.socket.close() } }
            clients.clear()
        }
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
        val silence = ByteArray(TICK_BYTES)
        var filling = false
        var nextSilence = 0L
        var lastLevelUpdate = 0L
        var peak = 0
        while (running) {
            val timeout = if (filling) {
                (nextSilence - System.nanoTime() / 1_000_000).coerceAtLeast(0)
            } else SILENCE_GRACE_MS
            val data = try {
                live.poll(timeout, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            val now = System.nanoTime() / 1_000_000
            if (data == null) {
                if (!filling) {
                    filling = true
                    nextSilence = now
                    AppState.audioFlowing.value = false
                }
                broadcast(silence)
                nextSilence += TICK_MS
                if (nextSilence < now) nextSilence = now + TICK_MS
                peak = 0
            } else {
                filling = false
                AppState.audioFlowing.value = true
                broadcast(data)
                peak = maxOf(peak, peakOf(data))
            }
            if (now - lastLevelUpdate >= 100) {
                AppState.level.value = peak / 32768f
                peak = 0
                lastLevelUpdate = now
            }
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
                c.queue.poll()
                c.queue.offer(data)
            }
        }
    }

    // ---- HTTP -------------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) continue else break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
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
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val peer = socket.inetAddress?.hostAddress ?: "?"
            AppState.log("$peer  $method $path")

            when (path) {
                PATH -> {
                    head(out, 200, "audio/wav", null)
                    if (method == "HEAD") return
                    stream(socket, out, peer)
                }
                "/healthz" -> {
                    val body = ("{\"ok\":true,\"port\":$port,\"clients\":${clients.size}," +
                        "\"live\":${AppState.audioFlowing.value},\"drops\":$drops}").toByteArray()
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

    private fun stream(socket: Socket, out: OutputStream, peer: String) {
        val client = Client(socket, peer)
        val burst: List<ByteArray>
        synchronized(history) {
            burst = history.toList()
            clients.add(client)
        }
        AppState.clients.value = clients.size
        AppState.lastClient.value = peer
        try {
            // Every client joins mid-stream and needs its own header.
            out.write(wavHeader())
            for (chunk in burst) {
                out.write(chunk)
                client.sent += chunk.size
            }
            while (running) {
                val data = client.queue.poll(30, TimeUnit.SECONDS) ?: break
                out.write(data)
                client.sent += data.size
            }
        } finally {
            clients.remove(client)
            AppState.clients.value = clients.size
        }
    }

    private fun head(out: OutputStream, code: Int, type: String, length: Long?) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $type\r\n")
        if (length != null) sb.append("Content-Length: $length\r\n")
        sb.append("Cache-Control: no-cache, no-store\r\n")
        sb.append("Pragma: no-cache\r\n")
        sb.append("Connection: close\r\n")
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
