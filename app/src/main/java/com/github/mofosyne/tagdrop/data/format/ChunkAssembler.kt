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
            val content: ByteArray,  // already decompressed
            val collectionId: ByteArray?,
            val collectionLabel: String?,
            val collectionTag: String?,
            val icon: String?
        ) : State()

        /** All chunks received but assembly failed integrity check. */
        object HashMismatch : State()
    }

    fun add(payload: TagDropPayload): State = when (payload) {
        is TagDropPayload.Manifest -> {
            manifest = payload
            chunks.clear()
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

    private fun computeState(): State {
        val m = manifest ?: return State.WaitingForManifest

        if (chunks.size < m.chunkCount) {
            return State.Collecting(chunks.size, m.chunkCount, m.cacheId, m.hint)
        }

        // All chunks present — assemble in order
        val assembled = (0 until m.chunkCount).map { idx ->
            chunks[idx] ?: return State.Collecting(chunks.size, m.chunkCount, m.cacheId, m.hint)
        }.reduce(ByteArray::plus)

        // Integrity check
        if (!MessageDigest.getInstance("SHA-256").digest(assembled).contentEquals(m.sha256)) {
            return State.HashMismatch
        }

        val content = TagDropCodec.decompressPayload(assembled, m.compression)
        return State.Complete(m.cacheId, m.hint, m.filename, m.mimeType, content, m.collectionId, m.collectionLabel, m.collectionTag, m.icon)
    }

    fun reset() {
        manifest = null
        chunks.clear()
    }

    val hasManifest get() = manifest != null
    val chunksReceived get() = chunks.size
    val chunksNeeded get() = manifest?.chunkCount ?: 0
}
