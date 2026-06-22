package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Exercises [SectorAssembler]'s state machine directly, with hand-rolled [Sector]/[PartMeta]
 * values (rather than going through [TagDropCodec]'s factory functions, which is how
 * TagDropCodecTest.kt already covers most round-trip/erasure-coding/paper scenarios). Every
 * sector here is self-describing (carries its own part_meta inline, SPEC §3) — unlike the old
 * Manifest+Chunk split, there's no "metadata arrives separately from data" ordering to test.
 */
class SectorAssemblerTest {

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Builds a Content reassembled stream (core_meta_item || bulky_meta_item || content, SPEC
     * §4.2) with an empty bulky_meta_item. Core keys used: 3 hint, 4 mime_type, 8 content_sha256,
     * 11 filename, 12 content_compression.
     */
    private fun contentStream(
        content: ByteArray, hint: String? = "test hint", filename: String? = null,
        mimeType: String = "text/plain", compression: Int = TagDropCodec.COMPRESSION_NONE,
        sha: ByteArray? = null,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null,
        preferDeclaredLocation: Boolean = false
    ): ByteArray {
        val core = MiniCbor.encodeMap(listOf(
            3 to hint, 4 to mimeType, 11 to filename,
            12 to compression.takeIf { it != TagDropCodec.COMPRESSION_NONE },
            8 to sha,
            26 to lat, 27 to lng, 48 to radiusM,
            49 to preferDeclaredLocation.takeIf { it }
        ))
        val bulky = MiniCbor.encodeMap(emptyList())
        return core + bulky + content
    }

    /** Slices [stream] into `size`-byte sectors, each wrapped with matching part_meta (SPEC §5). */
    private fun sectorsOf(cacheId: ByteArray?, stream: ByteArray, size: Int, type: Int = TagDropCodec.TYPE_CONTENT): List<Sector> {
        val pieces = stream.toList().chunked(size).map { it.toByteArray() }
        return pieces.mapIndexed { i, data -> Sector(type, PartMeta(cacheId, i, pieces.size, stream.size), data) }
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test fun initialStateIsIdle() {
        val a = SectorAssembler()
        assertEquals(SectorAssembler.State.Idle, a.currentState())
        assertFalse(a.hasPending)
    }

    // ── Single-sector payload ────────────────────────────────────────────────

    @Test fun singleSectorCompletes() {
        val content = "tiny payload".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(content, hint = "test hint")
        val a = SectorAssembler()

        val state = a.add(sectorsOf(cacheId, stream, stream.size).single())
        assertTrue(state is SectorAssembler.State.ContentReady)
        state as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
        assertEquals("text/plain", state.mimeType)
        assertEquals("test hint", state.hint)
        assertFalse("a terminal ContentReady group is dropped from tracking", a.hasPending)
    }

    @Test fun declaredLocationFieldsPropagateToContentReady() {
        val content = "tiny payload".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(
            content, hint = null, lat = -33.8688, lng = 151.2093, radiusM = 25.0, preferDeclaredLocation = true
        )
        val a = SectorAssembler()

        val state = a.add(sectorsOf(cacheId, stream, stream.size).single()) as SectorAssembler.State.ContentReady
        assertEquals(-33.8688, state.lat!!, 0.0)
        assertEquals(151.2093, state.lng!!, 0.0)
        assertEquals(25.0, state.radiusM!!, 0.0)
        assertTrue(state.preferDeclaredLocation)
    }

    // ── In-order multi-sector ─────────────────────────────────────────────────

    @Test fun multiSectorInOrder() {
        val content = "hello world this is some longer test content for chunking".toByteArray()
        val cacheId = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte())
        val sectors = sectorsOf(cacheId, contentStream(content, hint = null, sha = sha256(content)), 8)
        assertTrue(sectors.size > 1)
        val a = SectorAssembler()

        for (i in 0 until sectors.lastIndex) {
            val s = a.add(sectors[i]) as SectorAssembler.State.Collecting
            assertEquals(i + 1, s.received)
            assertEquals(sectors.size, s.total)
        }
        val final = a.add(sectors.last()) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, final.content)
    }

    // ── Order-independent assembly ────────────────────────────────────────────

    @Test fun reverseOrderSectors() {
        val content = "abcdefghijklmnopqrstuvwxyz".toByteArray()
        val cacheId = byteArrayOf(5, 4, 3, 2, 1, 0, 9, 8)
        val sectors = sectorsOf(cacheId, contentStream(content, hint = null, sha = sha256(content)), 5)
        assertTrue(sectors.size > 1)
        val a = SectorAssembler()

        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (s in sectors.reversed()) last = a.add(s)
        assertArrayEquals(content, (last as SectorAssembler.State.ContentReady).content)
    }

    @Test fun shuffledSectors() {
        val content = ByteArray(100) { it.toByte() }
        val cacheId = byteArrayOf(1, 1, 2, 3, 5, 8, 13, 21)
        val sectors = sectorsOf(cacheId, contentStream(content, hint = null, sha = sha256(content)), 10)
        assertTrue(sectors.size > 1)
        val a = SectorAssembler()

        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (s in sectors.shuffled(java.util.Random(42))) last = a.add(s)
        assertArrayEquals(content, (last as SectorAssembler.State.ContentReady).content)
    }

    // ── Missing-index reporting (lets the UI say which sector to scan next) ───

    @Test fun missingIndicesNarrowsAsSectorsArrive() {
        val content = ByteArray(50) { it.toByte() }
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val sectors = sectorsOf(cacheId, contentStream(content, hint = null), 10)
        assertTrue(sectors.size >= 4)
        val a = SectorAssembler()

        // Scan sector index 2 first, out of order — every other index is still missing.
        val afterOne = a.add(sectors[2]) as SectorAssembler.State.Collecting
        assertEquals(sectors.size, afterOne.total)
        assertEquals((0 until sectors.size).filter { it != 2 }, afterOne.missingIndices)

        // A duplicate of the same sector leaves the missing list unaffected.
        val afterDuplicate = a.add(sectors[2]) as SectorAssembler.State.Collecting
        assertEquals(afterOne.missingIndices, afterDuplicate.missingIndices)
    }

    // ── Multi-concurrent tracking (unlike the old single-in-flight assembler) ─

    @Test fun differentCacheIdSectorsAssembleAsIndependentGroups() {
        val contentA = "one two three four five".toByteArray()
        val contentB = "alpha beta gamma delta".toByteArray()
        val cacheIdA = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val cacheIdB = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)
        val sectorsA = sectorsOf(cacheIdA, contentStream(contentA, hint = null, sha = sha256(contentA)), 6)
        val sectorsB = sectorsOf(cacheIdB, contentStream(contentB, hint = null, sha = sha256(contentB)), 6)
        assertTrue(sectorsA.size > 1 && sectorsB.size > 1)

        val a = SectorAssembler()
        // Interleave sectors from two unrelated payloads — each assembles independently (SPEC §5).
        sectorsA.dropLast(1).forEach { a.add(it) }
        sectorsB.dropLast(1).forEach { a.add(it) }
        val completedA = a.add(sectorsA.last()) as SectorAssembler.State.ContentReady
        val completedB = a.add(sectorsB.last()) as SectorAssembler.State.ContentReady

        assertArrayEquals(contentA, completedA.content)
        assertArrayEquals(contentB, completedB.content)
    }

    @Test fun sameCacheIdDifferentTypeAreTrackedAsSeparateGroups() {
        val (paper, paperSectors) = TagDropCodec.createPaper("Label", null, null, emptyList())
        val paperSector = paperSectors.single()
        val contentBytes = contentStream("hello".toByteArray(), hint = null)
        // Force the content sector to carry the same id bytes as the paper's real root_hash,
        // purely to exercise group identity being (type, id) together, not id alone (SPEC §4.1).
        // Content's cache_id isn't verified against its bytes (SPEC §9 allows a random id for
        // override-map/key-only payloads), so the collision has to be made content-side — a
        // forged id on the paper side would now correctly fail root_hash verification.
        val contentSector = Sector(TagDropCodec.TYPE_CONTENT, PartMeta(paper.rootHash, 0, 1, contentBytes.size), contentBytes)

        val a = SectorAssembler()
        val contentState = a.add(contentSector)
        val paperState = a.add(paperSector)
        assertTrue(contentState is SectorAssembler.State.ContentReady)
        assertTrue(paperState is SectorAssembler.State.PaperReady)
    }

    // ── SHA-256 integrity check ───────────────────────────────────────────────

    @Test fun hashMismatchDetected() {
        val content = "correct content".toByteArray()
        val tampered = "tampered content".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        // Stream declares content_sha256 of `tampered`, but carries `content`'s bytes.
        val stream = contentStream(content, hint = null, sha = sha256(tampered))
        val a = SectorAssembler()
        val state = a.add(sectorsOf(cacheId, stream, stream.size).single())
        assertTrue(state is SectorAssembler.State.HashMismatch)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun resetClearsAllState() {
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val sectors = sectorsOf(cacheId, contentStream("data".toByteArray(), hint = null), 2)
        assertTrue(sectors.size > 1)
        val a = SectorAssembler()
        a.add(sectors[0])
        assertTrue(a.hasPending)

        a.reset()
        assertFalse(a.hasPending)
        assertEquals(SectorAssembler.State.Idle, a.currentState())
    }

    @Test fun hasPendingReflectsAnyGroupInFlight() {
        val a = SectorAssembler()
        assertFalse(a.hasPending)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val sectors = sectorsOf(cacheId, contentStream("hello world, multi sector".toByteArray(), hint = null), 6)
        assertTrue(sectors.size > 1)

        a.add(sectors[0])
        assertTrue(a.hasPending)
        sectors.drop(1).forEach { a.add(it) }
        assertFalse("a terminal ContentReady group is dropped from tracking", a.hasPending)
    }

    // ── Compressed payload ────────────────────────────────────────────────────

    @Test fun compressedPayloadDecompressedOnAssembly() {
        val raw = "The quick brown fox jumps over the lazy dog".repeat(5).toByteArray()
        val compr = TagDropCodec.compress(raw)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(compr, hint = null, compression = TagDropCodec.COMPRESSION_DEFLATE, sha = sha256(compr))
        val a = SectorAssembler()
        val state = a.add(sectorsOf(cacheId, stream, stream.size).single()) as SectorAssembler.State.ContentReady
        // SectorAssembler decompresses internally; content should be the raw bytes.
        assertArrayEquals(raw, state.content)
    }

    // ── Malformed assembly ─────────────────────────────────────────────────────

    @Test fun failedStateWhenReassembledBytesAreMalformed() {
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val garbage = byteArrayOf(0xFF.toByte(), 0x00, 0x00) // not a valid core_meta_item
        val a = SectorAssembler()
        val state = a.add(Sector(TagDropCodec.TYPE_CONTENT, PartMeta(cacheId, 0, 1, garbage.size), garbage))
        assertTrue(state is SectorAssembler.State.Failed)
    }

    // ── Hidden override map (SPEC §9) ─────────────────────────────────────────

    /**
     * With `content_compression = deflate`, the assembled bytes (an AES-256-GCM blob) can't
     * decompress as plain content (SPEC §5 step 5) — so the assembler awaits a key once every
     * sector is in.
     */
    @Test fun encryptedContentAwaitsKeyOnceSectorsComplete() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret trail notes".toByteArray())
        val assembled = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_DEFLATE)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(
            assembled, hint = "cover hint", filename = "cover.txt",
            compression = TagDropCodec.COMPRESSION_DEFLATE, sha = sha256(assembled)
        )
        val sectors = sectorsOf(cacheId, stream, 8)
        assertTrue(sectors.size > 1)

        val a = SectorAssembler()
        for (s in sectors.dropLast(1)) {
            assertTrue(a.add(s) is SectorAssembler.State.Collecting)
        }
        val final = a.add(sectors.last())
        assertTrue(final is SectorAssembler.State.AwaitingKey)
        assertArrayEquals(cacheId, (final as SectorAssembler.State.AwaitingKey).cacheId)
    }

    @Test fun tryKeyWithWrongKeyStaysAwaitingKey() {
        val key = TagDropCodec.generateKeyMaterial()
        val wrongKey = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret trail notes".toByteArray())
        val assembled = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_DEFLATE)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(assembled, hint = null, compression = TagDropCodec.COMPRESSION_DEFLATE, sha = sha256(assembled))
        val sectors = sectorsOf(cacheId, stream, 8)

        val a = SectorAssembler()
        sectors.forEach { a.add(it) }
        assertTrue(a.currentState() is SectorAssembler.State.AwaitingKey)

        assertTrue(a.tryKey(wrongKey).isEmpty())
        assertTrue(a.currentState() is SectorAssembler.State.AwaitingKey)
    }

    @Test fun tryKeyWithCorrectKeyResolvesToContentReady() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(hint = "real hint", filename = "secret.txt", content = "secret trail notes".toByteArray())
        val assembled = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_DEFLATE)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(
            assembled, hint = "cover hint", filename = "cover.txt",
            compression = TagDropCodec.COMPRESSION_DEFLATE, sha = sha256(assembled)
        )
        val sectors = sectorsOf(cacheId, stream, 8)

        val a = SectorAssembler()
        sectors.forEach { a.add(it) }

        val resolved = a.tryKey(key)
        assertEquals(1, resolved.size)
        val state = resolved[0]
        assertArrayEquals("secret trail notes".toByteArray(), state.content)
        assertEquals("real hint", state.hint)
        assertEquals("secret.txt", state.filename)
        assertNull(state.pendingOverrideBlob)
    }

    @Test fun tryKeyBeforeSectorsCompleteHasNoEffectButOrderIsIndependent() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret trail notes".toByteArray())
        val assembled = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_DEFLATE)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(assembled, hint = null, compression = TagDropCodec.COMPRESSION_DEFLATE, sha = sha256(assembled))
        val sectors = sectorsOf(cacheId, stream, 8)
        assertTrue(sectors.size > 1)

        val a = SectorAssembler()
        a.add(sectors[0])

        // The key can be scanned before all sectors have arrived — trying it then has no effect
        // yet (no fully-collected group to check against), but isn't an error either.
        assertTrue(a.tryKey(key).isEmpty())

        sectors.drop(1).forEach { a.add(it) }
        assertTrue(a.currentState() is SectorAssembler.State.AwaitingKey)

        val resolved = a.tryKey(key)
        assertEquals(1, resolved.size)
        assertArrayEquals("secret trail notes".toByteArray(), resolved[0].content)
    }

    /**
     * With `content_compression = none`, the assembled bytes (an AES-256-GCM blob) "decompress"
     * trivially (identity) — the assembler reaches ContentReady immediately, showing the raw
     * bytes as a cover with the blob kept as [SectorAssembler.State.ContentReady.pendingOverrideBlob].
     * That state is terminal: the assembler stops tracking the group, and (per
     * TagDropCodecTest's encryptedPlainContentShowsCoverWithPendingBlob) it's the *caller's* job
     * to try keys against the carried blob directly — not [SectorAssembler.tryKey], which only
     * ever resolves groups still awaiting a key.
     */
    @Test fun contentReadyWithPendingOverrideBlobIsDroppedFromTracking() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(hint = "real hint", content = "secret trail notes".toByteArray())
        val assembled = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_NONE)
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val stream = contentStream(assembled, hint = "cover hint", compression = TagDropCodec.COMPRESSION_NONE, sha = sha256(assembled))

        val a = SectorAssembler()
        val state = a.add(sectorsOf(cacheId, stream, stream.size).single())
        assertTrue(state is SectorAssembler.State.ContentReady)
        state as SectorAssembler.State.ContentReady
        assertEquals("cover hint", state.hint)
        assertArrayEquals(assembled, state.pendingOverrideBlob)
        assertFalse(a.hasPending)
        assertTrue(a.tryKey(key).isEmpty())
    }
}
