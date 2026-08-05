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
     * Encodes a signed CBOR integer: a non-negative uint (major type 0) for `k >= 0`, or a CBOR
     * negative integer (major type 1, RFC 8949 §3.1: stores `-(n+1)` as its argument) for `k <
     * 0`. Used for map/field keys (a non-negative uint for an ordinary Type-specific key, a
     * negative one for a QDEF Common Field Key, QDEF-SPEC.md §3.6, always negative/odd/optional)
     * — and, as of SPEC.md v16, for a Record's own `typeId` item too ([encodeRecord]), where
     * sign is the namespace-scoping signal (§2.1a): non-negative = global, negative = scoped to
     * whatever namespace is ambient.
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

    // ── QDEF array-wrapped Records (QDEF-SPEC.md §3.1, SPEC.md v9 §2, v11 §2, v15 §3.1) ──
    // Every Record is its own self-delimited CBOR array, [namespace?, typeId, map?, subrecord*]
    // — not a bare typeId-then-map pair. The map is omitted entirely when every field is
    // declared null (saves its header byte). As of SPEC.md v15, QDEF's positional `payload`
    // array slot is gone entirely from the grammar: a Record's "one genuinely singular value,"
    // when it has one, now lives at reserved map key `0` (QDEF-SPEC.md §3.6) exactly like any
    // other field — there is no longer any ambiguity to resolve between "next item is the
    // payload" vs. "next item is subrecord 0," since every item after the map is
    // unconditionally a subrecord array. `subrecords` are already-encoded `[typeId, ...]` byte
    // sequences (each itself built by a nested encodeRecord call), spliced in as their own
    // array items. Mirrors the JS reference's cborRecord/craw pattern (tools/generator/index.html).

    /**
     * Encodes a QDEF Record: `[namespace?, typeId, map?, subrecord*]` as one CBOR array (major
     * type 4) — QDEF-SPEC.md §2.1a/§3.5's namespace-pairing prefix. There is no positional
     * payload slot as of SPEC.md v15 (§3.6's reserved map key `0` replaces it — a caller that
     * needs one just includes `0 to someBytes` as an ordinary entry in [fields]).
     *
     * As of SPEC.md v16, [typeId]'s own **sign** — not the presence of a namespace item — decides
     * whether *this* Record is global or namespace-scoped: pass a non-negative [typeId] for a
     * QDEF standard/global Type (Media Preview, Media Payload, Split Wrapper, Compress Wrapper),
     * or a negative [typeId] (the negation of one of TagDrop's declared magnitudes, e.g. `-1` for
     * Content Extension) for a Record scoped to whatever namespace is ambient from an ancestor —
     * `h''` ("inherit the ambient namespace as my own scope") is gone from QDEF's grammar
     * entirely, so a TagDrop-scoped Record now passes [namespace] as `null` (the default) and
     * relies purely on its negative typeId; see [decodeRecordPrefix]'s doc comment for the full
     * resolution rule. [namespace] here therefore has exactly one remaining job — introducing a
     * fresh ambient value for this Record's OWN [subrecords], never for itself — pass the real
     * 4-byte value (`TagDropCodec.TAGDROP_NAMESPACE`) when this Record needs to hand a namespace
     * down to something nested inside it (the root Bundle's own leading element being the common
     * case, via [encodeRootBundle]); leave it `null` on every other Record, TagDrop-scoped or
     * global alike, letting the ambient value already in scope keep flowing through untouched.
     *
     * Fields sort by RFC 8949 §4.2.1 canonical/deterministic order — by their *encoded* key
     * bytes (shorter first, then bytewise), not by plain integer value; see
     * [CANONICAL_KEY_BYTES_ORDER] (QDEF-SPEC.md §3.4). The map is omitted entirely only when
     * [fields] itself is empty — a static, per-call-site choice — NOT whenever every declared
     * field's *value* happens to be null: a Type that always declares fields (Content Extension,
     * Paper-Preview/Body) must keep writing `{}` even when every optional field is unset this
     * time, since [stripKeys] and any signature-hash formula covering its bytes need a stable
     * map to exist.
     */
    fun encodeRecord(
        typeId: Int, fields: List<Pair<Int, Any?>>, subrecords: List<ByteArray> = emptyList(),
        namespace: ByteArray? = null
    ): ByteArray {
        val items = mutableListOf<ByteArray>()
        if (namespace != null) items.add(encodeBytes(namespace))
        items.add(encodeKey(typeId)) // typeId's own sign (major 0 vs. major 1) is the v16 scoping signal
        if (fields.isNotEmpty()) items.add(encodeMap(fields.sortedWith(compareBy(CANONICAL_KEY_BYTES_ORDER) { encodeKey(it.first) })))
        items.addAll(subrecords)
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(item)
        return out.toByteArray()
    }

    /** One decoded QDEF Record (QDEF-SPEC.md §3.1) — see [decodeRecordPrefix]. */
    data class DecodedRecord(
        /** The Record's own Type ID (the array's typeId element, after any leading namespace item). */
        val typeId: Int,
        /** The Record's decoded field map — empty if the map was omitted from the wire entirely. As of SPEC.md v15, a Record's "one genuinely singular value," if it has one, is an ordinary entry at reserved map key `0` (QDEF-SPEC.md §3.6) rather than a separate positional payload item. */
        val record: Map<Int, Any>,
        /** This Record's own exact byte range — namespace? + typeId + map + all subrecords — what signature/group-id hashes are computed over. */
        val raw: ByteArray,
        /** Nested Records carried after this Record's own map, each with [raw] relative to the ORIGINAL top-level bytes passed to the outermost [decodeRecordPrefix] call. */
        val subrecords: List<DecodedRecord>,
        /** Whatever follows this Record in the CBOR Sequence. */
        val trailing: ByteArray,
        /**
         * This Record's own resolved namespace (QDEF-SPEC.md §3.5, SPEC.md v16 §2.1a) — the
         * value that governs how *this Record's own* [typeId] is interpreted. As of v16 this is
         * decided purely by [typeId]'s own sign: `null` (global/standard) whenever [typeId] is
         * non-negative, regardless of whether a namespace item happens to be present on this
         * Record's own array; the ambient value passed in as `decodeRecordPrefix`'s
         * `ambientNamespace` parameter whenever [typeId] is negative. A namespace item present on
         * this SAME Record never contributes to this field — as of v16 it has exactly one job,
         * becoming the new ambient value for [subrecords], never scoping the Record that carries
         * it (this is precisely why a Record can no longer both introduce a namespace and be
         * scoped by it in the same array). Does NOT by itself tell you what ambient value was
         * forwarded to [subrecords] — a standard/global Type with no namespace item of its own
         * (this field null) still passes whatever ambient value IT received through to its own
         * subrecords, untouched; see [decodeRecordPrefix]'s own doc comment.
         */
        val namespace: ByteArray?
    )

    /**
     * Determines where a Record's optional namespace/map/subrecords fall within its own
     * top-level item [ranges] — QDEF-SPEC.md §3.1/§3.5's grammar (no positional payload slot as
     * of SPEC.md v15), dispatched purely by CBOR major type: an optional leading namespace item
     * (major 2, byte string) comes first, then typeId (major 0); then the map (major 5) if
     * present; everything from there onward is subrecords (each unconditionally a CBOR array,
     * major 4). The namespace item is unambiguous at position 0 only, since typeId (always
     * major 0, never major 2) is guaranteed to immediately follow it. Shared by
     * [decodeRecordPrefix]/[stripKeys]/[stripSubrecordType]/[stripAllSubrecords] so all four
     * agree on the same layout.
     */
    private class RecordLayout(val hasNamespace: Boolean, val typeIdIdx: Int, val mapIdx: Int, val subrecordsFrom: Int)

    private fun majorOf(bytes: ByteArray, pos: Int): Int = (bytes[pos].toInt() and 0xFF) ushr 5

    private fun layoutOf(bytes: ByteArray, ranges: List<Pair<Int, Int>>): RecordLayout {
        val hasNamespace = ranges.isNotEmpty() && majorOf(bytes, ranges[0].first) == 2
        var idx = if (hasNamespace) 1 else 0
        val typeIdIdx = idx
        idx++
        var mapIdx = -1
        if (idx < ranges.size && majorOf(bytes, ranges[idx].first) == 5) { mapIdx = idx; idx++ }
        return RecordLayout(hasNamespace, typeIdIdx, mapIdx, idx)
    }

    /**
     * Decodes one QDEF Record (a self-delimited CBOR array, `[namespace?, typeId, map?,
     * subrecord*]`) from the head of [bytes] — see [DecodedRecord]. Returns null if
     * the head of [bytes] isn't a well-formed Record.
     *
     * [ambientNamespace] is whatever namespace value is already in scope from this Record's own
     * *parent* (null at the outermost call unless the caller already knows better — e.g.
     * [decodeRootBundle] threading the root's own declaration down).
     *
     * Namespace resolution (QDEF-SPEC.md §3.5, SPEC.md v16 §2.1a — this Record's own scope is
     * decided purely by [DecodedRecord.typeId]'s sign, never by whether a namespace item happens
     * to be present on this same Record): [DecodedRecord.namespace] is [ambientNamespace]
     * unchanged when the decoded typeId is negative, or `null` (global) when it's non-negative —
     * `h''` ("inherit the ambient namespace as my own scope") no longer exists as a distinct
     * wire form, since sign alone now carries that signal at zero extra bytes.
     *
     * A namespace item on this Record, if present, plays a completely different, narrower role:
     * it becomes the *new* ambient value handed down to [DecodedRecord.subrecords] — never a
     * contributor to this Record's own [DecodedRecord.namespace] above. If no namespace item is
     * present at all, [ambientNamespace] is passed straight through unchanged to subrecords
     * instead — a standard/global Type (no namespace item of its own, ever) still relays
     * whatever ambient namespace it received on to whatever's nested inside it. A present
     * namespace item that decodes to an empty byte string (`h''`) is treated the same as "no
     * item present" for this purpose — no valid v16 encoder ever emits an empty namespace bstr
     * (the mechanism it used to signal, cascading, is now free — sign does that job instead), so
     * this is purely graceful handling of a malformed/stale input, not a supported cascade form.
     * Getting the distinction between "this Record's own scope" and "what it hands its children"
     * right is what lets e.g. Content Signature — nested inside Media Payload inside Media
     * Preview, neither of which ever declares a namespace of their own or is itself
     * namespace-scoped — still correctly resolve back to the root's one declaration via its own
     * negative typeId alone.
     */
    @Suppress("UNCHECKED_CAST")
    fun decodeRecordPrefix(bytes: ByteArray, ambientNamespace: ByteArray? = null): DecodedRecord? = try {
        val cur = Cursor(bytes, 0)
        val ranges = itemRanges(cur, 4)
        if (ranges.isEmpty()) null else {
            val layout = layoutOf(bytes, ranges)
            if (layout.typeIdIdx >= ranges.size) null else {
                // Ambient value handed down to subrecords: a present, non-empty namespace item
                // becomes the new ambient value; otherwise (no item at all, or a malformed empty
                // `h''` no valid encoder emits any more) [ambientNamespace] passes through
                // untouched — the "global Type still relays ambient context" rule above.
                val childAmbient: ByteArray? = if (layout.hasNamespace) {
                    val (nsStart, nsEnd) = ranges[0]
                    val nsBytes = decodeSequencePrefix(bytes.copyOfRange(nsStart, nsEnd), 1).first[0] as? ByteArray
                        ?: throw IllegalStateException("bad namespace item")
                    if (nsBytes.isEmpty()) ambientNamespace else nsBytes
                } else ambientNamespace
                val (typeIdStart, typeIdEnd) = ranges[layout.typeIdIdx]
                val typeIdVal = decodeSequencePrefix(bytes.copyOfRange(typeIdStart, typeIdEnd), 1).first[0]
                val typeId = (typeIdVal as? Long)?.toInt()
                if (typeId == null) null else {
                    // v16's own-scope rule: negative typeId adopts [ambientNamespace] as received;
                    // non-negative typeId is unconditionally global, regardless of any namespace
                    // item sitting on this same Record (that item's only job is [childAmbient]).
                    val ownNamespace: ByteArray? = if (typeId < 0) ambientNamespace else null
                    val record: Map<Int, Any> = if (layout.mapIdx < 0) emptyMap() else {
                        val (mapStart, mapEnd) = ranges[layout.mapIdx]
                        decodeSequencePrefix(bytes.copyOfRange(mapStart, mapEnd), 1).first[0] as? Map<Int, Any> ?: throw IllegalStateException("bad map")
                    }
                    val subrecords = mutableListOf<DecodedRecord>()
                    var failed = false
                    for (i in layout.subrecordsFrom until ranges.size) {
                        val (s, e) = ranges[i]
                        val sub = decodeRecordPrefix(bytes.copyOfRange(s, e), childAmbient)
                        if (sub == null) { failed = true; break }
                        subrecords.add(sub)
                    }
                    if (failed) null
                    else DecodedRecord(typeId, record, bytes.copyOfRange(0, cur.pos), subrecords, bytes.copyOfRange(cur.pos, bytes.size), ownNamespace)
                }
            }
        }
    } catch (e: Exception) { null }

    /**
     * Encodes 1+ already-encoded top-level Record byte-arrays as the QDEF self-delimited root
     * (QDEF-SPEC.md §2/§3.1): one more definite-length CBOR array wrapping [records] as its own
     * subrecords, with [namespace], if non-null, prepended as *that wrapping array's own* leading
     * element (QDEF-SPEC.md §3.5, SPEC.md v16 §2.1a).
     *
     * As of SPEC.md v16, this ALWAYS wraps — even a single Record (SPEC §9's key-only case) —
     * since a Record can no longer both introduce a namespace and be scoped by it in the same
     * array, so there's no shape left where a lone top-level Record's own array could double as
     * both the root and something namespace-scoped itself (the version-13–15 "single Record
     * needs no Bundle indirection" special case is gone). Each TagDrop-scoped child then
     * resolves back to [namespace] via its own negative typeId (see [encodeRecord]/
     * [decodeRecordPrefix]) rather than repeating the full value or emitting a cascade marker of
     * its own. Bytes appended after the returned array are provably outside the container, and
     * MUST be tolerated by a decoder (SPEC §9's deniability feature) — [decodeRootBundle]'s own
     * self-delimiting length is what makes that safe without any app-specific record-count
     * foreknowledge.
     */
    fun encodeRootBundle(records: List<ByteArray>, namespace: ByteArray? = null): ByteArray {
        require(records.isNotEmpty()) { "encodeRootBundle requires at least one Record" }
        val items = mutableListOf<ByteArray>()
        if (namespace != null) items.add(encodeBytes(namespace))
        items.addAll(records)
        val out = ByteArrayOutputStream()
        writeHead(out, 4, items.size.toLong())
        for (item in items) out.write(item)
        return out.toByteArray()
    }

    /**
     * Decodes the QDEF self-delimited root (QDEF-SPEC.md §2/§3.1, §3.5/SPEC.md v16 §2.1a):
     * exactly one definite-length CBOR array whose own leading element MUST be TagDrop's
     * namespace declaration (a CBOR byte string, major type 2) — as of v16 every TagDrop code is
     * a namespace-declaring root Bundle, including the former single-Record/key-only shape (see
     * [encodeRootBundle]), so there is no longer a "single-Record root, no Bundle indirection"
     * case, nor a legacy Bundle-with-no-namespace-item case, to special-case here. Returns null
     * if the root isn't a well-formed, definite-length CBOR array, if its first item isn't a
     * byte string, or if any remaining item fails to decode as a Record (each seeded with the
     * declared namespace as its own `ambientNamespace`, per [decodeRecordPrefix]).
     */
    fun decodeRootBundle(bytes: ByteArray): List<DecodedRecord>? {
        return try {
            val ranges = itemRanges(Cursor(bytes, 0), 4)
            if (ranges.isEmpty() || majorOf(bytes, ranges[0].first) != 2) null
            else {
                val (nsStart, nsEnd) = ranges[0]
                val namespace = decodeSequencePrefix(bytes.copyOfRange(nsStart, nsEnd), 1).first[0] as? ByteArray
                    ?: return null
                val out = mutableListOf<DecodedRecord>()
                for (i in 1 until ranges.size) {
                    val (s, e) = ranges[i]
                    out.add(decodeRecordPrefix(bytes.copyOfRange(s, e), namespace) ?: return null)
                }
                out
            }
        } catch (e: Exception) { null }
    }

    /**
     * Splices the namespace bstr out of the front of a namespaced root array, given [bytes] — a
     * namespaced root array whose first element is the namespace CBOR byte string — returning
     * the namespace bytes and the plain, unnamespaced root array, with the namespace item
     * removed and the array header's count decremented back by one. Returns null if [bytes]
     * isn't a well-formed CBOR array or its first element isn't a byte string (i.e. no namespace
     * present).
     *
     * As of SPEC.md v14 (§2.1a), every carrier's root array carries its namespace declaration
     * the same way (§3.5's "the ordinary namespace-pairing prefix," no separate discriminator
     * item), and [decodeRootBundle]/[encodeRootBundle]'s own `namespace` parameter now models
     * that uniformly across all three carriers — so [TagDropCodec] no longer calls this function
     * itself (its own byte-mode QR [TagDropCodec.stripQdefFraming] just strips the flat 4-byte
     * magic prefix and lets [decodeRootBundle] handle the rest, same as every other carrier).
     * Left in place as a general-purpose splice utility — still correct in isolation, and a
     * natural building block if a future encode-side byte-mode QR path is ever added here.
     */
    fun unframeNamespaceFromRootArray(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        return try {
            val ranges = itemRanges(Cursor(bytes, 0), 4)
            if (ranges.isEmpty() || majorOf(bytes, ranges[0].first) != 2) null
            else {
                val (nsStart, nsEnd) = ranges[0]
                val namespace = bytes.copyOfRange(nsStart, nsEnd)
                val itemsStart = if (ranges.size > 1) ranges[1].first else nsEnd
                val items = bytes.copyOfRange(itemsStart, bytes.size)
                val out = ByteArrayOutputStream()
                writeHead(out, 4, (ranges.size - 1).toLong())
                out.write(items)
                namespace to out.toByteArray()
            }
        } catch (e: Exception) { null }
    }

    /**
     * Re-encodes [recordBytes] (a Record's own array, `[namespace?, typeId, map?, subrecord*]`)
     * with every field-map pair whose key is in [keysToStrip] removed, regardless of position
     * — SPEC §2.2's fixed ascending key-encoding order means a higher-numbered field (e.g.
     * `source_url`, key 55) can legitimately sort after the ones being stripped (e.g. the
     * signature fields, 45/47/49), so this walks and keeps every surviving pair by its own
     * byte range rather than assuming the stripped keys form a truncatable trailing run. Any
     * subrecords, if present, are carried through byte-for-byte, untouched — including a
     * leading namespace item (QDEF-SPEC.md §3.5, SPEC.md v14 §2.1a), carried through unchanged
     * as the first item of the rebuilt array, exactly like the typeId item. Note: key `0`
     * (QDEF-SPEC.md §3.6's reserved "singular value" key, SPEC.md v15) is stripped exactly like
     * any other key if it's in [keysToStrip] — none of the current callers ever ask to strip it
     * (only `signature_algorithm`/`signer_id`/`signer_label`-type keys are stripped, never a
     * Record's own mandatory content), but this function itself has no special-cased protection
     * for key `0` beyond that. Mirrors the JS reference's `stripKeys`. Requires a map to be
     * present (every caller strips keys from a Record Type that always carries required fields).
     */
    fun stripKeys(recordBytes: ByteArray, keysToStrip: Set<Int>): ByteArray {
        val cur = Cursor(recordBytes, 0)
        val ranges = itemRanges(cur, 4)
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
        val items = mutableListOf<ByteArray>()
        if (layout.hasNamespace) items.add(recordBytes.copyOfRange(ranges[0].first, ranges[0].second))
        items.add(recordBytes.copyOfRange(ranges[layout.typeIdIdx].first, ranges[layout.typeIdIdx].second))
        items.add(mapOut.toByteArray())
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
     * Carries a leading namespace item (QDEF-SPEC.md §3.5, SPEC.md v14 §2.1a) and the field map
     * (including its key `0`, if any — SPEC.md v15's reserved "singular value" key, e.g. Media
     * Payload's own `content`) through unchanged, same as [stripKeys].
     */
    fun stripSubrecordType(recordBytes: ByteArray, typeIdToStrip: Int): ByteArray {
        val cur = Cursor(recordBytes, 0)
        val ranges = itemRanges(cur, 4)
        val layout = layoutOf(recordBytes, ranges)
        val kept = ranges.drop(layout.subrecordsFrom).filter { (s, _) ->
            val subCur = Cursor(recordBytes, s)
            val subRanges = itemRanges(subCur, 4)
            val subLayout = layoutOf(recordBytes, subRanges)
            readIntAt(recordBytes, subRanges[subLayout.typeIdIdx].first).toInt() != typeIdToStrip
        }
        val items = mutableListOf<ByteArray>()
        if (layout.hasNamespace) items.add(recordBytes.copyOfRange(ranges[0].first, ranges[0].second))
        items.add(recordBytes.copyOfRange(ranges[layout.typeIdIdx].first, ranges[layout.typeIdIdx].second))
        if (layout.mapIdx >= 0) { val (s, e) = ranges[layout.mapIdx]; items.add(recordBytes.copyOfRange(s, e)) }
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
     * message, not whatever happens to be nested inside it on the wire. The map (including its
     * key `0`, if any — SPEC.md v15's reserved "singular value" key) is kept — only subrecords
     * are dropped. Carries a leading namespace item (QDEF-SPEC.md §3.5, SPEC.md v14 §2.1a)
     * through unchanged, same as [stripKeys].
     */
    fun stripAllSubrecords(recordBytes: ByteArray): ByteArray {
        val cur = Cursor(recordBytes, 0)
        val ranges = itemRanges(cur, 4)
        val layout = layoutOf(recordBytes, ranges)
        val items = mutableListOf<ByteArray>()
        if (layout.hasNamespace) items.add(recordBytes.copyOfRange(ranges[0].first, ranges[0].second))
        items.add(recordBytes.copyOfRange(ranges[layout.typeIdIdx].first, ranges[layout.typeIdIdx].second))
        if (layout.mapIdx >= 0) { val (s, e) = ranges[layout.mapIdx]; items.add(recordBytes.copyOfRange(s, e)) }
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
