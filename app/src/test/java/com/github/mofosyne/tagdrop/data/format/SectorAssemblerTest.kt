package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Exercises [SectorAssembler]'s state machine directly, with hand-built [ScannedRecord.Content]/
 * Split Wrapper fragment CBOR bytes (rather than going through [TagDropCodec]'s factory
 * functions, which is how TagDropCodecTest.kt already covers most round-trip/erasure-coding/
 * paper scenarios) — isolates the assembler's own reassembly/grouping/state logic from the
 * codec's encode-side field layout. Every fragment here is self-describing (carries its own
 * group_id inline, SPEC §5), matching the real wire format; fragment index 0 also carries
 * payload_hash (TagDrop-mandatory reassembly integrity, SPEC §5.1 "Reassembly integrity" —
 * group_id itself is just an opaque correlation token, QDEF-SPEC.md's Split Wrapper history).
 * Records are hand-built as raw QDEF
 * array-wrapped bytes (SPEC.md v16 §2, §2.1a, §3.1/§3.1a) and turned into [ScannedRecord]s via
 * [TagDropCodec.decodeRaw] — the same decode path a real scan uses — so this file only needs to
 * get the wire *bytes* right, not reimplement Record/subrecord decoding a second time.
 */
class SectorAssemblerTest {

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * A minimal Content Extension Record (declared Type 1, SPEC §3.1) — only the fields these
     * tests need. As of SPEC.md v16 (§2.1a), a TagDrop-scoped Record wire-encodes its NEGATIVE
     * typeId (`-1` here) and carries no namespace item of its own at all — it resolves back to
     * whatever namespace is ambient from its enclosing root Bundle, which [recordOf] always
     * supplies via [TagDropCodec.TAGDROP_NAMESPACE], whether it later pairs this with a second
     * Record or not (the key-only case).
     */
    private fun extensionBytes(
        hint: String?, keyMaterial: ByteArray? = null,
        lat: Double? = null, lng: Double? = null, radiusM: Double? = null, preferDeclaredLocation: Boolean = false
    ): ByteArray = MiniCbor.encodeRecord(-1, listOf(
        3 to hint, 23 to lat, 25 to lng, 27 to radiusM, 29 to (true.takeIf { preferDeclaredLocation }), 33 to keyMaterial
    ))

    /**
     * A minimal Media Preview Record (QDEF Type 7, SPEC §3.1a), optionally nesting
     * [subrecords]. `contentHash`/`filename` are back to Type-specific keys as of SPEC.md v14
     * (were QDEF Common Field Keys -11/-15 in versions 11-13), renumbered again in v15 (key `0`
     * reserved for a Record's own singular value, which Media Preview never has — every field
     * shifts up two keys: mediaType/contentHash/filename now 2/3/5). A QDEF standard/global
     * Type — never carries a namespace item of its own.
     */
    private fun mediaPreviewBytes(
        cacheId: ByteArray?, mimeType: String = "text/plain", filename: String? = null,
        subrecords: List<ByteArray> = emptyList()
    ): ByteArray = MiniCbor.encodeRecord(7, listOf(
        2 to mimeType, 3 to cacheId?.let { byteArrayOf(0x12) + it }, 5 to filename
    ), subrecords)

    /** A minimal Media Payload Record (QDEF Type 3, SPEC §3.1a) carrying [content] at reserved map key `0` (SPEC.md v15) — mediaType displaced to key `1` to make room. */
    private fun mediaPayloadBytes(content: ByteArray, mimeType: String = "text/plain", subrecords: List<ByteArray> = emptyList()): ByteArray =
        MiniCbor.encodeRecord(3, listOf(0 to content, 1 to mimeType), subrecords)

    /** A Compress Wrapper Record (Type 4, QDEF-SPEC.md §4.1) DEFLATEing [inner] into reserved map key `0` (SPEC.md v15: `[4, {0: deflated_bytes}]`). */
    private fun compressWrapBytes(inner: ByteArray): ByteArray = MiniCbor.encodeRecord(4, listOf(0 to TagDropCodec.compress(inner)))

    /**
     * A Split Wrapper Record (Type 1, QDEF-SPEC.md §4.1, SPEC §5) fragment's raw bytes,
     * optionally nesting [subrecords] (Media Preview, multi-code case). Renumbered in
     * SPEC.md v15: fragment `data` moves onto reserved map key `0`; `group_id`/`index`/`count`
     * shift up two keys each (2/4/6) to make room; `total_bytes`/`parity_scheme` are unchanged
     * (7/9). [payloadHash] is `payload_hash` (key `11`) — TagDrop-mandatory reassembly
     * integrity (SPEC §5.1), normally only passed on fragment index `0`.
     */
    private fun splitFragmentBytes(
        groupId: ByteArray, index: Int, count: Int, data: ByteArray, total: Int, parity: Boolean = false,
        subrecords: List<ByteArray> = emptyList(), payloadHash: ByteArray? = null
    ): ByteArray = MiniCbor.encodeRecord(1, listOf(
        0 to data, 2 to groupId, 4 to index, 6 to count, 7 to total, 9 to (1.takeIf { parity }), 11 to payloadHash
    ), subrecords)

    /** Decodes [extensionRaw]/[secondRaw] the same way a real scan would (SPEC §2, §2.1a, §5.1): wrapped as the QDEF self-delimited root (QDEF-SPEC.md §2/§3.1), namespace declared as the root Bundle's own leading element. */
    private fun recordOf(extensionRaw: ByteArray, secondRaw: ByteArray?): ScannedRecord.Content =
        (TagDropCodec.decodeRaw(MiniCbor.encodeRootBundle(listOfNotNull(extensionRaw, secondRaw), TagDropCodec.TAGDROP_NAMESPACE)) as TagDropScan.RecordScan).record as ScannedRecord.Content

    /**
     * Splits [mediaPayloadRaw] into [chunkCount]-many Split Wrapper fragment [ScannedRecord.
     * Content]s sharing [extensionRaw] and [mediaPreviewRaw] (Split's own repeated subrecord in
     * the multi-code case, SPEC.md v9 §3.1a), each carrying its own `group_id`
     * (SHA-256([mediaPayloadRaw])[0:8], an opaque correlation token only — SPEC §5.1) — mirrors
     * [TagDropCodec]'s own `splitFragments`. Fragment index `0` also carries `payload_hash`
     * (a multihash of [mediaPayloadRaw], TagDrop-mandatory reassembly integrity) unless
     * [includePayloadHash] is false, which builds a group missing the mandatory field on
     * purpose (for [SectorAssemblerTest]'s own rejection-path test). Appends a trailing XOR
     * parity fragment when [withParity].
     */
    private fun splitRecords(
        extensionRaw: ByteArray, mediaPreviewRaw: ByteArray, mediaPayloadRaw: ByteArray, chunkCount: Int,
        withParity: Boolean = false, includePayloadHash: Boolean = true
    ): List<ScannedRecord.Content> {
        val groupId = sha256(mediaPayloadRaw).copyOf(8)
        val payloadHash = byteArrayOf(0x12) + sha256(mediaPayloadRaw)
        val chunkLen = (mediaPayloadRaw.size + chunkCount - 1) / chunkCount
        val dataRecords = (0 until chunkCount).map { i ->
            val start = minOf(i * chunkLen, mediaPayloadRaw.size)
            val end = minOf(start + chunkLen, mediaPayloadRaw.size)
            val fragRaw = splitFragmentBytes(
                groupId, i, chunkCount, mediaPayloadRaw.copyOfRange(start, end), mediaPayloadRaw.size,
                subrecords = listOf(mediaPreviewRaw), payloadHash = payloadHash.takeIf { i == 0 && includePayloadHash }
            )
            recordOf(extensionRaw, fragRaw)
        }
        if (!withParity) return dataRecords
        val parity = ByteArray(chunkLen)
        for (i in 0 until chunkCount) {
            val start = minOf(i * chunkLen, mediaPayloadRaw.size)
            val end = minOf(start + chunkLen, mediaPayloadRaw.size)
            for (j in start until end) parity[j - start] = (parity[j - start].toInt() xor mediaPayloadRaw[j].toInt()).toByte()
        }
        val parityRaw = splitFragmentBytes(groupId, chunkCount, chunkCount, parity, mediaPayloadRaw.size, parity = true, subrecords = listOf(mediaPreviewRaw))
        return dataRecords + recordOf(extensionRaw, parityRaw)
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
        val record = recordOf(
            extensionBytes("test hint"),
            mediaPreviewBytes(cacheId, subrecords = listOf(mediaPayloadBytes(content)))
        )
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
            extensionBytes(null, lat = -33.8688, lng = 151.2093, radiusM = 25.0, preferDeclaredLocation = true),
            mediaPreviewBytes(cacheId, subrecords = listOf(mediaPayloadBytes(content)))
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
        val record = recordOf(extensionBytes("key hint", keyMaterial = key), null)
        val state = SectorAssembler().add(record) as SectorAssembler.State.ContentReady
        assertTrue(state.content.isEmpty())
        assertArrayEquals(key, state.keyMaterial)
        assertNull(state.mediaPreviewRaw)
        assertNull(state.mediaPayloadRaw)
    }

    // ── In-order multi-code Split (SPEC §5) ─────────────────────────────────────

    @Test fun multiCodeInOrder() {
        val content = "hello world this is some longer test content for chunking".toByteArray()
        val cacheId = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte())
        val records = splitRecords(extensionBytes(null), mediaPreviewBytes(cacheId), mediaPayloadBytes(content), chunkCount = 6)
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
        val records = splitRecords(extensionBytes(null), mediaPreviewBytes(cacheId), mediaPayloadBytes(content), chunkCount = 6)
        assertTrue(records.size > 1)
        val a = SectorAssembler()

        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records.reversed()) last = a.add(r)
        assertArrayEquals(content, (last as SectorAssembler.State.ContentReady).content)
    }

    @Test fun shuffledCodes() {
        val content = ByteArray(100) { it.toByte() }
        val cacheId = byteArrayOf(1, 1, 2, 3, 5, 8, 13, 21)
        val records = splitRecords(extensionBytes(null), mediaPreviewBytes(cacheId), mediaPayloadBytes(content), chunkCount = 10)
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
        val records = splitRecords(extensionBytes(null), mediaPreviewBytes(cacheId), mediaPayloadBytes(content), chunkCount = 5)
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
        val recordsA = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), mediaPayloadBytes(contentA), chunkCount = 4
        )
        val recordsB = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)), mediaPayloadBytes(contentB), chunkCount = 4
        )
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

    // ── payload_hash integrity check (SPEC §5.1) ────────────────────────────────

    @Test fun payloadHashMismatchDetected() {
        val content = ByteArray(60) { it.toByte() }
        val records = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), mediaPayloadBytes(content), chunkCount = 4
        ).toMutableList()
        // Tamper one fragment's data in place, without touching group_id (an opaque correlation
        // token only now, SPEC §5.1 — no longer verified) or fragment 0's payload_hash: the
        // reassembled bytes no longer hash to what fragment 0's payload_hash still claims.
        val victim = records[1]
        val frag = TagDropCodec.splitFragmentOf(victim)!!
        val tamperedData = frag.data.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tamperedRaw = splitFragmentBytes(
            frag.groupId, frag.index, frag.count, tamperedData, frag.total, subrecords = listOf(victim.mediaPreviewRaw!!)
        )
        records[1] = recordOf(victim.extensionRaw, tamperedRaw)

        val a = SectorAssembler()
        var state: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records) state = a.add(r)
        assertTrue("expected HashMismatch, got $state", state is SectorAssembler.State.HashMismatch)
    }

    @Test fun missingPayloadHashRejected() {
        // TagDrop makes payload_hash mandatory whenever Split is used (SPEC §5.1 "Reassembly
        // integrity"), stricter than QDEF's own OPTIONAL/odd framing of it -- a Split-wrapped
        // payload that never carries it on any fragment must be rejected outright once fully
        // reassembled, not silently accepted the way skipping a merely-optional field would be.
        val content = ByteArray(60) { it.toByte() }
        val records = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), mediaPayloadBytes(content),
            chunkCount = 4, includePayloadHash = false
        )
        val a = SectorAssembler()
        var state: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records) state = a.add(r)
        assertTrue("expected HashMismatch (missing payload_hash), got $state", state is SectorAssembler.State.HashMismatch)
    }

    // ── Resource-exhaustion guards (SPEC §5.1) ──────────────────────────────────

    @Test fun oversizedDeclaredCountRejected() {
        // A hostile fragment declaring count far beyond TagDropCodec.MAX_SPLIT_FRAGMENT_COUNT
        // must fail closed before any fragment-tracking storage is allocated -- not be silently
        // accepted into an indefinitely "Collecting" state. Carries a Media Preview subrecord
        // like any real multi-code fragment (SPEC §3.1a) -- without one, contentScanResult
        // itself rejects the code before ever reaching SectorAssembler, which would test the
        // wrong thing.
        val groupId = sha256("x".toByteArray()).copyOf(8)
        val hostileCount = TagDropCodec.MAX_SPLIT_FRAGMENT_COUNT + 1
        val fragRaw = splitFragmentBytes(
            groupId, 0, hostileCount, byteArrayOf(1, 2, 3), total = 100,
            subrecords = listOf(mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        )
        val record = recordOf(extensionBytes(null), fragRaw)
        val a = SectorAssembler()
        assertEquals(SectorAssembler.State.Failed, a.add(record))
        assertFalse("must not leave a group in flight", a.hasPending)
    }

    @Test fun oversizedDeclaredTotalBytesRejected() {
        // Same guard, for total_bytes -- bounds the reassembled-buffer allocation directly,
        // independent of count.
        val groupId = sha256("y".toByteArray()).copyOf(8)
        val hostileTotal = TagDropCodec.MAX_SPLIT_TOTAL_BYTES + 1
        val fragRaw = splitFragmentBytes(
            groupId, 0, 2, byteArrayOf(1, 2, 3), total = hostileTotal,
            subrecords = listOf(mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        )
        val record = recordOf(extensionBytes(null), fragRaw)
        val a = SectorAssembler()
        assertEquals(SectorAssembler.State.Failed, a.add(record))
        assertFalse("must not leave a group in flight", a.hasPending)
    }

    @Test fun ordinarySmallMultiFragmentPayloadUnaffectedByGuards() {
        // The new resource-exhaustion guards must not reject any real-world-shaped payload --
        // only ones declaring count/total_bytes far beyond what a physical multi-code drop needs.
        val content = ByteArray(50) { it.toByte() }
        val records = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), mediaPayloadBytes(content), chunkCount = 5
        )
        val a = SectorAssembler()
        var state: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in records) state = a.add(r)
        assertTrue("ordinary small multi-fragment payload must still complete normally", state is SectorAssembler.State.ContentReady)
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun resetClearsAllState() {
        val records = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), mediaPayloadBytes("data".toByteArray()), chunkCount = 3
        )
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
        val records = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
            mediaPayloadBytes("hello world, multi code".toByteArray()), chunkCount = 6
        )
        assertTrue(records.size > 1)

        a.add(records[0])
        assertTrue(a.hasPending)
        records.drop(1).forEach { a.add(it) }
        assertFalse("a terminal ContentReady group is dropped from tracking", a.hasPending)
    }

    // ── Compressed payload (Compress Wrapper, QDEF Type 4) ──────────────────────

    @Test fun compressedPayloadDecompressedOnAssembly() {
        val raw = "The quick brown fox jumps over the lazy dog".repeat(5).toByteArray()
        val record = recordOf(
            extensionBytes(null),
            mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), subrecords = listOf(compressWrapBytes(mediaPayloadBytes(raw))))
        )
        val state = SectorAssembler().add(record) as SectorAssembler.State.ContentReady
        // SectorAssembler decompresses internally; content should be the raw bytes.
        assertArrayEquals(raw, state.content)
    }

    // ── Malformed assembly ─────────────────────────────────────────────────────

    @Test fun failedStateWhenPayloadSubrecordTypeIsWrong() {
        // Media Preview decodes fine (only its own field map and subrecord count are checked at
        // scan time, SPEC §5.1) but its one subrecord isn't Media Payload (Type 3) or a Compress
        // Wrapper (Type 4) around one — caught only once reassembly/unwrap actually happens.
        val bogusPayload = MiniCbor.encodeRecord(999, listOf(1 to "not media payload".toByteArray()))
        val record = recordOf(
            extensionBytes(null),
            mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), subrecords = listOf(bogusPayload))
        )
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
        val extension = extensionBytes("cover hint")
        val mediaPreview = mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), filename = "cover.txt")
        val records = splitRecords(extension, mediaPreview, mediaPayloadBytes(blob), chunkCount = 4)
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
        val records = splitRecords(
            extensionBytes(null), mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), mediaPayloadBytes(blob), chunkCount = 4
        )
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
        val record = recordOf(
            extensionBytes("cover hint"),
            mediaPreviewBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), subrecords = listOf(mediaPayloadBytes(blob)))
        )

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
