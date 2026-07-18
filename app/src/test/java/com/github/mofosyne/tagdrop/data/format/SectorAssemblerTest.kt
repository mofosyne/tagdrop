package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Exercises [SectorAssembler]'s state machine directly, with hand-built [ScannedRecord]/Split
 * Wrapper fragment CBOR bytes (rather than going through [TagDropCodec]'s factory functions,
 * which is how TagDropCodecTest.kt already covers most round-trip/erasure-coding/paper
 * scenarios) — isolates the assembler's own reassembly/grouping/state logic from the codec's
 * encode-side field layout. Every fragment here is self-describing (carries its own group_id
 * inline, SPEC §5), matching the real wire format.
 */
class SectorAssemblerTest {

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** A minimal Content-Preview Record (Type 1, SPEC §3.1) — only the fields these tests need. */
    private fun previewBytes(
        cacheId: ByteArray?, hint: String?, mimeType: String = "text/plain", filename: String? = null,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null, preferDeclaredLocation: Boolean = false
    ): ByteArray = MiniCbor.encodeUInt(1) + MiniCbor.encodeMap(listOf(
        1 to cacheId, 3 to hint, 5 to mimeType, 7 to filename,
        23 to lat, 25 to lng, 27 to radiusM, 29 to (true.takeIf { preferDeclaredLocation })
    ))

    /** A minimal Content-Body Record (Type 3, SPEC §3.2) carrying [content]. */
    private fun bodyBytes(content: ByteArray): ByteArray = MiniCbor.encodeUInt(3) + MiniCbor.encodeMap(listOf(1 to content))

    /** A Compress Wrapper Record (Type 8, QDEF-SPEC.md §4.1) DEFLATE-wrapping [inner]. */
    private fun compressWrapBytes(inner: ByteArray): ByteArray = MiniCbor.encodeUInt(8) + MiniCbor.encodeMap(listOf(2 to TagDropCodec.compress(inner)))

    @Suppress("UNCHECKED_CAST")
    private fun recordOf(previewRaw: ByteArray, secondRaw: ByteArray?): ScannedRecord {
        val previewResult = MiniCbor.decodeSequencePrefix(previewRaw, 2)
        val previewTypeId = (previewResult.items[0] as? Int) ?: (previewResult.items[0] as? Long)?.toInt() ?: 0
        val previewMap = previewResult.items[1] as? Map<Int, Any> ?: MiniCbor.decodeMap(previewRaw)
        val secondResult = secondRaw?.let { MiniCbor.decodeSequencePrefix(it, 2) }
        val secondTypeId = secondResult?.items?.getOrNull(0)?.let { (it as? Int) ?: (it as? Long)?.toInt() }
        val secondMap = secondResult?.items?.getOrNull(1) as? Map<Int, Any> ?: secondRaw?.let { MiniCbor.decodeMap(it) }
        return ScannedRecord(PayloadKind.CONTENT, previewRaw, previewMap, secondTypeId, secondRaw, secondMap)
    }

    /** A Split Wrapper Record (Type 2, QDEF-SPEC.md §4.1, SPEC §5) fragment's raw bytes. */
    private fun splitFragmentBytes(groupId: ByteArray, index: Int, count: Int, data: ByteArray, total: Int, parity: Boolean = false): ByteArray =
        MiniCbor.encodeUInt(2) + MiniCbor.encodeMap(listOf(2 to groupId, 4 to index, 6 to count, 8 to data, 9 to total, 11 to (1.takeIf { parity })))

    /**
     * Splits [bodyRaw] into [chunkCount]-many Split Wrapper fragment [ScannedRecord]s sharing
     * [previewRaw], each carrying its own `group_id` (SHA-256([bodyRaw])[0:8]) — mirrors
     * [TagDropCodec]'s own `splitFragments`. Appends a trailing XOR parity fragment when
     * [withParity].
     */
    private fun splitRecords(previewRaw: ByteArray, bodyRaw: ByteArray, chunkCount: Int, withParity: Boolean = false): List<ScannedRecord> {
        val groupId = sha256(bodyRaw).copyOf(8)
        val chunkLen = (bodyRaw.size + chunkCount - 1) / chunkCount
        val dataRecords = (0 until chunkCount).map { i ->
            val start = minOf(i * chunkLen, bodyRaw.size)
            val end = minOf(start + chunkLen, bodyRaw.size)
            val fragRaw = splitFragmentBytes(groupId, i, chunkCount, bodyRaw.copyOfRange(start, end), bodyRaw.size)
            recordOf(previewRaw, fragRaw)
        }
        if (!withParity) return dataRecords
        val parity = ByteArray(chunkLen)
        for (i in 0 until chunkCount) {
            val start = minOf(i * chunkLen, bodyRaw.size)
            val end = minOf(start + chunkLen, bodyRaw.size)
            for (j in start until end) parity[j - start] = (parity[j - start].toInt() xor bodyRaw[j].toInt()).toByte()
        }
        val parityRaw = splitFragmentBytes(groupId, chunkCount, chunkCount, parity, bodyRaw.size, parity = true)
        return dataRecords + recordOf(previewRaw, parityRaw)
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test fun initialStateIsIdle() {
        val a = SectorAssembler()
        assertEquals(SectorAssembler.State.Idle, a.currentState())
        assertFalse(a.hasPending)
    }

    // ── Single-code payload ──────────────────────────────────────────────────

    @Test fun singleCodeCompletes() {
        val content = "tiny payload".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val record = recordOf(previewBytes(cacheId, "test hint"), bodyBytes(content))
        val a = SectorAssembler()

        val state = a.add(record)
        assertTrue(state is SectorAssembler.State.ContentReady)
        state as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
        assertEquals("text/plain", state.mimeType)
        assertEquals("test hint", state.hint)
        assertFalse("a single-code payload never enters group tracking", a.hasPending)
    }

    @Test fun declaredLocationFieldsPropagateToContentReady() {
        val content = "tiny payload".toByteArray()
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val record = recordOf(
            previewBytes(cacheId, null, lat = -33.8688, lng = 151.2093, radiusM = 25.0, preferDeclaredLocation = true),
            bodyBytes(content)
        )
        val a = SectorAssembler()

        val state = a.add(record) as SectorAssembler.State.ContentReady
        assertEquals(-33.8688, state.lat!!, 0.0)
        assertEquals(151.2093, state.lng!!, 0.0)
        assertEquals(25.0, state.radiusM!!, 0.0)
        assertTrue(state.preferDeclaredLocation)
    }

    @Test fun keyOnlyCodeHasNoBody() {
        val key = TagDropCodec.generateKeyMaterial()
        val preview = MiniCbor.encodeUInt(1) + MiniCbor.encodeMap(listOf(3 to "key hint", 33 to key))
        val state = SectorAssembler().add(recordOf(preview, null)) as SectorAssembler.State.ContentReady
        assertTrue(state.content.isEmpty())
        assertArrayEquals(key, state.keyMaterial)
        assertNull(state.bodyRaw)
    }

    // ── In-order multi-code Split (SPEC §5) ─────────────────────────────────────

    @Test fun multiCodeInOrder() {
        val content = "hello world this is some longer test content for chunking".toByteArray()
        val cacheId = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte())
        val preview = previewBytes(cacheId, null)
        val records = splitRecords(preview, bodyBytes(content), chunkCount = 6)
        assertTrue(records.size > 1)
        val a = SectorAssembler()

        for (i in 0 until records.lastIndex) {
            val s = a.add(records[i]) as SectorAssembler.State.Collecting
            assertEquals(i + 1, s.received)
            assertEquals(records.size, s.total)
        }
        val final = a.add(records.last()) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, final.content)
    }

    // ── Order-independent assembly ────────────────────────────────────────────

    @Test fun reverseOrderCodes() {
        val content = "abcdefghijklmnopqrstuvwxyz".toByteArray()
        val cacheId = byteArrayOf(5, 4, 3, 2, 1, 0, 9, 8)
        val records = splitRecords(previewBytes(cacheId, null), bodyBytes(content), chunkCount = 6)
        assertTrue(records.size > 1)
        val a = SectorAssembler()

        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records.reversed()) last = a.add(r)
        assertArrayEquals(content, (last as SectorAssembler.State.ContentReady).content)
    }

    @Test fun shuffledCodes() {
        val content = ByteArray(100) { it.toByte() }
        val cacheId = byteArrayOf(1, 1, 2, 3, 5, 8, 13, 21)
        val records = splitRecords(previewBytes(cacheId, null), bodyBytes(content), chunkCount = 10)
        assertTrue(records.size > 1)
        val a = SectorAssembler()

        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records.shuffled(java.util.Random(42))) last = a.add(r)
        assertArrayEquals(content, (last as SectorAssembler.State.ContentReady).content)
    }

    // ── Missing-index reporting (lets the UI say which code to scan next) ─────

    @Test fun missingIndicesNarrowsAsCodesArrive() {
        val content = ByteArray(50) { it.toByte() }
        val cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val records = splitRecords(previewBytes(cacheId, null), bodyBytes(content), chunkCount = 5)
        assertTrue(records.size >= 4)
        val a = SectorAssembler()

        // Scan index 2 first, out of order — every other index is still missing.
        val afterOne = a.add(records[2]) as SectorAssembler.State.Collecting
        assertEquals(records.size, afterOne.total)
        assertEquals((0 until records.size).filter { it != 2 }, afterOne.missingIndices)

        // A duplicate of the same fragment leaves the missing list unaffected.
        val afterDuplicate = a.add(records[2]) as SectorAssembler.State.Collecting
        assertEquals(afterOne.missingIndices, afterDuplicate.missingIndices)
    }

    // ── Multi-concurrent tracking ─────────────────────────────────────────────

    @Test fun differentGroupIdCodesAssembleAsIndependentGroups() {
        val contentA = "one two three four five".toByteArray()
        val contentB = "alpha beta gamma delta".toByteArray()
        val recordsA = splitRecords(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null), bodyBytes(contentA), chunkCount = 4)
        val recordsB = splitRecords(previewBytes(byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16), null), bodyBytes(contentB), chunkCount = 4)
        assertTrue(recordsA.size > 1 && recordsB.size > 1)

        val a = SectorAssembler()
        // Interleave fragments from two unrelated payloads — each assembles independently (SPEC §5).
        recordsA.dropLast(1).forEach { a.add(it) }
        recordsB.dropLast(1).forEach { a.add(it) }
        val completedA = a.add(recordsA.last()) as SectorAssembler.State.ContentReady
        val completedB = a.add(recordsB.last()) as SectorAssembler.State.ContentReady

        assertArrayEquals(contentA, completedA.content)
        assertArrayEquals(contentB, completedB.content)
    }

    // ── group_id integrity check (SPEC §5.1) ────────────────────────────────────

    @Test fun groupIdMismatchDetected() {
        val content = ByteArray(60) { it.toByte() }
        val records = splitRecords(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null), bodyBytes(content), chunkCount = 4).toMutableList()
        // Tamper one fragment's data in place, without touching its declared group_id —
        // the reassembled bytes no longer hash to the group_id every fragment still claims.
        val frag = TagDropCodec.splitFragmentOf(records[1])!!
        val tamperedData = frag.data.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tamperedRaw = splitFragmentBytes(frag.groupId, frag.index, frag.count, tamperedData, frag.total)
        records[1] = recordOf(records[1].previewRaw, tamperedRaw)

        val a = SectorAssembler()
        var state: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records) state = a.add(r)
        assertTrue("expected HashMismatch, got $state", state is SectorAssembler.State.HashMismatch)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun resetClearsAllState() {
        val records = splitRecords(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null), bodyBytes("data".toByteArray()), chunkCount = 3)
        assertTrue(records.size > 1)
        val a = SectorAssembler()
        a.add(records[0])
        assertTrue(a.hasPending)

        a.reset()
        assertFalse(a.hasPending)
        assertEquals(SectorAssembler.State.Idle, a.currentState())
    }

    @Test fun hasPendingReflectsAnyGroupInFlight() {
        val a = SectorAssembler()
        assertFalse(a.hasPending)
        val records = splitRecords(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null), bodyBytes("hello world, multi code".toByteArray()), chunkCount = 6)
        assertTrue(records.size > 1)

        a.add(records[0])
        assertTrue(a.hasPending)
        records.drop(1).forEach { a.add(it) }
        assertFalse("a terminal ContentReady group is dropped from tracking", a.hasPending)
    }

    // ── Compressed payload (Compress Wrapper, QDEF Type 8) ──────────────────────

    @Test fun compressedPayloadDecompressedOnAssembly() {
        val raw = "The quick brown fox jumps over the lazy dog".repeat(5).toByteArray()
        val record = recordOf(
            previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null),
            compressWrapBytes(bodyBytes(raw))
        )
        val state = SectorAssembler().add(record) as SectorAssembler.State.ContentReady
        // SectorAssembler decompresses internally; content should be the raw bytes.
        assertArrayEquals(raw, state.content)
    }

    // ── Malformed assembly ─────────────────────────────────────────────────────

    @Test fun failedStateWhenBodyTypeIdIsWrong() {
        // A well-formed Record, but not a Content-Body (Type 3) — decodeRaw would happily hand
        // this to the assembler (only the Preview's Type ID gates the initial scan dispatch).
        val bogusBody = MiniCbor.encodeUInt(999) + MiniCbor.encodeMap(listOf(1 to "not content-body".toByteArray()))
        val record = recordOf(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null), bogusBody)
        val state = SectorAssembler().add(record)
        assertTrue(state is SectorAssembler.State.Failed)
    }

    // ── Hidden override map (SPEC §9) ─────────────────────────────────────────

    /**
     * Once every Split fragment is in, the assembler resolves straight to [SectorAssembler.
     * State.ContentReady] — there is no separate "awaiting key" state (unlike the old envelope):
     * the reassembled bytes (an AES-256-GCM blob) are shown as-is via [SectorAssembler.State.
     * ContentReady.content], with [SectorAssembler.State.ContentReady.pendingOverrideBlob] set
     * as the candidate to unlock.
     */
    @Test fun encryptedContentResolvesWithPendingBlobOnceCodesComplete() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret trail notes".toByteArray())
        val blob = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_DEFLATE)
        val preview = previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "cover hint", filename = "cover.txt")
        val records = splitRecords(preview, bodyBytes(blob), chunkCount = 4)
        assertTrue(records.size > 1)

        val a = SectorAssembler()
        for (r in records.dropLast(1)) assertTrue(a.add(r) is SectorAssembler.State.Collecting)
        val final = a.add(records.last())
        assertTrue(final is SectorAssembler.State.ContentReady)
        final as SectorAssembler.State.ContentReady
        assertArrayEquals(blob, final.content)
        assertArrayEquals(blob, final.pendingOverrideBlob)
        assertFalse("terminal ContentReady is dropped from tracking, even still-locked", a.hasPending)
    }

    @Test fun tryKeyBeforeGroupCompletesHasNoEffectYet() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret trail notes".toByteArray())
        val blob = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_DEFLATE)
        val records = splitRecords(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), null), bodyBytes(blob), chunkCount = 4)
        assertTrue(records.size > 1)

        val a = SectorAssembler()
        a.add(records[0])
        // No fully-collected group exists yet — tryKey can't reassemble anything, correct key or not.
        assertTrue(a.tryKey(key).isEmpty())
    }

    @Test fun contentReadyWithPendingOverrideBlobIsDroppedFromTracking() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(hint = "real hint", content = "secret trail notes".toByteArray())
        val blob = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_NONE)
        val record = recordOf(previewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "cover hint"), bodyBytes(blob))

        val a = SectorAssembler()
        val state = a.add(record)
        assertTrue(state is SectorAssembler.State.ContentReady)
        state as SectorAssembler.State.ContentReady
        assertEquals("cover hint", state.hint)
        assertArrayEquals(blob, state.pendingOverrideBlob)
        assertFalse(a.hasPending)
        // Resolving a single-code payload's blob is the caller's job (TagDropCodec.
        // tryDecryptOverrideMap directly, per ReceiveActivity.handleContentReady) — the
        // assembler's own tryKey has nothing left to act on once the group is terminal.
        assertTrue(a.tryKey(key).isEmpty())
    }
}
