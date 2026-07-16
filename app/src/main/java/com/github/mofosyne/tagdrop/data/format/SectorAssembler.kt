package com.github.mofosyne.tagdrop.data.format

import java.security.MessageDigest

/**
 * Reassembles TagDrop payloads from scanned [ScannedRecord]s (SPEC §5), mirroring
 * `tools/reader/index.html`'s `RecordAssembler` — the settled JS reference this Kotlin port
 * targets.
 *
 * Groups are keyed by Split Wrapper `group_id` (SPEC §5.1), not `(type, cache_id)`: a lone
 * Preview (key-only code) or a Preview + plain/Compress-wrapped Body (single-code payload)
 * completes immediately on its first [add]. A Preview + Split-fragment accumulates by
 * `group_id` until every fragment `0..count-1` is in (an optional XOR parity fragment, at
 * index `== count`, can reconstruct one missing data fragment).
 *
 * Thread-safety: not thread-safe; call from a single thread (main thread).
 */
class SectorAssembler {

    private class Group(val kind: PayloadKind, val groupId: ByteArray, val count: Int, val total: Int) {
        val data = mutableMapOf<Int, ByteArray>()
        var parity: ByteArray? = null
        var previewRaw: ByteArray? = null
        var preview: Map<Int, Any>? = null
        /** Set once a `key_material` (SPEC §9) decrypts this payload's content slot as an override map. */
        var resolvedOverride: TagDropPayload.OverrideMap? = null
    }

    private val groups = mutableMapOf<String, Group>()
    private var lastGroupKey: String? = null

    sealed class State {
        /** Nothing in flight. */
        object Idle : State()

        /** Still collecting Split fragments for one payload. [missingIndices] is sorted ascending. */
        data class Collecting(
            val received: Int,
            val total: Int,
            val kind: PayloadKind,
            val cacheId: ByteArray?,
            val hint: String?,
            val missingIndices: List<Int>
        ) : State()

        /**
         * A Content payload's Preview+Body fully reassembled (SPEC §5, §9). [content] is the
         * content slot's bytes exactly as carried — for an override-map payload (SPEC §9) this
         * IS the encrypted blob unless [pendingOverrideBlob] has already been resolved by a
         * matching key ([wasEncrypted] true, [content] the decrypted override reading). There is
         * no separate "awaiting key" state: a still-locked payload is fully "ready" with
         * [pendingOverrideBlob] set, exactly like the web reader — the caller shows [content] as
         * the clear/cover reading and offers key trial for [pendingOverrideBlob] in the
         * background ([SectorAssembler.tryKey]).
         */
        data class ContentReady(
            val previewRaw: ByteArray,
            val bodyRaw: ByteArray?,
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
            /**
             * True only when the author actually declared an override (`encryption` cosmetic
             * hint or a passphrase `kdf_alg`, SPEC §9) — as opposed to [pendingOverrideBlob]
             * merely being long enough to *try* keys against (every payload this size is a
             * candidate, per "discovery, not declaration"). Gates the 🔒 UI hint so it doesn't
             * fire on ordinary unencrypted content that happens to be ≥28 bytes.
             */
            val pendingOverrideDeclared: Boolean = false,
            val kdfAlg: Int = TagDropCodec.KDF_NONE,
            val kdfSalt: ByteArray? = null,
            val kdfIters: Int = 100000,
            val wasEncrypted: Boolean = false,
            val lat: Double? = null,
            val lng: Double? = null,
            val radiusM: Double? = null,
            val preferDeclaredLocation: Boolean = false,
            val locationLabel: String? = null,
            val inReplyTo: ByteArray? = null,
            val title: String? = null,
            val description: String? = null,
            val createdAt: Long? = null,
            val pixelArt: Boolean = false,
            val sourceUrl: String? = null,
            val signatureAlgorithm: Int = TagDropCodec.SIGNATURE_ALG_NONE,
            val signature: ByteArray? = null,
            val signerPubkey: ByteArray? = null,
            val signerId: ByteArray? = null,
            val signerLabel: String? = null
        ) : State()

        /**
         * A Paper payload fully reassembled. [streamBytes] (`previewRaw + bodyRaw`) is the
         * reassembled (Compress-unwrapped, unsplit) stream, stored as `ScannedPaper.cborBytes`;
         * [previewRaw]/[bodyRaw] are exposed separately for signature verification
         * (`data/signing/SignatureVerifier.kt`'s `verifyPaperSignature`).
         */
        data class PaperReady(val paper: TagDropPayload.Paper, val previewRaw: ByteArray, val bodyRaw: ByteArray, val streamBytes: ByteArray) : State()

        /** A Split group fully reassembled but `group_id` didn't match — incomplete or corrupt assembly. */
        object HashMismatch : State()

        /** All fragments present but the reassembled bytes weren't a well-formed payload. */
        object Failed : State()
    }

    /** Adds a scanned [record], matching it to (or starting) its payload group, and returns that group's state. */
    fun add(record: ScannedRecord): State {
        if (!TagDropCodec.isSplitFragment(record)) {
            return if (record.kind == PayloadKind.PAPER) {
                finishPaper(record, TagDropCodec.unwrappedBodyBytes(record))
            } else {
                finishContent(record, TagDropCodec.unwrappedBodyBytes(record), resolvedOverride = null)
            }
        }
        val frag = TagDropCodec.splitFragmentOf(record) ?: return State.Failed
        val key = frag.groupId.toHex()
        val group = groups.getOrPut(key) { Group(record.kind, frag.groupId, frag.count, frag.total) }
        // Preview repeats identically on every code (SPEC §5.1) — keep the latest scan's copy.
        group.previewRaw = record.previewRaw
        group.preview = record.preview
        if (frag.isParity) group.parity = frag.data else group.data[frag.index] = frag.data
        lastGroupKey = key
        val state = computeState(group)
        if (state.isTerminal) groups.remove(key)
        return state
    }

    /** State of the most-recently-touched group, for status display; [State.Idle] if none pending. */
    fun currentState(): State = lastGroupKey?.let { groups[it] }?.let { computeState(it) } ?: State.Idle

    /** True while any payload is still being collected. */
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
            if (group.kind != PayloadKind.CONTENT || group.resolvedOverride != null) continue
            val previewRaw = group.previewRaw ?: continue
            val preview = group.preview ?: continue
            val wrapped = reassemble(group) ?: continue
            val record = ScannedRecord(group.kind, previewRaw, preview, null, null)
            val parsed = TagDropCodec.parseContentStream(record, wrapped) as? TagDropCodec.ContentParse.Ok ?: continue
            val slot = parsed.content.content
            if (slot.size < TagDropCodec.OVERRIDE_BLOB_MIN_BYTES) continue
            val override = TagDropCodec.tryDecryptOverrideMap(slot, keyMaterial) ?: continue
            group.resolvedOverride = override
            val state = finishContent(record, wrapped, override)
            if (state is State.ContentReady) { resolved += state; iterator.remove() }
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

    private fun computeState(group: Group): State {
        val wrapped = reassemble(group)
        if (wrapped != null) {
            // group_id is SHA-256(wrapped Body)[0:8] (SPEC §5.1) — the reassembly integrity
            // check over the bytes exactly as transmitted (after Compress-wrap, if applicable).
            if (!sha256(wrapped).copyOf(8).contentEquals(group.groupId)) return State.HashMismatch
            val previewRaw = group.previewRaw ?: return State.Failed
            val preview = group.preview ?: return State.Failed
            val record = ScannedRecord(group.kind, previewRaw, preview, null, null)
            return if (group.kind == PayloadKind.PAPER) {
                finishPaper(record, wrapped)
            } else {
                finishContent(record, wrapped, group.resolvedOverride)
            }
        }
        val missing = (0 until group.count).filterNot { group.data.containsKey(it) }
        val preview = group.preview
        return State.Collecting(
            group.data.size, group.count, group.kind,
            preview?.let { identityOf(it) }, preview?.let { hintOf(it) }, missing
        )
    }

    /** Completes a payload whose full (wire-form) Body bytes are in hand — or a key-only code with no Body at all ([bodyWireBytes] null). */
    private fun finishContent(record: ScannedRecord, bodyWireBytes: ByteArray?, resolvedOverride: TagDropPayload.OverrideMap?): State =
        when (val parsed = TagDropCodec.parseContentStream(record, bodyWireBytes)) {
            is TagDropCodec.ContentParse.Malformed -> State.Failed
            is TagDropCodec.ContentParse.HashMismatch -> State.HashMismatch
            is TagDropCodec.ContentParse.Ok -> contentState(record, parsed.bodyRaw, resolvedOverride, parsed.content)
        }

    /** Resolves a parsed Content into a ready state: an already-unlocked override reading, or the clear/cover reading with the candidate blob (if any) left pending. */
    private fun contentState(
        record: ScannedRecord, bodyRaw: ByteArray?,
        resolvedOverride: TagDropPayload.OverrideMap?, content: TagDropPayload.Content
    ): State {
        if (resolvedOverride != null) {
            return readyState(
                record.previewRaw, bodyRaw, content,
                resolvedOverride.hint ?: content.hint, resolvedOverride.filename ?: content.filename,
                resolvedOverride.mimeType ?: content.mimeType, resolvedOverride.content ?: ByteArray(0),
                pendingBlob = null, wasEncrypted = true
            )
        }
        val pendingBlob = content.overrideBlob
        // Only treat the candidate blob as a UI-worthy "locked" hint if the author actually
        // declared it (cosmetic `encryption` field or a passphrase KDF) — SPEC §9 deliberately
        // leaves undeclared candidates indistinguishable from ordinary content.
        val declared = pendingBlob != null &&
            (content.encryption == TagDropCodec.ENCRYPTION_AES256GCM || content.kdfAlg != TagDropCodec.KDF_NONE)
        return readyState(
            record.previewRaw, bodyRaw, content, content.hint, content.filename, content.mimeType, content.content,
            pendingBlob = pendingBlob, wasEncrypted = declared, declared = declared
        )
    }

    private fun readyState(
        previewRaw: ByteArray, bodyRaw: ByteArray?, content: TagDropPayload.Content,
        hint: String?, filename: String?, mimeType: String,
        resolved: ByteArray, pendingBlob: ByteArray?, wasEncrypted: Boolean, declared: Boolean = false
    ) = State.ContentReady(
        previewRaw = previewRaw,
        bodyRaw = bodyRaw,
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
        pendingOverrideDeclared = declared,
        kdfAlg = content.kdfAlg,
        kdfSalt = content.kdfSalt,
        kdfIters = content.kdfIters,
        wasEncrypted = wasEncrypted,
        lat = content.lat,
        lng = content.lng,
        radiusM = content.radiusM,
        preferDeclaredLocation = content.preferDeclaredLocation,
        locationLabel = content.locationLabel,
        inReplyTo = content.inReplyTo,
        title = content.title,
        description = content.description,
        createdAt = content.createdAt,
        pixelArt = content.pixelArt,
        sourceUrl = content.sourceUrl,
        signatureAlgorithm = content.signatureAlgorithm,
        signature = content.signature,
        signerPubkey = content.signerPubkey,
        signerId = content.signerId,
        signerLabel = content.signerLabel
    )

    /** Paper-specific counterpart to [finishContent]: [bodyWireBytes] null means malformed (a Paper always has a Body). */
    private fun finishPaper(record: ScannedRecord, bodyWireBytes: ByteArray?): State {
        if (bodyWireBytes == null) return State.Failed
        val paper = TagDropCodec.parsePaperStream(record, bodyWireBytes) ?: return State.Failed
        val bodyRaw = TagDropCodec.logicalBodyBytes(bodyWireBytes, isPaper = true) ?: return State.Failed
        return State.PaperReady(paper, record.previewRaw, bodyRaw, record.previewRaw + bodyRaw)
    }

    /** Concatenated fragment data for a complete group (with XOR parity reconstruction of a single missing fragment, SPEC §5), or null while fragments are still missing. */
    private fun reassemble(group: Group): ByteArray? {
        val missing = (0 until group.count).filterNot { group.data.containsKey(it) }
        val data: Map<Int, ByteArray> = if (missing.size == 1 && group.parity != null) {
            val reconstructed = reconstruct(group, missing[0]) ?: return null
            HashMap(group.data).apply { put(missing[0], reconstructed) }
        } else if (missing.isNotEmpty()) {
            return null
        } else {
            group.data
        }
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until group.count) out.write(data[i] ?: return null)
        val bytes = out.toByteArray()
        return bytes.takeIf { it.size == group.total }
    }

    /**
     * Reconstructs the single missing data fragment [index] by XOR-ing the parity fragment
     * against every present data fragment (each implicitly zero-padded), then truncating to
     * that fragment's real length derived from `total` (SPEC §5).
     */
    private fun reconstruct(group: Group, index: Int): ByteArray? {
        val parity = group.parity ?: return null
        val chunkLen = (group.total + group.count - 1) / group.count
        val x = parity.copyOf()
        for ((i, bytes) in group.data) {
            if (i == index) continue
            for (k in bytes.indices) {
                if (k < x.size) x[k] = (x[k].toInt() xor bytes[k].toInt()).toByte()
            }
        }
        val realLen = if (index == group.count - 1) group.total - (group.count - 1) * chunkLen else chunkLen
        if (realLen < 0 || realLen > x.size) return null
        return x.copyOf(realLen)
    }

    private fun identityOf(preview: Map<Int, Any>): ByteArray? = preview[1] as? ByteArray
    private fun hintOf(preview: Map<Int, Any>): String? = preview[3] as? String

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
