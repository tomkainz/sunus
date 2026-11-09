package de.tomkainz.sonoslive.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Apple's binary property lists, as AirPlay's setup messages use them. Values are
 * Map, List, String, Long, Int, Double, Boolean and ByteArray.
 */
object Bplist {

    fun write(root: Any): ByteArray {
        val flat = ArrayList<Any>()
        fun collect(value: Any) {
            flat += value
            when (value) {
                is Map<*, *> -> value.forEach { (k, v) ->
                    collect(k!!)
                    collect(v!!)
                }
                is List<*> -> value.forEach { collect(it!!) }
            }
        }
        collect(root)

        val refSize = if (flat.size < 256) 1 else 2
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = IntArray(flat.size)

        // Children sit right behind their parent in the flat list, in order.
        fun size(value: Any): Int = 1 + when (value) {
            is Map<*, *> -> value.entries.sumOf { size(it.key!!) + size(it.value!!) }
            is List<*> -> value.sumOf { size(it!!) }
            else -> 0
        }

        fun marker(kind: Int, count: Int) {
            if (count < 15) {
                out.write(kind or count)
            } else {
                out.write(kind or 15)
                writeInt(out, count.toLong())
            }
        }

        fun ref(i: Int) {
            if (refSize == 2) out.write(i ushr 8)
            out.write(i)
        }

        flat.forEachIndexed { i, value ->
            offsets[i] = out.size()
            when (value) {
                is Boolean -> out.write(if (value) 0x09 else 0x08)
                is Int -> writeInt(out, value.toLong())
                is Long -> writeInt(out, value)
                is Double -> {
                    out.write(0x23)
                    out.write(ByteBuffer.allocate(8).putDouble(value).array())
                }
                is ByteArray -> {
                    marker(0x40, value.size)
                    out.write(value)
                }
                is String -> {
                    if (value.all { it.code < 128 }) {
                        marker(0x50, value.length)
                        out.write(value.toByteArray(Charsets.US_ASCII))
                    } else {
                        marker(0x60, value.length)
                        out.write(value.toByteArray(Charsets.UTF_16BE))
                    }
                }
                is List<*> -> {
                    marker(0xA0, value.size)
                    var child = i + 1
                    value.forEach {
                        ref(child)
                        child += size(it!!)
                    }
                }
                is Map<*, *> -> {
                    marker(0xD0, value.size)
                    var child = i + 1
                    val keys = ArrayList<Int>()
                    val values = ArrayList<Int>()
                    value.forEach { (k, v) ->
                        keys += child
                        child += size(k!!)
                        values += child
                        child += size(v!!)
                    }
                    keys.forEach(::ref)
                    values.forEach(::ref)
                }
                else -> error("Cannot write ${value.javaClass}")
            }
        }

        val tableAt = out.size()
        val offsetSize = if (tableAt < 256) 1 else if (tableAt < 65536) 2 else 4
        offsets.forEach { o ->
            for (shift in (offsetSize - 1) downTo 0) out.write(o ushr (shift * 8))
        }
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        out.write(ByteBuffer.allocate(24).putLong(flat.size.toLong()).putLong(0).putLong(tableAt.toLong()).array())
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Long) {
        when (value) {
            in 0..0xFF -> {
                out.write(0x10)
                out.write(value.toInt())
            }
            in 0..0xFFFF -> {
                out.write(0x11)
                out.write(ByteBuffer.allocate(2).putShort(value.toShort()).array())
            }
            in 0..0xFFFFFFFFL -> {
                out.write(0x12)
                out.write(ByteBuffer.allocate(4).putInt(value.toInt()).array())
            }
            else -> {
                out.write(0x13)
                out.write(ByteBuffer.allocate(8).putLong(value).array())
            }
        }
    }

    fun read(data: ByteArray): Any? {
        require(data.size > 40 && String(data, 0, 6) == "bplist") { "Not a binary plist" }
        val trailer = ByteBuffer.wrap(data, data.size - 32, 32)
        trailer.position(trailer.position() + 6)
        val offsetSize = trailer.get().toInt()
        val refSize = trailer.get().toInt()
        val count = trailer.long.toInt()
        val top = trailer.long.toInt()
        val table = trailer.long.toInt()
        fun uint(at: Int, size: Int): Long {
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
            return v
        }
        fun offset(i: Int) = uint(table + i * offsetSize, offsetSize).toInt()
        fun obj(i: Int, depth: Int): Any? {
            require(i in 0 until count && depth < 32) { "Bad plist reference" }
            val at = offset(i)
            val head = data[at].toInt() and 0xFF
            val kind = head and 0xF0
            var n = head and 0x0F
            var body = at + 1
            if (n == 15 && kind in setOf(0x40, 0x50, 0x60, 0xA0, 0xD0)) {
                val size = 1 shl (data[body].toInt() and 0x0F)
                n = uint(body + 1, size).toInt()
                body += 1 + size
            }
            return when (kind) {
                0x00 -> when (head) {
                    0x08 -> false
                    0x09 -> true
                    else -> null
                }
                0x10 -> uint(body, 1 shl n)
                0x20 -> if (n == 3) ByteBuffer.wrap(data, body, 8).double else ByteBuffer.wrap(data, body, 4).float.toDouble()
                0x30 -> ByteBuffer.wrap(data, body, 8).double
                0x40 -> data.copyOfRange(body, body + n)
                0x50 -> String(data, body, n, Charsets.US_ASCII)
                0x60 -> String(data, body, n * 2, Charsets.UTF_16BE)
                0x80 -> uint(body, n + 1)
                0xA0 -> (0 until n).map { obj(uint(body + it * refSize, refSize).toInt(), depth + 1) }
                0xD0 -> {
                    val map = LinkedHashMap<String, Any?>()
                    for (k in 0 until n) {
                        val key = obj(uint(body + k * refSize, refSize).toInt(), depth + 1)
                        map[key.toString()] = obj(uint(body + (n + k) * refSize, refSize).toInt(), depth + 1)
                    }
                    map
                }
                else -> null
            }
        }
        return obj(top, 0)
    }
}
