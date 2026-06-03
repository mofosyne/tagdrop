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
 *   - Null (0xf6)
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

    private fun encodeUInt(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeHead(out, 0, n)
        return out.toByteArray()
    }

    private fun encodeValue(v: Any): ByteArray {
        val out = ByteArrayOutputStream()
        when (v) {
            is Int       -> writeHead(out, 0, v.toLong())
            is Long      -> writeHead(out, 0, v)
            is ByteArray -> { writeHead(out, 2, v.size.toLong()); out.write(v) }
            is String    -> {
                val bytes = v.toByteArray(Charsets.UTF_8)
                writeHead(out, 3, bytes.size.toLong())
                out.write(bytes)
            }
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
        val head = stream.read()
        require(head ushr 5 == 5) { "Expected CBOR map (major 5), got major ${head ushr 5}" }
        val count = readArg(head and 0x1F, stream).toInt()
        return readMapFromStream(stream, count)
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
        val b     = stream.read()
        val major = b ushr 5
        val arg   = readArg(b and 0x1F, stream)
        return when (major) {
            0 -> arg
            2 -> ByteArray(arg.toInt()).also { stream.read(it) }
            3 -> ByteArray(arg.toInt()).also { stream.read(it) }.toString(Charsets.UTF_8)
            4 -> List(arg.toInt()) { readValue(stream) }
            5 -> readMapFromStream(stream, arg.toInt())
            7 -> if (b == 0xF6) Unit else throw IllegalArgumentException("Unsupported simple value 0x${b.toString(16)}")
            else -> throw IllegalArgumentException("Unsupported CBOR major type $major")
        }
    }

    private fun readArg(info: Int, stream: ByteArrayInputStream): Long = when (info) {
        in 0..23 -> info.toLong()
        24       -> stream.read().toLong()
        25       -> { var n = 0L; repeat(2) { n = (n shl 8) or stream.read().toLong() }; n }
        26       -> { var n = 0L; repeat(4) { n = (n shl 8) or stream.read().toLong() }; n }
        27       -> { var n = 0L; repeat(8) { n = (n shl 8) or stream.read().toLong() }; n }
        else     -> throw IllegalArgumentException("Unsupported CBOR additional info: $info")
    }
}
