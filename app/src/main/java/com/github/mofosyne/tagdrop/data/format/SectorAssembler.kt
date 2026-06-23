package com.github.mofosyne.tagdrop.data.format

import java.io.ByteArrayOutputStream

/**
 * Reassembles TagDrop payloads from scanned [Sector]s (SPEC §5).
 *
 * Unlike a single in-flight assembly, this tracks **several payloads concurrently**, keyed
 * by `(type, cache_id/root_hash)`: sectors from different payloads can be interleaved in any
 * order, any session (SPEC §4.1, §5), and each is matched to its own group as it arrives. A
 * single-sector payload (the common case) completes on its first [add]. Multi-sector payloads
 * accumulate until every `sector_index` `0..sector_count-1` is in; an optional XOR parity
 * sector (SPEC §5) can reconstruct one missing data sector.
 *
 * Integrity (`content_sha256`, SPEC §3/§9) is verified during parsing; the assembler resolves
 * the cover/plain reading of a Content payload, leaving key-trial override decryption (§9) to
 * the caller via [tryKey].
 *
 * Thread-safety: not thread-safe; call from a single thread (main thread).
 */
class SectorAssembler {

    private class Group(val type: Int) {
        var meta: PartMeta? = null
        val data = mutableMapOf<Int, ByteArray>()
        var parity: ByteArray? = null
        /** Set once a `key_material` (SPEC §9) decrypts this payload's content slot as an override map. */
        var resolvedOverride: TagDropPayload.OverrideMap? = null
    }

    private val groups = mutableMapOf<String, Group>()
    private var lastGroupKey: String? = null

    sealed class State {
        /** Nothing in flight. */
        object Idle : State()

        /** Still collecting sectors for one payload. [missingIndices] is sorted ascending. */
        data class Collecting(
            val received: Int,
            val total: Int,
            val type: Int,
            val cacheId: ByteArray?,
            val hint: String?,
            val missingIndices: List<Int>
        ) : State()

        /**
         * A Content payload fully reassembled and integrity-verified; [content] is its resolved
         * cover/plain reading. If [pendingOverrideBlob] is non-null the content slot may also be a
         * hidden override map (SPEC §9) not yet unlocked — a candidate for [tryKey] to
         * "self-correct" [content]/[hint]/[filename]/[mimeType] later. [keyMaterial], if present,
         * is a key this code carries for *other* content (SPEC §9), to be discovered by the caller.
         */
        data class ContentReady(
            val cacheId: ByteArray?,
            val hint: String?,
            val filename: String?,
            val mimeType: String,
            val content: ByteArray,
            val collectionId: ByteArray?,
            val collectionLabel: String?,
            val collectionTag: String?,
            val icon: String?,
            val keyMaterial: ByteArray?,
            val retainKey: Boolean,
            val pendingOverrideBlob: ByteArray? = null,
            val pendingOverrideCompression: Int = TagDropCodec.COMPRESSION_NONE,
            val kdfAlg: Int = TagDropCodec.KDF_NONE,
            val kdfSalt: ByteArray? = null,
            val kdfIters: Int = 100000,
            val wasEncrypted: Boolean = false,
            val lat: Double? = null,
            val lng: Double? = null,
            val radiusM: Double? = null,
            val preferDeclaredLocation: Boolean = false,
            val inReplyTo: ByteArray? = null,
            val title: String? = null,
            val description: String? = null
        ) : State()

        /** A Paper payload fully reassembled. [streamBytes] is the reassembled stream, stored as `ScannedPaper.cborBytes`. */
        data class PaperReady(val paper: TagDropPayload.Paper, val streamBytes: ByteArray) : State()

        /**
         * All sectors present but the content slot couldn't be read as plain content (SPEC §5
         * step 5) — it must be a hidden override blob (SPEC §9), and no key has decrypted it yet.
         * Call [tryKey] when a candidate key arrives (scan order doesn't matter); [contentSlot]
         * plus the kdf fields let the caller also try a passphrase-derived key (SPEC §9).
         */
        data class AwaitingKey(
            val cacheId: ByteArray?,
            val hint: String?,
            val contentSlot: ByteArray,
            val compression: Int,
            val kdfAlg: Int = TagDropCodec.KDF_NONE,
            val kdfSalt: ByteArray? = null,
            val kdfIters: Int = 100000
        ) : State()

        /** All sectors present but `content_sha256` didn't match — incomplete or corrupt assembly. */
        object HashMismatch : State()

        /** All sectors present but the reassembled bytes weren't a well-formed payload. */
        object Failed : State()
    }

    /** Adds a scanned [sector], matching it to (or starting) its payload group, and returns that group's state. */
    fun add(sector: Sector): State {
        val key = groupKey(sector)
        val group = groups.getOrPut(key) { Group(sector.type) }
        group.meta = sector.partMeta
        if (sector.partMeta.paritySchemeRaw != null) {
            group.parity = sector.sectorBytes
        } else {
            group.data[sector.partMeta.sectorIndex] = sector.sectorBytes
        }
        lastGroupKey = key
        val state = computeState(group)
        if (state.isTerminal) groups.remove(key)
        return state
    }

    /** State of the most-recently-touched group, for status display; [State.Idle] if none pending. */
    fun currentState(): State = lastGroupKey?.let { groups[it] }?.let { computeState(it) } ?: State.Idle

    /** True while any payload is still being collected (or awaiting a key). */
    val hasPending: Boolean get() = groups.isNotEmpty()

    /**
     * Tries [keyMaterial] against every fully-collected Content group still awaiting a key
     * (SPEC §9, "discovery, not declaration"): if it decrypts the content slot as an override
     * map, that group resolves and is returned (and dropped from tracking). A non-matching key
     * changes nothing.
     */
    fun tryKey(keyMaterial: ByteArray): List<State.ContentReady> {
        val resolved = mutableListOf<State.ContentReady>()
        val iterator = groups.entries.iterator()
        while (iterator.hasNext()) {
            val (_, group) = iterator.next()
            if (group.type != TagDropCodec.TYPE_CONTENT || group.resolvedOverride != null) continue
            val meta = group.meta ?: continue
            val stream = reassemble(group, meta.sectorCount) ?: continue
            val parsed = TagDropCodec.parseContentStream(stream, meta) as? TagDropCodec.ContentParse.Ok ?: continue
            val slot = parsed.content.content
            if (slot.size < TagDropCodec.OVERRIDE_BLOB_MIN_BYTES) continue
            val override = TagDropCodec.tryDecryptOverrideMap(slot, keyMaterial, parsed.content.compression) ?: continue
            group.resolvedOverride = override
            (computeState(group) as? State.ContentReady)?.let { resolved += it; iterator.remove() }
        }
        return resolved
    }

    fun reset() {
        groups.clear()
        lastGroupKey = null
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private val State.isTerminal: Boolean
        get() = this is State.ContentReady || this is State.PaperReady ||
                this is State.HashMismatch || this is State.Failed

    /** `(type, cache_id)` identity (SPEC §4.1); a key-only code with no `cache_id` keys off its type alone. */
    private fun groupKey(sector: Sector): String =
        "${sector.type}:${sector.partMeta.cacheId?.toHex() ?: "nokey"}"

    private fun computeState(group: Group): State {
        val meta = group.meta ?: return State.Idle
        val count = meta.sectorCount
        val missing = (0 until count).filterNot { group.data.containsKey(it) }

        if (missing.isEmpty()) {
            return finish(group, group.data, meta, count)
        }

        // Single missing data sector + a parity sector → try XOR reconstruction (SPEC §5).
        if (missing.size == 1 && group.parity != null) {
            val reconstructed = reconstruct(group, missing[0], count, meta.totalBytes)
            if (reconstructed != null) {
                val trial = HashMap(group.data).apply { put(missing[0], reconstructed) }
                val state = finish(group, trial, meta, count)
                // Trust the reconstruction only if it parses/verifies; otherwise keep waiting.
                if (state !is State.HashMismatch && state !is State.Failed) {
                    if (state.isTerminal) group.data[missing[0]] = reconstructed
                    return state
                }
            }
        }

        return State.Collecting(group.data.size, count, group.type, meta.cacheId, bestEffortHint(group, count), missing)
    }

    /** Reassembles [data] and parses it into a terminal/awaiting state per [group]'s type. */
    private fun finish(group: Group, data: Map<Int, ByteArray>, meta: PartMeta, count: Int): State {
        val stream = reassemble(data, count) ?: return State.Failed
        return when (group.type) {
            TagDropCodec.TYPE_PAPER -> TagDropCodec.parsePaperStream(stream, meta)
                ?.let { State.PaperReady(it, stream) } ?: State.Failed
            else -> when (val parsed = TagDropCodec.parseContentStream(stream, meta)) {
                is TagDropCodec.ContentParse.Malformed -> State.Failed
                is TagDropCodec.ContentParse.HashMismatch -> State.HashMismatch
                is TagDropCodec.ContentParse.Ok -> contentState(group, parsed.content)
            }
        }
    }

    /** Resolves a parsed Content into a ready/awaiting state: override (if a key matched) → cover → awaiting key. */
    private fun contentState(group: Group, content: TagDropPayload.Content): State {
        val override = group.resolvedOverride
        if (override != null) {
            return readyState(content, override.hint ?: content.hint,
                override.filename ?: content.filename, override.mimeType ?: content.mimeType,
                override.content ?: ByteArray(0), pendingBlob = null, wasEncrypted = true)
        }
        // SPEC §5 step 5: try the cover/plain-content reading per content_compression.
        val cover = runCatching { TagDropCodec.decompressPayload(content.content, content.compression) }.getOrNull()
            ?: return State.AwaitingKey(
                content.cacheId, content.hint, content.content, content.compression,
                content.kdfAlg, content.kdfSalt, content.kdfIters
            )
        val pendingBlob = content.overrideBlob
        return readyState(content, content.hint, content.filename, content.mimeType, cover,
            pendingBlob = pendingBlob, wasEncrypted = pendingBlob != null)
    }

    private fun readyState(
        content: TagDropPayload.Content, hint: String?, filename: String?, mimeType: String,
        resolved: ByteArray, pendingBlob: ByteArray?, wasEncrypted: Boolean
    ) = State.ContentReady(
        cacheId = content.cacheId,
        hint = hint,
        filename = filename,
        mimeType = mimeType,
        content = resolved,
        collectionId = content.collectionId,
        collectionLabel = content.collectionLabel,
        collectionTag = content.collectionTag,
        icon = content.icon,
        keyMaterial = content.keyMaterial,
        retainKey = content.retainKey,
        pendingOverrideBlob = pendingBlob,
        pendingOverrideCompression = if (pendingBlob != null) content.compression else TagDropCodec.COMPRESSION_NONE,
        kdfAlg = content.kdfAlg,
        kdfSalt = content.kdfSalt,
        kdfIters = content.kdfIters,
        wasEncrypted = wasEncrypted,
        lat = content.lat,
        lng = content.lng,
        radiusM = content.radiusM,
        preferDeclaredLocation = content.preferDeclaredLocation,
        inReplyTo = content.inReplyTo,
        title = content.title,
        description = content.description
    )

    /** Concatenates `sector_bytes` for indices `0..count-1` in order, or null if any is missing. */
    private fun reassemble(data: Map<Int, ByteArray>, count: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        for (i in 0 until count) out.write(data[i] ?: return null)
        return out.toByteArray()
    }

    private fun reassemble(group: Group, count: Int): ByteArray? = reassemble(group.data, count)

    /**
     * Reconstructs the single missing data sector [index] by XOR-ing the parity sector against
     * every present data sector (each implicitly zero-padded), then truncating to that sector's
     * real length derived from `total_bytes` (SPEC §5).
     */
    private fun reconstruct(group: Group, index: Int, count: Int, totalBytes: Int): ByteArray? {
        val parity = group.parity ?: return null
        val sectorSize = (totalBytes + count - 1) / count
        val x = parity.copyOf()
        for ((i, bytes) in group.data) {
            if (i == index) continue
            for (k in bytes.indices) {
                if (k < x.size) x[k] = (x[k].toInt() xor bytes[k].toInt()).toByte()
            }
        }
        val realLen = if (index == count - 1) totalBytes - (count - 1) * sectorSize else sectorSize
        if (realLen < 0 || realLen > x.size) return null
        return x.copyOf(realLen)
    }

    /** Best-effort `hint`/`label` from the contiguous run of sectors starting at index 0 (SPEC §5). */
    private fun bestEffortHint(group: Group, count: Int): String? {
        val buf = ByteArrayOutputStream()
        var i = 0
        while (i < count && group.data.containsKey(i)) { buf.write(group.data[i]); i++ }
        if (buf.size() == 0) return null
        @Suppress("UNCHECKED_CAST")
        return runCatching {
            val (items, _) = MiniCbor.decodeSequencePrefix(buf.toByteArray(), 1)
            (items[0] as? Map<Int, Any>)?.get(3) as? String
        }.getOrNull()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
