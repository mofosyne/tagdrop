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

    /** Encodes a single CBOR byte string (major type 2) — e.g. a sequence envelope item. */
    fun encodeBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeHead(out, 2, bytes.size.toLong())
        out.write(bytes)
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
        repeat(8) { i -> out.write((bits ushr (56 - (i * 8))).toInt() and 0xFF) }
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

    /**
     * Decodes exactly [count] top-level CBOR data items from the start of [bytes] (per
     * [decodeMap]'s value conventions), then returns whatever bytes remain unconsumed.
     * Used to split a TagDrop envelope+payload sequence (3 items, see TagDropCodec) from
     * any raw trailing bytes it may carry — a hidden override-map blob (SPEC §9).
     */
    fun decodeSequencePrefix(bytes: ByteArray, count: Int): Pair<List<Any>, ByteArray> {
        val stream = ByteArrayInputStream(bytes)
        val items = List(count) { readValue(stream) }
        return items to stream.readBytes()
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
            1 -> -(arg + 1)                   // negative integer
            2 -> readBytes(stream, arg.toInt())
            3 -> readBytes(stream, arg.toInt()).toString(Charsets.UTF_8)
            4 -> List(arg.toInt()) { readValue(stream) }
            5 -> readMapFromStream(stream, arg.toInt())
            6 -> "<tag $arg>(${readValue(stream)})"  // tagged value
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

    // ── Debug ─────────────────────────────────────────────────────────────────

    /**
     * Classic 16-bytes-per-line hex dump. [startOffset] is added to the printed address so a
     * partial dump (the unparsed tail) still shows accurate file offsets.
     */
    private fun hexDump(bytes: ByteArray, startOffset: Int = 0): String = buildString {
        val w = 16
        for (base in bytes.indices step w) {
            val end = minOf(base + w, bytes.size)
            append("%08x  ".format(startOffset + base))
            for (i in base until base + w) {
                append(if (i < end) "%02x ".format(bytes[i]) else "   ")
                if (i - base == 7) append(' ')
            }
            append(' ')
            for (i in base until end) {
                val c = bytes[i].toInt() and 0xFF
                append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            appendLine()
        }
    }

    /**
     * Pretty-prints arbitrary bytes as a generic CBOR Sequence, for inspecting content whose
     * structure isn't known ahead of time -- e.g. a found tag/QR of uncertain origin, possibly
     * truncated or damaged. Unlike [TagDropCodec.describeCbor] (which expects TagDrop's own fixed
     * version/type/part_meta/sector_bytes envelope and names its specific keys), this has no
     * notion of TagDrop semantics -- it just walks whatever items it can decode.
     *
     * Best-effort: items are decoded one at a time, so a corrupt or truncated item only ends the
     * walk from that point on -- every item decoded before it is still shown, followed by a hex
     * dump of whatever bytes remain unparsed, rather than discarding everything (this is purely a
     * discovery aid, not a correctness check).
     */
    /**
     * Scans [bytes] for CBOR items, decoding whatever it can and dumping unrecognised stretches as
     * hex. Unlike a strict decoder, a failure at position N does not abort the whole walk — it adds
     * byte N to the current "unrecognised" run and retries from N+1, so CBOR structures embedded in
     * binary garbage (e.g. a non-CBOR file that happens to contain a CBOR map partway through) are
     * still surfaced. The full hex dump is always printed first so the raw bytes are immediately
     * visible even when the CBOR interpretation is entirely garbage.
     */
    fun describeSequence(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(empty)"
        return buildString {
            // Full hex dump first — always immediately visible.
            appendLine("── hex ────────────────────────────────────────────────")
            append(hexDump(bytes))
            appendLine()
            appendLine("── CBOR scan ──────────────────────────────────────────")

            var offset = 0
            var itemIndex = 0
            val skipped = mutableListOf<Byte>() // consecutive bytes that couldn't be decoded

            fun flushSkipped() {
                if (skipped.isEmpty()) return
                val start = offset - skipped.size
                appendLine()
                appendLine("  ⚠ ${skipped.size} unrecognised byte(s) at 0x${"%x".format(start)}:")
                append("  ")
                append(hexDump(skipped.toByteArray(), start).trimEnd().replace("\n", "\n  "))
                appendLine()
                skipped.clear()
            }

            while (offset < bytes.size) {
                val slice = ByteArrayInputStream(bytes, offset, bytes.size - offset)
                val before = slice.available()
                val item = runCatching { readValue(slice) }.getOrNull()
                val consumed = before - slice.available()

                if (item != null && consumed > 0) {
                    flushSkipped()
                    appendLine()
                    appendLine("  ── item $itemIndex  offset 0x${"%x".format(offset)}  ($consumed byte(s)) ──")
                    describeValue(null, item, 1, this)
                    offset += consumed
                    itemIndex++
                } else {
                    skipped.add(bytes[offset])
                    offset++
                }
            }
            flushSkipped()
            if (itemIndex == 0) appendLine("  (no CBOR items found in ${bytes.size} bytes)")
        }
    }

    private fun describeValue(key: Any?, value: Any?, indent: Int, out: StringBuilder) {
        val pad = "  ".repeat(indent)
        val prefix = if (key != null) "$pad$key: " else pad
        when (value) {
            null, Unit -> out.appendLine("${prefix}null")
            is ByteArray -> out.appendLine("$prefix${value.joinToString(" ") { "%02x".format(it) }} (${value.size} bytes)")
            is String -> out.appendLine("$prefix\"$value\"")
            is Map<*, *> -> {
                out.appendLine("$prefix{")
                for ((k, v) in value) describeValue(k, v, indent + 1, out)
                out.appendLine("$pad}")
            }
            is List<*> -> {
                out.appendLine("$prefix[")
                for (item in value) describeValue(null, item, indent + 1, out)
                out.appendLine("$pad]")
            }
            else -> out.appendLine("$prefix$value")
        }
    }
}
