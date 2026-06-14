package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Minimal CBOR encoder/decoder (RFC 8949) covering only the TagDrop-required subset:
 *   - Unsigned integers (major type 0)
 *   - Byte strings    (major type 2)
 *   - Text strings    (major type 3)
 *   - Arrays          (major type 4)
 *   - Maps with integer keys (major type 5)
 *   - Booleans        (major type 7, additional info 20/21)
 *   - Float64         (major type 7, additional info 27)
 *   - Null (0xf6)
 *
 * Also supports top-level CBOR Sequences (RFC 8742) — concatenated CBOR data
 * items with no enclosing array or map — used to prefix TagDrop's payload map
 * with a version/type envelope (see TagDropCodec).
 *
 * Values over 2^32 are not supported. This keeps the implementation compact
 * with no library dependency while covering everything TagDrop payloads need.
 */
object MiniCbor {

    /** Wraps a nested CBOR map for use as a value inside arrays or other maps. */
    class CborMap(val pairs: List<Pair<Int, Any?>>)

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encode a map from integer keys to values. Null values are silently omitted,
     * which is how optional fields (hint, filename, set, slug) are handled.
     */
    fun encodeMap(pairs: List<Pair<Int, Any?>>): ByteArray {
        val nonNull = pairs.filter { it.second != null }
        val out = ByteArrayOutputStream()
        writeHead(out, 5, nonNull.size.toLong())
        for ((k, v) in nonNull) {
            out.write(encodeUInt(k.toLong()))
            out.write(encodeValue(v!!))
        }
        return out.toByteArray()
    }

    /** Encodes a single CBOR unsigned integer (major type 0) — e.g. a sequence envelope item. */
    fun encodeUInt(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeHead(out, 0, n)
        return out.toByteArray()
    }

    private fun encodeValue(v: Any): ByteArray {
        val out = ByteArrayOutputStream()
        when (v) {
            is Int       -> writeHead(out, 0, v.toLong())
            is Long      -> writeHead(out, 0, v)
            is Boolean   -> out.write(if (v) 0xF5 else 0xF4) // true (0xf5) / false (0xf4)
            is ByteArray -> { writeHead(out, 2, v.size.toLong()); out.write(v) }
            is String    -> {
                val bytes = v.toByteArray(Charsets.UTF_8)
                writeHead(out, 3, bytes.size.toLong())
                out.write(bytes)
            }
            is Double    -> writeFloat64(out, v)
            is List<*>   -> {
                writeHead(out, 4, v.size.toLong())
                for (item in v) out.write(encodeValue(item!!))
            }
            is CborMap   -> {
                val nonNull = v.pairs.filter { it.second != null }
                writeHead(out, 5, nonNull.size.toLong())
                for ((k, kv) in nonNull) {
                    out.write(encodeUInt(k.toLong()))
                    out.write(encodeValue(kv!!))
                }
            }
            else -> throw IllegalArgumentException("Unsupported CBOR value type: ${v::class.simpleName}")
        }
        return out.toByteArray()
    }

    /** Always written as major type 7, additional info 27 (8-byte IEEE 754 double), regardless of value. */
    private fun writeFloat64(out: ByteArrayOutputStream, v: Double) {
        out.write((7 shl 5) or 27)
        val bits = java.lang.Double.doubleToLongBits(v)
        repeat(8) { i -> out.write((bits ushr (56 - i * 8)).toInt() and 0xFF) }
    }

    private fun writeHead(out: ByteArrayOutputStream, major: Int, n: Long) {
        val m = major shl 5
        when {
            n <= 23          -> out.write(m or n.toInt())
            n <= 0xFF        -> { out.write(m or 24); out.write(n.toInt()) }
            n <= 0xFFFF      -> { out.write(m or 25); out.write((n shr 8).toInt()); out.write(n.toInt()) }
            n <= 0xFFFFFFFFL -> {
                out.write(m or 26)
                repeat(4) { shift -> out.write((n shr (24 - shift * 8)).toInt()) }
            }
            else -> throw IllegalArgumentException("Value $n too large for 4-byte CBOR integer")
        }
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    /** Returns a Map<Int, Any> where Any is Long, ByteArray, String, List<Any>, or Map<Int, Any>. */
    fun decodeMap(bytes: ByteArray): Map<Int, Any> {
        val stream = ByteArrayInputStream(bytes)
        val head = readByte(stream)
        require(head ushr 5 == 5) { "Expected CBOR map (major 5), got major ${head ushr 5}" }
        val count = readArg(head and 0x1F, stream).toInt()
        return readMapFromStream(stream, count)
    }

    /**
     * Decodes a CBOR Sequence (RFC 8742): top-level CBOR data items concatenated with no
     * enclosing array, read until the input is exhausted. Item types follow [decodeMap]'s
     * value conventions (Long, ByteArray, String, List<Any>, Map<Int, Any>, Double).
     */
    fun decodeSequence(bytes: ByteArray): List<Any> {
        val stream = ByteArrayInputStream(bytes)
        val items = mutableListOf<Any>()
        while (stream.available() > 0) {
            items.add(readValue(stream))
        }
        return items
    }

    private fun readMapFromStream(stream: ByteArrayInputStream, count: Int): Map<Int, Any> =
        buildMap(count) {
            repeat(count) {
                val k = readValue(stream)
                val v = readValue(stream)
                if (k is Long) put(k.toInt(), v)
            }
        }

    private fun readValue(stream: ByteArrayInputStream): Any {
        val b     = readByte(stream)
        val major = b ushr 5
        val arg   = readArg(b and 0x1F, stream)
        return when (major) {
            0 -> arg
            2 -> readBytes(stream, arg.toInt())
            3 -> readBytes(stream, arg.toInt()).toString(Charsets.UTF_8)
            4 -> List(arg.toInt()) { readValue(stream) }
            5 -> readMapFromStream(stream, arg.toInt())
            7 -> when (b and 0x1F) {
                20 -> false                   // false (0xf4)
                21 -> true                    // true (0xf5)
                22 -> Unit                    // null (0xf6)
                27 -> Double.fromBits(arg)    // float64 (0xfb)
                else -> throw IllegalArgumentException("Unsupported simple value 0x${b.toString(16)}")
            }
            else -> throw IllegalArgumentException("Unsupported CBOR major type $major")
        }
    }

    private fun readArg(info: Int, stream: ByteArrayInputStream): Long = when (info) {
        in 0..23 -> info.toLong()
        24       -> readByte(stream).toLong()
        25       -> { var n = 0L; repeat(2) { n = (n shl 8) or readByte(stream).toLong() }; n }
        26       -> { var n = 0L; repeat(4) { n = (n shl 8) or readByte(stream).toLong() }; n }
        27       -> { var n = 0L; repeat(8) { n = (n shl 8) or readByte(stream).toLong() }; n }
        else     -> throw IllegalArgumentException("Unsupported CBOR additional info: $info")
    }

    private fun readByte(stream: ByteArrayInputStream): Int {
        val b = stream.read()
        require(b >= 0) { "Unexpected end of CBOR input" }
        return b
    }

    private fun readBytes(stream: ByteArrayInputStream, n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var totalRead = 0
        while (totalRead < n) {
            val read = stream.read(buf, totalRead, n - totalRead)
            if (read == -1) break
            totalRead += read
        }
        require(totalRead == n) { "Truncated CBOR byte string: expected $n bytes, got $totalRead" }
        return buf
    }
}
