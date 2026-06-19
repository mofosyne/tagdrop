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

    /** Set once a `key_material` (SPEC §9) successfully decrypts the assembled bytes as an override map. */
    private var resolvedOverride: TagDropPayload.OverrideMap? = null

    sealed class State {
        /** No manifest scanned yet. */
        object WaitingForManifest : State()

        /** Manifest received; still collecting chunks. [missingIndices] is sorted ascending. */
        data class Collecting(
            val received: Int,
            val total: Int,
            val cacheId: ByteArray,
            val hint: String?,
            val missingIndices: List<Int>
        ) : State() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Collecting
                if (received != other.received) return false
                if (total != other.total) return false
                if (!cacheId.contentEquals(other.cacheId)) return false
                if (hint != other.hint) return false
                if (missingIndices != other.missingIndices) return false
                return true
            }

            override fun hashCode(): Int {
                var result = received
                result = 31 * result + total
                result = 31 * result + cacheId.contentHashCode()
                result = 31 * result + (hint?.hashCode() ?: 0)
                result = 31 * result + missingIndices.hashCode()
                return result
            }
        }

        /** All chunks received and SHA-256 verified. Content is ready. */
        data class Complete(
            val cacheId: ByteArray,
            val hint: String?,
            val filename: String?,
            val mimeType: String,
            val content: ByteArray,  // resolved (cover, or override-merged) content
            val collectionId: ByteArray?,
            val collectionLabel: String?,
            val collectionTag: String?,
            val icon: String?,
            /**
             * The assembled bytes, if >= [TagDropCodec.OVERRIDE_BLOB_MIN_BYTES] and not yet
             * resolved as an override map — a candidate for [tryKey] to "self-correct"
             * [content]/[hint]/[filename]/[mimeType] later (SPEC §9). Null once resolved.
             */
            val pendingOverrideBlob: ByteArray? = null,
            /** Compression to apply when decoding [pendingOverrideBlob]'s plaintext, if non-null (SPEC §9). */
            val pendingOverrideCompression: Int = TagDropCodec.COMPRESSION_NONE
        ) : State() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Complete
                if (!cacheId.contentEquals(other.cacheId)) return false
                if (hint != other.hint) return false
                if (filename != other.filename) return false
                if (mimeType != other.mimeType) return false
                if (!content.contentEquals(other.content)) return false
                if (!collectionId.contentEquals(other.collectionId)) return false
                if (collectionLabel != other.collectionLabel) return false
                if (collectionTag != other.collectionTag) return false
                if (icon != other.icon) return false
                if (!pendingOverrideBlob.contentEquals(other.pendingOverrideBlob)) return false
                if (pendingOverrideCompression != other.pendingOverrideCompression) return false
                return true
            }

            override fun hashCode(): Int {
                var result = cacheId.contentHashCode()
                result = 31 * result + (hint?.hashCode() ?: 0)
                result = 31 * result + (filename?.hashCode() ?: 0)
                result = 31 * result + mimeType.hashCode()
                result = 31 * result + content.contentHashCode()
                result = 31 * result + (collectionId?.contentHashCode() ?: 0)
                result = 31 * result + (collectionLabel?.hashCode() ?: 0)
                result = 31 * result + (collectionTag?.hashCode() ?: 0)
                result = 31 * result + (icon?.hashCode() ?: 0)
                result = 31 * result + (pendingOverrideBlob?.contentHashCode() ?: 0)
                result = 31 * result + pendingOverrideCompression
                return result
            }
        }

        /** All chunks received but assembly failed integrity check. */
        object HashMismatch : State()

        /**
         * All chunks received and SHA-256 verified, but the assembled bytes could not be
         * decompressed as plain `content` per `compression` (SPEC §5 step 5) — they must be
         * a hidden override-map blob (SPEC §9), and no `key_material` tried so far has
         * decrypted them. Call [ChunkAssembler.tryKey] when a candidate key becomes
         * available — scan order between key and content doesn't matter.
         */
        data class AwaitingKey(val cacheId: ByteArray, val hint: String?) : State()
    }

    fun add(payload: TagDropPayload): State = when (payload) {
        is TagDropPayload.Manifest -> {
            manifest = payload
            chunks.clear()
            resolvedOverride = null
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
     * Tries [keyMaterial] against the assembled (verified) bytes as a hidden override-map
     * blob, per SPEC §9's "discovery, not declaration" matching (SPEC §5 step 5). If it
     * authenticates, the override map's present fields overlay the manifest's clear fields —
     * resolving an [State.AwaitingKey], or "self-correcting" a [State.Complete] reached via
     * the cover-content interpretation. Returns the resulting state either way — a
     * non-matching key leaves the state unchanged.
     */
    fun tryKey(keyMaterial: ByteArray): State {
        val m = manifest ?: return currentState()
        if (resolvedOverride != null) return currentState()
        val assembled = assembledAndVerified() ?: return currentState()
        if (assembled.size < TagDropCodec.OVERRIDE_BLOB_MIN_BYTES) return currentState()
        TagDropCodec.tryDecryptOverrideMap(assembled, keyMaterial, m.compression)?.let { resolvedOverride = it }
        return computeState()
    }

    /** 0-based indices in `0 until total` not yet received — lets the UI say which to scan next. */
    private fun missingIndices(total: Int): List<Int> = (0 until total).filterNot { chunks.containsKey(it) }

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
            return State.Collecting(chunks.size, m.chunkCount, m.cacheId, m.hint, missingIndices(m.chunkCount))
        }

        // All chunks present — assemble in order
        val assembled = (0 until m.chunkCount).map { idx ->
            chunks[idx] ?: return State.Collecting(chunks.size, m.chunkCount, m.cacheId, m.hint, missingIndices(m.chunkCount))
        }.reduce(ByteArray::plus)

        // Integrity check — covers the fully-transmitted (possibly encrypted) bytes (SPEC §9)
        if (!MessageDigest.getInstance("SHA-256").digest(assembled).contentEquals(m.sha256)) {
            return State.HashMismatch
        }

        val override = resolvedOverride
        if (override != null) {
            return State.Complete(
                cacheId = m.cacheId,
                hint = override.hint ?: m.hint,
                filename = override.filename ?: m.filename,
                mimeType = override.mimeType ?: m.mimeType,
                content = override.content ?: ByteArray(0),
                collectionId = m.collectionId,
                collectionLabel = m.collectionLabel,
                collectionTag = m.collectionTag,
                icon = m.icon,
                pendingOverrideBlob = null
            )
        }

        // SPEC §5 step 5: try the cover/plain-content interpretation per `compression`.
        val cover = runCatching { TagDropCodec.decompressPayload(assembled, m.compression) }.getOrNull()
            ?: return State.AwaitingKey(m.cacheId, m.hint)

        val pendingBlob = assembled.takeIf { it.size >= TagDropCodec.OVERRIDE_BLOB_MIN_BYTES }
        return State.Complete(
            cacheId = m.cacheId,
            hint = m.hint,
            filename = m.filename,
            mimeType = m.mimeType,
            content = cover,
            collectionId = m.collectionId,
            collectionLabel = m.collectionLabel,
            collectionTag = m.collectionTag,
            icon = m.icon,
            pendingOverrideBlob = pendingBlob,
            pendingOverrideCompression = if (pendingBlob != null) m.compression else TagDropCodec.COMPRESSION_NONE
        )
    }

    fun reset() {
        manifest = null
        chunks.clear()
        resolvedOverride = null
    }

    val hasManifest get() = manifest != null
    val chunksReceived get() = chunks.size
    val chunksNeeded get() = manifest?.chunkCount ?: 0
}
