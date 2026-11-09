package de.tomkainz.sonoslive.airplay

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class RtspResponse(val code: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String) = headers[name.lowercase()]
    val ok get() = code in 200..299
}

class RtspError(val code: Int, what: String) : IOException("$what: RTSP $code")

/** The encrypted form AirPlay wraps around its messages once pairing is done: blocks of at most 1024 bytes. */
class HapStream(private val input: InputStream, writeKey: ByteArray, readKey: ByteArray) {
    private val writer = AirPlayCipher(writeKey)
    private val reader = AirPlayCipher(readKey)
    private var writeCount = 0L
    private var readCount = 0L
    private var buffer = ByteArray(0)
    private var bufferAt = 0

    fun seal(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val n = minOf(BLOCK, data.size - at)
            val length = byteArrayOf(n.toByte(), (n ushr 8).toByte())
            out.write(length)
            out.write(writer.encrypt(writeCount++, data, at, n, length))
            at += n
        }
        return out.toByteArray()
    }

    fun read(): Int {
        while (bufferAt >= buffer.size) {
            val d = DataInputStream(input)
            val length = ByteArray(2)
            d.readFully(length)
            val n = (length[0].toInt() and 0xFF) or ((length[1].toInt() and 0xFF) shl 8)
            if (n > BLOCK * 2) throw IOException("AirPlay block of $n bytes")
            val block = ByteArray(n + TAG)
            d.readFully(block)
            buffer = reader.decrypt(readCount++, block, length)
            bufferAt = 0
        }
        return buffer[bufferAt++].toInt() and 0xFF
    }

    companion object {
        private const val BLOCK = 1024
        private const val TAG = 16
    }
}

/**
 * RTSP as AirPlay speaks it: one request at a time on one connection, in the clear
 * until pairing is done. HomePods since OS 27 refuse anything without a User-Agent
 * they like, even before pairing.
 */
class RtspClient(host: String, port: Int, private val userAgent: String = USER_AGENT) : Closeable {

    private val socket = Socket()
    private val output get() = socket.getOutputStream()
    private val input get() = socket.getInputStream()
    private var cipher: HapStream? = null
    private var cseq = 0
    private val dacpId = java.util.UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
    private val activeRemote = (System.nanoTime() and 0x7FFFFFFF).toString()

    val localIp: String

    var timeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    init {
        socket.connect(InetSocketAddress(host, port), 4000)
        socket.soTimeout = 8000
        socket.tcpNoDelay = true
        socket.keepAlive = true
        localIp = socket.localAddress.hostAddress.orEmpty()
    }

    fun encrypt(writeKey: ByteArray, readKey: ByteArray) {
        cipher = HapStream(input, writeKey, readKey)
    }

    @Synchronized
    fun request(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null
    ): RtspResponse {
        val text = StringBuilder("$method $uri RTSP/1.0\r\n")
        text.append("CSeq: ${++cseq}\r\n")
        text.append("User-Agent: $userAgent\r\n")
        text.append("DACP-ID: $dacpId\r\n")
        text.append("Active-Remote: $activeRemote\r\n")
        text.append("Client-Instance: $dacpId\r\n")
        headers.forEach { (k, v) -> text.append("$k: $v\r\n") }
        if (contentType != null) text.append("Content-Type: $contentType\r\n")
        text.append("Content-Length: ${body?.size ?: 0}\r\n\r\n")
        val plain = text.toString().toByteArray() + (body ?: ByteArray(0))
        val c = cipher
        output.write(if (c != null) c.seal(plain) else plain)
        output.flush()
        return readResponse()
    }

    private fun readByte(): Int {
        val c = cipher
        val b = if (c != null) c.read() else input.read()
        if (b < 0) throw IOException("The speaker closed the connection")
        return b
    }

    private fun readResponse(): RtspResponse {
        val status = readLine()
        val parts = status.split(" ", limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an RTSP answer: $status")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':', "").trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) throw IOException("RTSP body of $length bytes")
        val body = ByteArray(length) { readByte().toByte() }
        return RtspResponse(code, parts.getOrElse(2) { "" }, headers, body)
    }

    private fun readLine(): String {
        val out = StringBuilder()
        while (true) {
            val b = readByte()
            if (b == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(b.toChar())
            if (out.length > 8192) throw IOException("RTSP line too long")
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** Worked on HomePod OS 27 for other senders; see the notes on the User-Agent gate. */
        const val USER_AGENT = "AirPlay/550.10"
        private const val MAX_BODY = 1 shl 20
    }
}
