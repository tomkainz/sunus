package de.tomkainz.sonoslive

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A service found by its Bonjour name. */
data class MdnsService(
    val type: String,
    val instance: String,
    val host: String,
    val port: Int,
    val ip: String,
    val txt: Map<String, String>
)

/**
 * Asks the network which devices offer a service. Written out instead of Android's
 * NsdManager, which resolves one service at a time on the older versions this app
 * still runs on, and which cannot be tested without a phone.
 */
object Mdns {

    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353
    private const val PTR = 12
    private const val TXT = 16
    private const val A = 1
    private const val SRV = 33
    private const val IN = 1

    private val LABEL_END = Regex("(?<!\\\\)\\.")

    data class Record(val name: String, val type: Int, val data: Any)

    /** Lets the answer come straight back, for responders that do. */
    private const val UNICAST_RESPONSE = 0x8000

    fun query(names: List<Pair<String, Int>>, unicast: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v ushr 8)
            out.write(v)
        }
        u16(0) // id
        u16(0) // flags
        u16(names.size)
        u16(0)
        u16(0)
        u16(0)
        for ((name, type) in names) {
            for (label in name.trimEnd('.').split(LABEL_END)) {
                val bytes = label.replace("\\.", ".").toByteArray()
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            u16(type)
            u16(if (unicast) IN or UNICAST_RESPONSE else IN)
        }
        return out.toByteArray()
    }

    /** All records of a DNS message. Throws on a malformed one. */
    fun parse(m: ByteArray, length: Int = m.size): List<Record> {
        var at = 0
        fun u16(): Int = ((m[at++].toInt() and 0xFF) shl 8) or (m[at++].toInt() and 0xFF)
        fun name(from: Int): Pair<String, Int> {
            val parts = mutableListOf<String>()
            var p = from
            var end = -1
            var jumps = 0
            while (true) {
                val n = m[p].toInt() and 0xFF
                if (n == 0) {
                    if (end < 0) end = p + 1
                    break
                }
                if (n and 0xC0 == 0xC0) {
                    if (end < 0) end = p + 2
                    p = ((n and 0x3F) shl 8) or (m[p + 1].toInt() and 0xFF)
                    check(++jumps < 32) { "DNS name loop" }
                    continue
                }
                // "Buero 1.OG" is one label; escaped as DNS writes it, so it can be asked for again.
                parts += String(m, p + 1, n).replace(".", "\\.")
                p += n + 1
            }
            return parts.joinToString(".") to end
        }
        at = 4
        val questions = u16()
        val answers = u16() + u16() + u16()
        repeat(questions) {
            at = name(at).second + 4
        }
        val records = mutableListOf<Record>()
        repeat(answers) {
            val (owner, next) = name(at)
            at = next
            val type = u16()
            at += 2 // class
            at += 4 // ttl
            val size = u16()
            val start = at
            check(start + size <= length) { "DNS record past the end" }
            when (type) {
                PTR -> records += Record(owner, PTR, name(start).first)
                SRV -> {
                    at += 4
                    val port = u16()
                    records += Record(owner, SRV, port to name(at).first)
                }
                TXT -> {
                    val map = LinkedHashMap<String, String>()
                    var p = start
                    while (p < start + size) {
                        val n = m[p].toInt() and 0xFF
                        val s = String(m, p + 1, n)
                        if (s.isNotEmpty()) map[s.substringBefore('=').lowercase()] = s.substringAfter('=', "")
                        p += n + 1
                    }
                    records += Record(owner, TXT, map)
                }
                A -> if (size == 4) {
                    records += Record(
                        owner, A,
                        InetAddress.getByAddress(m.copyOfRange(start, start + 4)).hostAddress.orEmpty()
                    )
                }
            }
            at = start + size
        }
        return records
    }

    /**
     * What was heard so far as services. An instance with an address and a port counts;
     * [wanted] names follow-up questions for anything still missing.
     */
    class Collector(private val types: List<String>) {
        private val ptr = LinkedHashMap<String, MutableSet<String>>()
        private val srv = HashMap<String, Pair<Int, String>>()
        private val txt = HashMap<String, Map<String, String>>()
        private val a = HashMap<String, String>()

        @Suppress("UNCHECKED_CAST")
        fun add(records: List<Record>) {
            for (r in records) when (r.type) {
                PTR -> if (types.any { it.equals(r.name, ignoreCase = true) }) {
                    ptr.getOrPut(r.name.lowercase()) { LinkedHashSet() } += r.data as String
                }
                SRV -> srv[r.name.lowercase()] = r.data as Pair<Int, String>
                TXT -> txt[r.name.lowercase()] = r.data as Map<String, String>
                A -> a[r.name.lowercase()] = r.data as String
            }
        }

        fun wanted(): List<Pair<String, Int>> = buildList {
            for (instance in ptr.values.flatten()) {
                val key = instance.lowercase()
                if (key !in srv) add(instance to SRV)
                if (key !in txt) add(instance to TXT)
                srv[key]?.second?.let { if (it.lowercase() !in a) add(it to A) }
            }
        }

        fun services(): List<MdnsService> = ptr.flatMap { (type, instances) ->
            instances.mapNotNull { instance ->
                val key = instance.lowercase()
                val (port, host) = srv[key] ?: return@mapNotNull null
                val ip = a[host.lowercase()] ?: return@mapNotNull null
                val label = instance.removeSuffix(".$type").let {
                    if (it.length == instance.length) instance.substringBefore("._") else it
                }.replace("\\.", ".")
                MdnsService(type, label, host, port, ip, txt[key].orEmpty())
            }
        }
    }

    /** Multicast must leave on the Wi-Fi, not on whatever the default route is. */
    private fun MulticastSocket.useInterface(localIp: String?) {
        val address = localIp?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } ?: return
        runCatching { NetworkInterface.getByInetAddress(address)?.let { networkInterface = it } }
    }

    /** Blocks for [timeoutMs]. The caller holds the multicast lock. */
    fun browse(types: List<String>, localIp: String?, timeoutMs: Long = 2800): List<MdnsService> {
        val collector = Collector(types)
        val group = InetAddress.getByName(GROUP)
        val questions = types.map { it to PTR }
        // Asked from a free port, a responder answers that port. Asked from the mDNS port, it
        // answers the group. Doing both gets past firewalls that only let one of them in.
        val listener = runCatching {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                useInterface(localIp)
                joinGroup(InetSocketAddress(group, PORT), networkInterface)
                soTimeout = 100
            }
        }.getOrNull()
        val asker = MulticastSocket(0).apply {
            useInterface(localIp)
            timeToLive = 255
            soTimeout = 100
        }
        val lock = Any()
        fun receive(socket: MulticastSocket, until: Long) {
            val buffer = ByteArray(9000)
            while (System.nanoTime() / 1_000_000 < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val records = runCatching { parse(buffer, packet.length) }.getOrNull() ?: continue
                    synchronized(lock) { collector.add(records) }
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    return
                }
            }
        }
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        val heard = listener?.let { Thread { receive(it, deadline) }.apply { isDaemon = true; start() } }
        try {
            fun send(q: List<Pair<String, Int>>) {
                if (q.isEmpty()) return
                // A DNS message holds many questions, but not an unlimited number.
                q.chunked(24).forEach { part ->
                    val direct = query(part)
                    runCatching { asker.send(DatagramPacket(direct, direct.size, group, PORT)) }
                    listener?.let {
                        val shared = query(part, unicast = false)
                        runCatching { it.send(DatagramPacket(shared, shared.size, group, PORT)) }
                    }
                }
            }
            var round = 0
            while (System.nanoTime() / 1_000_000 < deadline) {
                // Multicast often gets lost on Wi-Fi, so ask again.
                if (round % 6 == 0) send(questions)
                send(synchronized(lock) { collector.wanted() })
                round++
                receive(asker, minOf(deadline, System.nanoTime() / 1_000_000 + 200))
            }
            heard?.join(500)
        } finally {
            runCatching { asker.close() }
            runCatching { listener?.close() }
        }
        return synchronized(lock) { collector.services() }
    }
}
