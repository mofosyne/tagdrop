package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ChunkAssemblerTest {

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun manifest(cacheId: ByteArray, content: ByteArray, chunkCount: Int) =
        TagDropPayload.Manifest(
            cacheId     = cacheId,
            hint        = "test hint",
            filename    = null,
            mimeType    = "text/plain",
            compression = TagDropCodec.COMPRESSION_NONE,
            chunkCount  = chunkCount,
            totalBytes  = content.size,
            sha256      = sha256(content)
        )

    private fun chunks(cacheId: ByteArray, content: ByteArray, size: Int): List<TagDropPayload.Chunk> {
        val pieces = content.toList().chunked(size).map { it.toByteArray() }
        return pieces.mapIndexed { i, data -> TagDropPayload.Chunk(cacheId, i, data) }
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test fun initialStateIsWaiting() {
        val a = ChunkAssembler()
        assertTrue(a.currentState() is ChunkAssembler.State.WaitingForManifest)
        assertFalse(a.hasManifest)
        assertEquals(0, a.chunksReceived)
    }

    // ── Single-chunk payload ──────────────────────────────────────────────────

    @Test fun singleChunkCompletes() {
        val content  = "tiny payload".toByteArray()
        val cacheId  = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val a        = ChunkAssembler()
        a.add(manifest(cacheId, content, 1))

        val state = a.add(TagDropPayload.Chunk(cacheId, 0, content))
        assertTrue(state is ChunkAssembler.State.Complete)
        assertArrayEquals(content, (state as ChunkAssembler.State.Complete).content)
        assertEquals("text/plain", state.mimeType)
        assertEquals("test hint", state.hint)
    }

    // ── In-order multi-chunk ──────────────────────────────────────────────────

    @Test fun manifestThenChunksInOrder() {
        val content = "hello world this is some longer test content for chunking".toByteArray()
        val cacheId = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte())
        val parts   = chunks(cacheId, content, 8)
        val a       = ChunkAssembler()
        a.add(manifest(cacheId, content, parts.size))

        for (i in 0 until parts.lastIndex) {
            val s = a.add(parts[i]) as ChunkAssembler.State.Collecting
            assertEquals(i + 1, s.received)
            assertEquals(parts.size, s.total)
        }
        val final = a.add(parts.last()) as ChunkAssembler.State.Complete
        assertArrayEquals(content, final.content)
    }

    // ── Order-independent assembly ────────────────────────────────────────────

    @Test fun reverseOrderChunks() {
        val content = "abcdefghijklmnopqrstuvwxyz".toByteArray()
        val cacheId = byteArrayOf(5, 4, 3, 2, 1, 0, 9, 8.toByte())
        val parts   = chunks(cacheId, content, 5)
        val a       = ChunkAssembler()
        a.add(manifest(cacheId, content, parts.size))

        for (chunk in parts.reversed()) a.add(chunk)
        assertArrayEquals(content, (a.currentState() as ChunkAssembler.State.Complete).content)
    }

    @Test fun shuffledChunks() {
        val content = ByteArray(100) { it.toByte() }
        val cacheId = byteArrayOf(1, 1, 2, 3, 5, 8, 13, 21)
        val parts   = chunks(cacheId, content, 10)
        val a       = ChunkAssembler()
        a.add(manifest(cacheId, content, parts.size))

        // Add in shuffled order: 3,7,1,5,0,9,2,4,6,8
        listOf(3, 7, 1, 5, 0, 9, 2, 4, 6, 8).forEach { a.add(parts[it]) }
        assertArrayEquals(content, (a.currentState() as ChunkAssembler.State.Complete).content)
    }

    // ── Chunks before manifest are discarded ──────────────────────────────────

    @Test fun chunksArrivedBeforeManifestAreLost() {
        val content = "some test content".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parts   = chunks(cacheId, content, 5)
        val a       = ChunkAssembler()

        // Chunks before manifest — ignored (no manifest to link them to)
        for (chunk in parts) {
            assertTrue(a.add(chunk) is ChunkAssembler.State.WaitingForManifest)
        }

        // Manifest arrives: chunks.clear() is called, received = 0
        val s = a.add(manifest(cacheId, content, parts.size)) as ChunkAssembler.State.Collecting
        assertEquals(0, s.received)
    }

    // ── Chunks from different cache are ignored ───────────────────────────────

    @Test fun ignoresChunksFromDifferentCache() {
        val content  = "one two three".toByteArray()
        val cacheId  = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val otherId  = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        val a        = ChunkAssembler()
        a.add(manifest(cacheId, content, 2))

        // Chunk with wrong cacheId is silently ignored
        a.add(TagDropPayload.Chunk(otherId, 0, content))
        val s = a.currentState() as ChunkAssembler.State.Collecting
        assertEquals(0, s.received)
    }

    // ── SHA-256 integrity check ───────────────────────────────────────────────

    @Test fun hashMismatchDetected() {
        val content  = "correct content".toByteArray()
        val tampered = "tampered content".toByteArray()
        val cacheId  = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        // Manifest advertises hash of `tampered`, but we send `content`
        val m = TagDropPayload.Manifest(
            cacheId = cacheId, hint = null, filename = null, mimeType = "text/plain",
            compression = 0, chunkCount = 1, totalBytes = content.size,
            sha256 = sha256(tampered)
        )
        val a = ChunkAssembler()
        a.add(m)
        val state = a.add(TagDropPayload.Chunk(cacheId, 0, content))
        assertTrue(state is ChunkAssembler.State.HashMismatch)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun resetClearsAllState() {
        val content = "data".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val a = ChunkAssembler()
        a.add(manifest(cacheId, content, 2))
        a.add(TagDropPayload.Chunk(cacheId, 0, content))
        assertTrue(a.hasManifest)

        a.reset()
        assertFalse(a.hasManifest)
        assertEquals(0, a.chunksReceived)
        assertEquals(0, a.chunksNeeded)
        assertTrue(a.currentState() is ChunkAssembler.State.WaitingForManifest)
    }

    // ── Compressed payload ────────────────────────────────────────────────────

    @Test fun compressedPayloadDecompressedOnAssembly() {
        val raw      = "The quick brown fox jumps over the lazy dog".repeat(5).toByteArray()
        val compr    = TagDropCodec.compress(raw)
        val cacheId  = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val m = TagDropPayload.Manifest(
            cacheId = cacheId, hint = null, filename = null, mimeType = "text/plain",
            compression = TagDropCodec.COMPRESSION_DEFLATE,
            chunkCount = 1, totalBytes = compr.size,
            sha256 = sha256(compr)
        )
        val a = ChunkAssembler()
        a.add(m)
        val state = a.add(TagDropPayload.Chunk(cacheId, 0, compr)) as ChunkAssembler.State.Complete
        // ChunkAssembler decompresses internally; content should be the raw bytes
        assertArrayEquals(raw, state.content)
    }

    // ── Encryption (SPEC §9) ──────────────────────────────────────────────────

    private fun encryptedManifest(cacheId: ByteArray, ciphertext: ByteArray, nonce: ByteArray, chunkCount: Int) =
        TagDropPayload.Manifest(
            cacheId     = cacheId,
            hint        = "test hint",
            filename    = "secret.txt",
            mimeType    = "text/plain",
            compression = TagDropCodec.COMPRESSION_NONE,
            chunkCount  = chunkCount,
            totalBytes  = ciphertext.size,
            sha256      = sha256(ciphertext),
            encryption  = TagDropCodec.ENCRYPTION_AES256GCM,
            nonce       = nonce
        )

    @Test fun encryptedManifestAwaitsKeyOnceChunksComplete() {
        val key       = TagDropCodec.generateKeyMaterial()
        val nonce     = TagDropCodec.generateNonce()
        val content   = "secret trail notes".toByteArray()
        val ciphertext = TagDropCodec.encryptAesGcm(content, key, nonce)
        val cacheId   = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parts     = chunks(cacheId, ciphertext, 8)

        val a = ChunkAssembler()
        a.add(encryptedManifest(cacheId, ciphertext, nonce, parts.size))
        for (chunk in parts.dropLast(1)) {
            assertTrue(a.add(chunk) is ChunkAssembler.State.Collecting)
        }

        val final = a.add(parts.last())
        assertTrue(final is ChunkAssembler.State.AwaitingKey)
        assertArrayEquals(cacheId, (final as ChunkAssembler.State.AwaitingKey).cacheId)
        assertArrayEquals(nonce, final.nonce)
    }

    @Test fun tryKeyWithWrongKeyStaysAwaitingKey() {
        val key        = TagDropCodec.generateKeyMaterial()
        val wrongKey   = TagDropCodec.generateKeyMaterial()
        val nonce      = TagDropCodec.generateNonce()
        val content    = "secret trail notes".toByteArray()
        val ciphertext = TagDropCodec.encryptAesGcm(content, key, nonce)
        val cacheId    = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parts      = chunks(cacheId, ciphertext, 8)

        val a = ChunkAssembler()
        a.add(encryptedManifest(cacheId, ciphertext, nonce, parts.size))
        parts.forEach { a.add(it) }
        assertTrue(a.currentState() is ChunkAssembler.State.AwaitingKey)

        assertTrue(a.tryKey(wrongKey) is ChunkAssembler.State.AwaitingKey)
    }

    @Test fun tryKeyWithCorrectKeyResolvesToComplete() {
        val key        = TagDropCodec.generateKeyMaterial()
        val nonce      = TagDropCodec.generateNonce()
        val content    = "secret trail notes".toByteArray()
        val ciphertext = TagDropCodec.encryptAesGcm(content, key, nonce)
        val cacheId    = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parts      = chunks(cacheId, ciphertext, 8)

        val a = ChunkAssembler()
        a.add(encryptedManifest(cacheId, ciphertext, nonce, parts.size))
        parts.forEach { a.add(it) }

        val state = a.tryKey(key)
        assertTrue(state is ChunkAssembler.State.Complete)
        assertArrayEquals(content, (state as ChunkAssembler.State.Complete).content)
        assertEquals("test hint", state.hint)
        assertEquals("secret.txt", state.filename)
    }

    @Test fun tryKeyBeforeChunksCompleteHasNoEffectButOrderIsIndependent() {
        val key        = TagDropCodec.generateKeyMaterial()
        val nonce      = TagDropCodec.generateNonce()
        val content    = "secret trail notes".toByteArray()
        val ciphertext = TagDropCodec.encryptAesGcm(content, key, nonce)
        val cacheId    = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parts      = chunks(cacheId, ciphertext, 8)

        val a = ChunkAssembler()
        a.add(encryptedManifest(cacheId, ciphertext, nonce, parts.size))

        // The key can be scanned before all chunks have arrived — trying it then
        // has no effect yet (nothing to decrypt), but isn't an error either.
        assertTrue(a.tryKey(key) is ChunkAssembler.State.Collecting)

        parts.forEach { a.add(it) }
        assertTrue(a.currentState() is ChunkAssembler.State.AwaitingKey)

        // Trying the same key again now that chunks are complete resolves it.
        val state = a.tryKey(key)
        assertTrue(state is ChunkAssembler.State.Complete)
        assertArrayEquals(content, (state as ChunkAssembler.State.Complete).content)
    }
}
