package com.github.mofosyne.tagdrop.data.format

import java.security.MessageDigest

/**
 * Assembles a multi-code TagDrop cache from scanned pieces.
 *
 * Designed for geographic distribution: chunks can be scanned in any order and
 * from any location. Provide the Manifest first (or at any point), then scan
 * Chunks until all are collected. SHA-256 integrity is verified on assembly.
 *
 * Thread-safety: not thread-safe; call from a single thread (main thread).
 */
class ChunkAssembler {

    private var manifest: TagDropPayload.Manifest? = null
    private val chunks = mutableMapOf<Int, ByteArray>()

    /** Set once a `key_material` (SPEC §9) successfully decrypts the assembled bytes. */
    private var resolvedContent: ByteArray? = null

    sealed class State {
        /** No manifest scanned yet. */
        object WaitingForManifest : State()

        /** Manifest received; still collecting chunks. */
        data class Collecting(val received: Int, val total: Int, val cacheId: ByteArray, val hint: String?) : State()

        /** All chunks received and SHA-256 verified. Content is ready. */
        data class Complete(
            val cacheId: ByteArray,
            val hint: String?,
            val filename: String?,
            val mimeType: String,
            val content: ByteArray,  // already decrypted (if needed) and decompressed
            val collectionId: ByteArray?,
            val collectionLabel: String?,
            val collectionTag: String?,
            val icon: String?
        ) : State()

        /** All chunks received but assembly failed integrity check. */
        object HashMismatch : State()

        /**
         * All chunks received and SHA-256 verified, but the assembled bytes are
         * encrypted (SPEC §9) and no `key_material` tried so far has decrypted them.
         * Call [ChunkAssembler.tryKey] when a candidate key becomes available —
         * scan order between key and content doesn't matter.
         */
        data class AwaitingKey(val cacheId: ByteArray, val hint: String?, val nonce: ByteArray) : State()
    }

    fun add(payload: TagDropPayload): State = when (payload) {
        is TagDropPayload.Manifest -> {
            manifest = payload
            chunks.clear()
            resolvedContent = null
            computeState()
        }
        is TagDropPayload.Chunk -> {
            val m = manifest
            if (m != null && payload.cacheId.contentEquals(m.cacheId)) {
                chunks[payload.index] = payload.data
            }
            computeState()
        }
        else -> currentState()
    }

    fun currentState(): State = computeState()

    /**
     * Tries [keyMaterial] against the assembled (verified) ciphertext, per SPEC §9's
     * "discovery, not declaration" matching. If it authenticates, the content is
     * decrypted and cached, resolving an [State.AwaitingKey]. Returns the resulting
     * state either way — a non-matching key leaves [State.AwaitingKey] unchanged.
     */
    fun tryKey(keyMaterial: ByteArray): State {
        val m = manifest
        if (m == null || m.encryption != TagDropCodec.ENCRYPTION_AES256GCM || resolvedContent != null) {
            return currentState()
        }
        val nonce = m.nonce ?: return currentState()
        val assembled = assembledAndVerified() ?: return currentState()
        TagDropCodec.decryptAesGcm(assembled, keyMaterial, nonce)?.let { resolvedContent = it }
        return computeState()
    }

    /** Joins all chunks in order and checks `sha256`, or returns null if incomplete or hash-mismatched. */
    private fun assembledAndVerified(): ByteArray? {
        val m = manifest ?: return null
        if (chunks.size < m.chunkCount) return null
        val assembled = (0 until m.chunkCount).map { idx -> chunks[idx] ?: return null }.reduce(ByteArray::plus)
        if (!MessageDigest.getInstance("SHA-256").digest(assembled).contentEquals(m.sha256)) return null
        return assembled
    }

    private fun computeState(): State {
        val m = manifest ?: return State.WaitingForManifest

        if (chunks.size < m.chunkCount) {
            return State.Collecting(chunks.size, m.chunkCount, m.cacheId, m.hint)
        }

        // All chunks present — assemble in order
        val assembled = (0 until m.chunkCount).map { idx ->
            chunks[idx] ?: return State.Collecting(chunks.size, m.chunkCount, m.cacheId, m.hint)
        }.reduce(ByteArray::plus)

        // Integrity check — covers the fully-transmitted bytes, i.e. after compression AND encryption (SPEC §9)
        if (!MessageDigest.getInstance("SHA-256").digest(assembled).contentEquals(m.sha256)) {
            return State.HashMismatch
        }

        val plain = if (m.encryption == TagDropCodec.ENCRYPTION_AES256GCM) {
            resolvedContent ?: return State.AwaitingKey(m.cacheId, m.hint, m.nonce ?: return State.HashMismatch)
        } else {
            assembled
        }

        val content = TagDropCodec.decompressPayload(plain, m.compression)
        return State.Complete(m.cacheId, m.hint, m.filename, m.mimeType, content, m.collectionId, m.collectionLabel, m.collectionTag, m.icon)
    }

    fun reset() {
        manifest = null
        chunks.clear()
        resolvedContent = null
    }

    val hasManifest get() = manifest != null
    val chunksReceived get() = chunks.size
    val chunksNeeded get() = manifest?.chunkCount ?: 0
}
