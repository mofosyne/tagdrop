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
     * Encode a map from integer keys to values, in the order given (callers that need
     * ascending-by-key order, e.g. [encodeRecord], sort before calling this). Null values are
     * silently omitted, which is how optional fields (hint, filename, set, slug) are handled.
     */
    fun encodeMap(pairs: List<Pair<Int, Any?>>): ByteArray {
        val nonNull = pairs.filter { it.second != null }
        val out = ByteArrayOutputStream()
        writeHead(out, 5, nonNull.size.toLong())
        for ((k, v) in nonNull) {
            out.write(encodeKey(k))
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

    /**
     * Encodes a CBOR map/field key: a non-negative uint (major type 0) for an ordinary
     * Type-specific key, or — for a QDEF Common Field Key (QDEF-SPEC.md §3.6, always
     * negative, always odd/optional) — a CBOR negative integer (major type 1, RFC 8949
     * §3.1: stores `-(n+1)` as its argument).
     */
    private fun encodeKey(k: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (k >= 0) writeHead(out, 0, k.toLong()) else writeHead(out, 1, (-k - 1).toLong())
        return out.toByteArray()
    }

    /**
     * RFC 8949 §4.2.1's core deterministic-encoding map-key order (QDEF-SPEC.md §3.4
     * requires it of every Record Map): shorter encoded key first; same-length keys compare
     * bytewise. NOT the same as ascending integer value once negative keys are involved — a
     * negative key (major type 1) always encodes to a *larger* raw byte than a same-magnitude
     * non-negative key (major type 0), so e.g. `0` sorts before `-1` here despite `-1 < 0`.
     */
    private val CANONICAL_KEY_BYTES_ORDER = Comparator<ByteArray> { a, b ->
        if (a.size != b.size) a.size - b.size
        else {
            var i = 0
            while (i < a.size && a[i] == b[i]) i++
            if (i == a.size) 0 else (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        }
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

    /**
     * Re-encodes [mapBytes] (a definite-length CBOR map, major type 5, starting at its own
     * head byte) with a trailing run of pairs whose key is in [trailingKeysToStrip] removed —
     * SPEC §10's "signing happens last" convention for Verified Authorship: the encoder always
     * writes keys 32/33/34/35/36 as the last pairs in `core_meta_item`/`bulky_meta_item`, so a
     * verifier can reconstruct exactly what an unsigned payload's bytes would have been by
     * trimming that trailing run and rewriting the map header's pair count.
     *
     * This operates on raw bytes rather than decoding to a map and re-encoding, because CBOR
     * map field order here isn't sorted/canonical (see the field lists in TagDropCodec) and a
     * semantic re-encode could silently reorder surviving fields — this must byte-for-byte
     * match the original pre-signature stream, or SHA-256 over it won't match what was signed.
     * If the input isn't honestly encoded (a stripped key reappears after a survivor, i.e. the
     * trailing run is interrupted), only the pairs before the *first* stripped key are kept —
     * this can only make the recomputed hash (and thus signature verification) fail closed on
     * such input, never succeed on tampered content, since a genuine signer's own encoder never
     * produces that shape.
     *
     * Returns [mapBytes] unchanged if none of [trailingKeysToStrip] are present.
     */
    fun stripTrailingKeys(mapBytes: ByteArray, trailingKeysToStrip: Set<Int>): ByteArray {
        val stream = ByteArrayInputStream(mapBytes)
        val head = readByte(stream)
        require(head ushr 5 == 5) { "Expected CBOR map (major 5), got major ${head ushr 5}" }
        val count = readArg(head and 0x1F, stream).toInt()
        val headerLen = mapBytes.size - stream.available()

        var survivingCount = 0
        var cutOffset = mapBytes.size
        var foundCut = false
        repeat(count) {
            val pairStart = mapBytes.size - stream.available()
            val key = readValue(stream)
            readValue(stream) // value bytes only need to be skipped, not interpreted
            if (!foundCut) {
                if (key is Long && key.toInt() in trailingKeysToStrip) {
                    cutOffset = pairStart
                    foundCut = true
                } else {
                    survivingCount++
                }
            }
        }
        if (!foundCut) return mapBytes
        val out = ByteArrayOutputStream()
        writeHead(out, 5, survivingCount.toLong())
        out.write(mapBytes, headerLen, cutOffset - headerLen)
        return out.toByteArray()
    }

    /**
     * Raw major-4 CBOR array encoding of [items] — the array's bytes UNWRAPPED. Mirrors the JS
     * reference's `cborArrayBytes` (used for QDEF Record fields whose value is an array of
     * sub-maps, e.g. Paper-Body's `files`/`related`, SPEC §3.4's field-value-shape rule: arrays
     * live inside a byte-string-encoded CBOR array, not bare at the Record level).
     *
     * Returns the array bytes UNWRAPPED: passing the result as a map value elsewhere is safe
     * without any extra step, because [encodeValue]'s `ByteArray` branch already wraps any
     * `ByteArray` as a byte string (major type 2) when used as a field value — wrapping here too
     * would double that header and corrupt the field on decode.
     */
    fun encodeArrayBytes(items: List<CborMap>): ByteArray {
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(encodeValue(item))
        return out.toByteArray()
    }

    // ── QDEF array-wrapped Records (QDEF-SPEC.md §3.1, SPEC.md v9 §2, v11 §2) ──────────
    // Every Record is its own self-delimited CBOR array, [typeId, map?, payload?, subrecord*]
    // — not a bare typeId-then-map pair. The map is omitted entirely when every field is
    // null (saves its header byte — e.g. Compress Wrapper, which has no fields of its own
    // once its one value moves to the payload slot). The payload slot, when present, is
    // always the item immediately after the map (or after typeId, if no map) that is NOT
    // itself a CBOR array — an array in that position is unconditionally subrecord 0
    // instead (QDEF-SPEC.md §3.1: payload can never be array-shaped, precisely so this is
    // unambiguous with no marker needed). TagDrop only ever uses a byte-string payload
    // (Compress Wrapper's deflated bytes, Media Payload's content), never text/scalar/map/
    // tag, so [encodeRecord]/[DecodedRecord.payload] are narrowly typed to ByteArray rather
    // than the full general shape QDEF allows. `subrecords` are already-encoded
    // `[typeId, ...]` byte sequences (each itself built by a nested encodeRecord call),
    // spliced in as their own array items. Mirrors the JS reference's cborRecord/craw
    // pattern (tools/generator/index.html).

    /**
     * Encodes a QDEF Record: `[typeId, map?, payload?, subrecord*]` as one CBOR array (major
     * type 4). Fields sort by RFC 8949 §4.2.1 canonical/deterministic order — by their
     * *encoded* key bytes (shorter first, then bytewise), not by plain integer value; see
     * [CANONICAL_KEY_BYTES_ORDER] (QDEF-SPEC.md §3.4). The map is omitted entirely only when
     * [fields] itself is empty — a static, per-call-site choice (e.g. Compress Wrapper, whose
     * one value moved entirely to the payload slot) — NOT whenever every declared field's
     * *value* happens to be null: a Type that always declares fields (Content Extension,
     * Paper-Preview/Body) must keep writing `{}` even when every optional field is unset this
     * time, since [stripKeys] and any signature-hash formula covering its bytes need a stable
     * map to exist. [payload], if non-null, is encoded as a CBOR byte string (major type 2) in
     * the payload slot.
     */
    fun encodeRecord(typeId: Int, fields: List<Pair<Int, Any?>>, subrecords: List<ByteArray> = emptyList(), payload: ByteArray? = null): ByteArray {
        val items = mutableListOf(encodeUInt(typeId.toLong()))
        if (fields.isNotEmpty()) items.add(encodeMap(fields.sortedWith(compareBy(CANONICAL_KEY_BYTES_ORDER) { encodeKey(it.first) })))
        if (payload != null) items.add(encodeBytes(payload))
        items.addAll(subrecords)
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(item)
        return out.toByteArray()
    }

    /** One decoded QDEF Record (QDEF-SPEC.md §3.1) — see [decodeRecordPrefix]. */
    data class DecodedRecord(
        /** The Record's own Type ID (the array's first element). */
        val typeId: Int,
        /** The Record's decoded field map — empty if the map was omitted from the wire entirely. */
        val record: Map<Int, Any>,
        /** This Record's own exact byte range — typeId + map + payload + all subrecords — what signature/group-id hashes are computed over. */
        val raw: ByteArray,
        /** The Record's own payload slot value (decoded byte-string content), or null if absent. */
        val payload: ByteArray?,
        /** Nested Records carried after this Record's own map/payload, each with [raw] relative to the ORIGINAL top-level bytes passed to the outermost [decodeRecordPrefix] call. */
        val subrecords: List<DecodedRecord>,
        /** Whatever follows this Record in the CBOR Sequence. */
        val trailing: ByteArray
    )

    /**
     * Determines where a Record's map/payload/subrecords fall within its own top-level item
     * [ranges] (`ranges[0]` is always typeId) — QDEF-SPEC.md §3.1's grammar, dispatched purely
     * by CBOR major type: the map (major 5) comes first if present; then, if the next item is
     * NOT an array (major 4), it's the payload; everything from the first remaining array
     * onward is subrecords. Shared by [decodeRecordPrefix]/[stripKeys]/[stripSubrecordType]/
     * [stripAllSubrecords] so all four agree on the same layout.
     */
    private class RecordLayout(val mapIdx: Int, val payloadIdx: Int, val subrecordsFrom: Int)

    private fun majorOf(bytes: ByteArray, pos: Int): Int = (bytes[pos].toInt() and 0xFF) ushr 5

    private fun layoutOf(bytes: ByteArray, ranges: List<Pair<Int, Int>>): RecordLayout {
        var idx = 1
        var mapIdx = -1
        if (idx < ranges.size && majorOf(bytes, ranges[idx].first) == 5) { mapIdx = idx; idx++ }
        var payloadIdx = -1
        if (idx < ranges.size && majorOf(bytes, ranges[idx].first) != 4) { payloadIdx = idx; idx++ }
        return RecordLayout(mapIdx, payloadIdx, idx)
    }

    /**
     * Decodes one QDEF Record (a self-delimited CBOR array, `[typeId, map?, payload?,
     * subrecord*]`) from the head of [bytes] — see [DecodedRecord]. Returns null if the head
     * of [bytes] isn't a well-formed Record.
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeRecordPrefix(bytes: ByteArray): DecodedRecord? = try {
        val cur = Cursor(bytes, 0)
        val ranges = itemRanges(cur, 4)
        if (ranges.isEmpty()) null else {
            val (typeIdStart, typeIdEnd) = ranges[0]
            val typeIdVal = decodeSequencePrefix(bytes.copyOfRange(typeIdStart, typeIdEnd), 1).first[0]
            val typeId = (typeIdVal as? Long)?.toInt()
            if (typeId == null) null else {
                val layout = layoutOf(bytes, ranges)
                val record: Map<Int, Any> = if (layout.mapIdx < 0) emptyMap() else {
                    val (mapStart, mapEnd) = ranges[layout.mapIdx]
                    decodeSequencePrefix(bytes.copyOfRange(mapStart, mapEnd), 1).first[0] as? Map<Int, Any> ?: throw IllegalStateException("bad map")
                }
                val payload: ByteArray? = if (layout.payloadIdx < 0) null else {
                    val (pStart, pEnd) = ranges[layout.payloadIdx]
                    decodeSequencePrefix(bytes.copyOfRange(pStart, pEnd), 1).first[0] as? ByteArray ?: throw IllegalStateException("bad payload")
                }
                val subrecords = mutableListOf<DecodedRecord>()
                var failed = false
                for (i in layout.subrecordsFrom until ranges.size) {
                    val (s, e) = ranges[i]
                    val sub = decodeRecordPrefix(bytes.copyOfRange(s, e))
                    if (sub == null) { failed = true; break }
                    subrecords.add(sub)
                }
                if (failed) null
                else DecodedRecord(typeId, record, bytes.copyOfRange(0, cur.pos), payload, subrecords, bytes.copyOfRange(cur.pos, bytes.size))
            }
        }
    } catch (e: Exception) { null }

    /**
     * Encodes 1+ already-encoded top-level Record byte-arrays as the QDEF self-delimited root
     * (QDEF-SPEC.md §2/§3.1): a single Record is returned as-is — its own array already IS the
     * root, "no Bundle indirection" — while two or more become subrecords of an implied, never-
     * transmitted Bundle (typeId 0, omitted): one more definite-length CBOR array wrapping them.
     * Bytes appended after the returned array are provably outside the container, and MUST be
     * tolerated by a decoder (SPEC §9's deniability feature) — [decodeRootBundle]'s own
     * self-delimiting length is what makes that safe without any app-specific record-count
     * foreknowledge.
     */
    fun encodeRootBundle(records: List<ByteArray>): ByteArray {
        require(records.isNotEmpty()) { "encodeRootBundle requires at least one Record" }
        if (records.size == 1) return records[0]
        val out = ByteArrayOutputStream()
        writeHead(out, 4, records.size.toLong())
        for (r in records) out.write(r)
        return out.toByteArray()
    }

    /**
     * Decodes the QDEF self-delimited root (QDEF-SPEC.md §2/§3.1): exactly one definite-length
     * CBOR array. If its first item is a uint, the whole array IS one Record's own item list
     * (delegated to [decodeRecordPrefix] exactly as for any other Record — the common
     * single-Record-root case, "no Bundle indirection"; that Record's own [DecodedRecord.trailing]
     * is precisely "bytes after the root array," intentionally never inspected by any caller here
     * or elsewhere — SPEC §9 requires trailing bytes be tolerated, not parsed or treated as an
     * error). Otherwise typeId defaults to `0` (Bundle, never transmitted for TagDrop's own Types)
     * and every item of the root array is itself a nested Record, decoded independently. Returns
     * null if the root isn't a well-formed, definite-length CBOR array, or if any of its items
     * fails to decode as a Record.
     */
    fun decodeRootBundle(bytes: ByteArray): List<DecodedRecord>? {
        return try {
            val ranges = itemRanges(Cursor(bytes, 0), 4)
            if (ranges.isEmpty()) null
            else if (majorOf(bytes, ranges[0].first) != 4) {
                decodeRecordPrefix(bytes)?.let { listOf(it) }
            } else {
                val out = mutableListOf<DecodedRecord>()
                for ((s, e) in ranges) out.add(decodeRecordPrefix(bytes.copyOfRange(s, e)) ?: return null)
                out
            }
        } catch (e: Exception) { null }
    }

    /**
     * Re-encodes [recordBytes] (a Record's own array, `[typeId, map?, payload?, subrecord*]`)
     * with every field-map pair whose key is in [keysToStrip] removed, regardless of position
     * — SPEC §2.2's fixed ascending key-encoding order means a higher-numbered field (e.g.
     * `source_url`, key 55) can legitimately sort after the ones being stripped (e.g. the
     * signature fields, 45/47/49), so this walks and keeps every surviving pair by its own
     * byte range rather than assuming the stripped keys form a truncatable trailing run. The
     * payload slot and any subrecords, if present, are carried through byte-for-byte,
     * untouched. Mirrors the JS reference's `stripKeys`. Requires a map to be present (every
     * caller strips keys from a Record Type that always carries required fields).
     */
    fun stripKeys(recordBytes: ByteArray, keysToStrip: Set<Int>): ByteArray {
        val cur = Cursor(recordBytes, 0)
        val ranges = itemRanges(cur, 4)
        val (typeIdStart, typeIdEnd) = ranges[0]
        val layout = layoutOf(recordBytes, ranges)
        require(layout.mapIdx >= 0) { "stripKeys requires a field map" }
        val (mapStart, _) = ranges[layout.mapIdx]
        val pairRanges = itemRanges(Cursor(recordBytes, mapStart), 5)
        val survivors = mutableListOf<ByteArray>()
        var i = 0
        while (i < pairRanges.size) {
            val (keyStart, _) = pairRanges[i]
            val (_, valueEnd) = pairRanges[i + 1]
            val key = readIntAt(recordBytes, keyStart)
            if (key.toInt() !in keysToStrip) survivors.add(recordBytes.copyOfRange(keyStart, valueEnd))
            i += 2
        }
        val mapOut = ByteArrayOutputStream()
        writeHead(mapOut, 5, survivors.size.toLong())
        for (s in survivors) mapOut.write(s)
        val items = mutableListOf(recordBytes.copyOfRange(typeIdStart, typeIdEnd), mapOut.toByteArray())
        if (layout.payloadIdx >= 0) { val (ps, pe) = ranges[layout.payloadIdx]; items.add(recordBytes.copyOfRange(ps, pe)) }
        for (i2 in layout.subrecordsFrom until ranges.size) { val (s, e) = ranges[i2]; items.add(recordBytes.copyOfRange(s, e)) }
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(item)
        return out.toByteArray()
    }

    /**
     * Re-encodes [recordBytes] (a Record's own array) with any *direct* subrecord whose own
     * typeId is [typeIdToStrip] removed entirely — not a field-map key strip, a whole-subrecord
     * removal. Used to build "what an unsigned Media Payload would contain" (no Content
     * Signature subrecord at all, SPEC.md v9 §10) from a placeholder-signed build or a
     * reassembled/decoded signed Media Payload. Mirrors the JS reference's `stripSubrecordType`.
     */
    fun stripSubrecordType(recordBytes: ByteArray, typeIdToStrip: Int): ByteArray {
        val cur = Cursor(recordBytes, 0)
        val ranges = itemRanges(cur, 4)
        val layout = layoutOf(recordBytes, ranges)
        val kept = ranges.drop(layout.subrecordsFrom).filter { (s, _) ->
            val subCur = Cursor(recordBytes, s)
            val subRanges = itemRanges(subCur, 4)
            readIntAt(recordBytes, subRanges[0].first).toInt() != typeIdToStrip
        }
        val items = mutableListOf(recordBytes.copyOfRange(ranges[0].first, ranges[0].second))
        if (layout.mapIdx >= 0) { val (s, e) = ranges[layout.mapIdx]; items.add(recordBytes.copyOfRange(s, e)) }
        if (layout.payloadIdx >= 0) { val (s, e) = ranges[layout.payloadIdx]; items.add(recordBytes.copyOfRange(s, e)) }
        for ((s, e) in kept) items.add(recordBytes.copyOfRange(s, e))
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(item)
        return out.toByteArray()
    }

    /**
     * Re-encodes [recordBytes] (a Record's own array) with ALL of its direct subrecords removed
     * — used to recover a Record's own bare bytes (e.g. a scanned Media Preview with Media
     * Payload nested inside it for wire transmission, single-code case, SPEC.md v9 §3.1a) for
     * hashing purposes (§10), where only the OWN Record's canonical bytes belong in the signed
     * message, not whatever happens to be nested inside it on the wire. The map and payload
     * slot (if present) are kept — only subrecords are dropped.
     */
    fun stripAllSubrecords(recordBytes: ByteArray): ByteArray {
        val cur = Cursor(recordBytes, 0)
        val ranges = itemRanges(cur, 4)
        val layout = layoutOf(recordBytes, ranges)
        val items = mutableListOf(recordBytes.copyOfRange(ranges[0].first, ranges[0].second))
        if (layout.mapIdx >= 0) { val (s, e) = ranges[layout.mapIdx]; items.add(recordBytes.copyOfRange(s, e)) }
        if (layout.payloadIdx >= 0) { val (s, e) = ranges[layout.payloadIdx]; items.add(recordBytes.copyOfRange(s, e)) }
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(item)
        return out.toByteArray()
    }

    // ── Low-level CBOR item-boundary walkers ────────────────────────────────────
    // QDEF-SPEC.md §3.1's array-wrapped Records need byte-range tracking through nested
    // subrecords — the generic recursive value-decoder above ([readValue]) doesn't track byte
    // positions, only values. Used by [decodeRecordPrefix]/[stripKeys]/[stripSubrecordType]/
    // [stripAllSubrecords] above. Mirrors the JS reference's cborSkipItem/cborItemRanges/
    // cborReadUint.

    private class Cursor(val bytes: ByteArray, var pos: Int)

    /** Advances [cur]'s position past exactly one well-formed CBOR item, recursing into arrays/maps as needed. */
    private fun skipItem(cur: Cursor) {
        fun rb(): Int = cur.bytes[cur.pos++].toInt() and 0xFF
        fun readArgLocal(info: Int): Long = when {
            info <= 23 -> info.toLong()
            info == 24 -> rb().toLong()
            info == 25 -> { var n = 0L; repeat(2) { n = (n shl 8) or rb().toLong() }; n }
            info == 26 -> { var n = 0L; repeat(4) { n = (n shl 8) or rb().toLong() }; n }
            info == 27 -> { var n = 0L; repeat(8) { n = (n shl 8) or rb().toLong() }; n }
            else -> throw IllegalArgumentException("Unsupported CBOR additional info: $info")
        }
        val b = rb()
        val major = b ushr 5
        val a = readArgLocal(b and 0x1F)
        when (major) {
            2, 3 -> cur.pos += a.toInt()
            4 -> repeat(a.toInt()) { skipItem(cur) }
            5 -> repeat(a.toInt()) { skipItem(cur); skipItem(cur) }
            // major 0/1/7 carry no payload beyond the argument readArgLocal already consumed.
        }
    }

    /**
     * Reads a definite-length array (major 4) or map (major 5) header at [cur]'s position and
     * returns the byte range of each child item — for a map, key and value ranges are
     * flattened into one list (2 per pair) — advancing [cur]'s position past the whole
     * array/map. Map keys in TagDrop's own encodings are always small (well under 2 bytes), so
     * only additional-info 0-25 are handled.
     */
    private fun itemRanges(cur: Cursor, expectedMajor: Int): List<Pair<Int, Int>> {
        val head = cur.bytes[cur.pos++].toInt() and 0xFF
        val major = head ushr 5
        require(major == expectedMajor) { "Expected CBOR major $expectedMajor, got $major" }
        val info = head and 0x1F
        val count = when {
            info <= 23 -> info
            info == 24 -> cur.bytes[cur.pos++].toInt() and 0xFF
            info == 25 -> {
                val v = ((cur.bytes[cur.pos].toInt() and 0xFF) shl 8) or (cur.bytes[cur.pos + 1].toInt() and 0xFF)
                cur.pos += 2
                v
            }
            else -> throw IllegalArgumentException("Unsupported CBOR array/map length encoding")
        }
        val n = if (expectedMajor == 5) count * 2 else count
        val ranges = mutableListOf<Pair<Int, Int>>()
        repeat(n) {
            val start = cur.pos
            skipItem(cur)
            ranges.add(start to cur.pos)
        }
        return ranges
    }

    /**
     * Decodes a bare CBOR uint (major 0) or negative integer (major 1 — a QDEF Common Field
     * Key, QDEF-SPEC.md §3.6) at [pos] within [bytes].
     */
    private fun readIntAt(bytes: ByteArray, pos: Int): Long {
        var p = pos
        val b = bytes[p++].toInt() and 0xFF
        val major = b ushr 5
        require(major == 0 || major == 1) { "Expected uint or negative int, got major $major" }
        val info = b and 0x1F
        val arg = when {
            info <= 23 -> info.toLong()
            info == 24 -> (bytes[p].toInt() and 0xFF).toLong()
            info == 25 -> (((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)).toLong()
            else -> throw IllegalArgumentException("Unsupported CBOR int encoding")
        }
        return if (major == 1) -(arg + 1) else arg
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
