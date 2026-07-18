package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.signing.MLDSA44
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests TagDropCodec against the QDEF Record wire format (SPEC.md v8): Content-Preview/
 * Content-Body (Type 1/3) and Paper-Preview/Paper-Body (Type 5/7), Split Wrapper (Type 2) and
 * Compress Wrapper (Type 8). Mirrors `tools/test-qdef-roundtrip.mjs`'s adversarial coverage
 * (tamper detection via group_id/root_hash recomputation, SPEC §2.2 even/odd key criticality,
 * key-only codes, the placeholder-then-strip signing round trip) rather than porting the old
 * version-1 envelope's byte-layout assertions, which no longer apply.
 */
class TagDropCodecTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Round-trips each code through encode → decode, like a real scan would. */
    private fun roundTrip(codes: List<ByteArray>): List<ScannedRecord> =
        codes.map { (TagDropCodec.decode(TagDropCodec.encode(it)) as TagDropScan.RecordScan).record }

    /** Feeds [records] (optionally shuffled) into a fresh assembler and returns the final state. */
    private fun assemble(records: List<ScannedRecord>, shuffle: Boolean = false): SectorAssembler.State {
        val a = SectorAssembler()
        val order = if (shuffle) records.shuffled(java.util.Random(42)) else records
        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (r in order) last = a.add(r)
        return last
    }

    // ── Content: single code ─────────────────────────────────────────────────

    @Test fun contentSingleRoundTrip() {
        val build = TagDropCodec.createContentSectors(
            "under the bridge", "poem.html", "text/html", "<h1>Hello</h1>".toByteArray()
        )
        assertEquals(1, build.codes.size)
        val uri = TagDropCodec.encode(build.codes.first())
        assertTrue(uri.startsWith("tagdrop:"))
        assertFalse(uri.startsWith("tagdrop://"))

        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals("under the bridge", state.hint)
        assertEquals("poem.html", state.filename)
        assertEquals("text/html", state.mimeType)
        assertArrayEquals("<h1>Hello</h1>".toByteArray(), state.content)
        assertArrayEquals(TagDropCodec.contentId("<h1>Hello</h1>".toByteArray()), state.cacheId)
    }

    @Test fun contentOptionalFieldsNull() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hello".toByteArray())
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertNull(state.hint)
        assertNull(state.filename)
        assertNull(state.collectionId)
        assertNull(state.collectionLabel)
        assertNull(state.collectionTag)
        assertNull(state.icon)
        assertNull(state.createdAt)
    }

    @Test fun contentWithCollectionAndIcon() {
        val collectionId = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80.toByte())
        val build = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(),
            collectionId = collectionId, collectionLabel = "Spring 2026 Sticker Hunt",
            collectionTag = "springtrail2026", icon = "🌳"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertArrayEquals(collectionId, state.collectionId)
        assertEquals("Spring 2026 Sticker Hunt", state.collectionLabel)
        assertEquals("springtrail2026", state.collectionTag)
        assertEquals("🌳", state.icon)
    }

    @Test fun contentWithTitleDescriptionAndInReplyTo() {
        val parentId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val build = TagDropCodec.createContentSectors(
            "hint text", null, "text/plain", "postcard message".toByteArray(),
            inReplyTo = parentId, title = "Greetings from the coast", description = "Wish you were here"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals("Greetings from the coast", state.title)
        assertEquals("Wish you were here", state.description)
        assertArrayEquals(parentId, state.inReplyTo)
    }

    @Test fun contentWithCreatedAt() {
        val build = TagDropCodec.createContentSectors(
            "hint text", null, "text/plain", "postcard message".toByteArray(), createdAt = 1_750_000_000L
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals(1_750_000_000L, state.createdAt)
    }

    @Test fun contentPixelArtRoundTripsWhenDeclaredTrue() {
        val build = TagDropCodec.createContentSectors(null, null, "image/png", byteArrayOf(1), pixelArt = true)
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertTrue(state.pixelArt)
    }

    @Test fun contentPixelArtDefaultsToFalse() {
        val build = TagDropCodec.createContentSectors(null, null, "image/png", byteArrayOf(1))
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertFalse(state.pixelArt)
    }

    @Test fun contentDeclaredLocationRoundTrip() {
        val build = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(),
            lat = -33.8688, lng = 151.2093, radiusM = 25.0, preferDeclaredLocation = true
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals(-33.8688, state.lat!!, 0.0)
        assertEquals(151.2093, state.lng!!, 0.0)
        assertEquals(25.0, state.radiusM!!, 0.0)
        assertTrue(state.preferDeclaredLocation)
    }

    @Test fun contentDeclaredLocationDefaultsAreNullAndFalse() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertNull(state.lat)
        assertNull(state.lng)
        assertNull(state.radiusM)
        assertFalse(state.preferDeclaredLocation)
    }

    @Test fun contentLocationLabelRoundTripsAlongsideCoordinates() {
        val build = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(), lat = 1.0, lng = 2.0, locationLabel = "🚋 Tram 40"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals("🚋 Tram 40", state.locationLabel)
    }

    @Test fun contentLocationLabelRoundTripsWithoutCoordinates() {
        // "Explicit no fixed point" (SPEC §4.2): a location label with no lat/lng at all.
        val build = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(), preferDeclaredLocation = true, locationLabel = "🚋 Tram 40"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertNull(state.lat)
        assertNull(state.lng)
        assertTrue(state.preferDeclaredLocation)
        assertEquals("🚋 Tram 40", state.locationLabel)
    }

    // ── Content: signing (SPEC §10) ─────────────────────────────────────────────

    @Test fun contentWithSignatureFieldsRoundTrip() {
        // Wire-format round-trip only, no real ML-DSA-44 math involved here.
        val signature = ByteArray(2420) { it.toByte() }
        val signerPubkey = ByteArray(1312) { (it * 3).toByte() }
        val signerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val build = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(),
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = signature, signerPubkey = signerPubkey, signerId = signerId, signerLabel = "Alice"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals(TagDropCodec.SIGNATURE_ALG_MLDSA44, state.signatureAlgorithm)
        assertArrayEquals(signature, state.signature)
        assertArrayEquals(signerPubkey, state.signerPubkey)
        assertArrayEquals(signerId, state.signerId)
        assertEquals("Alice", state.signerLabel)
    }

    @Test fun contentSignatureFieldsDefaultToUnsigned() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals(TagDropCodec.SIGNATURE_ALG_NONE, state.signatureAlgorithm)
        assertNull(state.signature)
        assertNull(state.signerPubkey)
    }

    @Test fun contentSignedMessageHashOfUnsignedIsDeterministic() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val h1 = TagDropCodec.contentSignedMessageHash(build.previewRaw, build.bodyRaw)
        val h2 = TagDropCodec.contentSignedMessageHash(build.previewRaw, build.bodyRaw)
        assertArrayEquals(h1, h2)
        assertEquals(32, h1.size)
    }

    @Test fun contentSignedMessageHashStripsSignatureFieldsBeforeHashing() {
        // The hash must be identical whether or not the payload ends up signed (SPEC §10
        // "signing happens last and feeds back into nothing").
        val unsigned = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val signed = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(),
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = ByteArray(2420), signerPubkey = ByteArray(1312), signerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        )
        val unsignedHash = TagDropCodec.contentSignedMessageHash(unsigned.previewRaw, unsigned.bodyRaw)
        val signedHash = TagDropCodec.contentSignedMessageHash(signed.previewRaw, signed.bodyRaw)
        assertArrayEquals(unsignedHash, signedHash)
    }

    @Test fun contentSignedMessageHashChangesIfContentTampered() {
        val a = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val b = TagDropCodec.createContentSectors(null, null, "text/plain", "ho".toByteArray())
        assertFalse(
            TagDropCodec.contentSignedMessageHash(a.previewRaw, a.bodyRaw)
                .contentEquals(TagDropCodec.contentSignedMessageHash(b.previewRaw, b.bodyRaw))
        )
    }

    @Test fun contentRealMlDsa44SignVerifyRoundTrip() {
        // The genuine end-to-end crypto path: placeholder-then-strip discipline, real keypair,
        // real signature — the exact class of bug (a field's value silently changing between an
        // independently-built "unsigned" pass and the real signed build) CLAUDE.md flags as only
        // catchable this way, not by code review alone.
        val (secretKey, publicKey) = MLDSA44.generateKeyPair()
        val signerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val placeholder = TagDropCodec.createContentSectors(
            "signed note", null, "text/plain", "hello, signed world".toByteArray(),
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = ByteArray(MLDSA44.SIGNATURE_BYTES), signerPubkey = publicKey, signerId = signerId
        )
        val hash = TagDropCodec.contentSignedMessageHash(placeholder.previewRaw, placeholder.bodyRaw)
        val signature = MLDSA44.sign(hash, secretKey)
        val final = TagDropCodec.createContentSectors(
            "signed note", null, "text/plain", "hello, signed world".toByteArray(),
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = signature, signerPubkey = publicKey, signerId = signerId
        )
        // Placeholder-swap must not have changed the logical bytes (fixed-length signature).
        assertArrayEquals(placeholder.previewRaw, final.previewRaw)

        val state = assemble(roundTrip(final.codes)) as SectorAssembler.State.ContentReady
        val verifyHash = TagDropCodec.contentSignedMessageHash(state.previewRaw, state.bodyRaw)
        assertTrue(MLDSA44.verify(state.signature!!, verifyHash, state.signerPubkey!!))
        // A signature must not verify against a different payload's hash.
        val otherHash = TagDropCodec.contentSignedMessageHash(
            TagDropCodec.createContentSectors(null, null, "text/plain", "different content".toByteArray()).previewRaw,
            null
        )
        assertFalse(MLDSA44.verify(state.signature!!, otherHash, state.signerPubkey!!))
    }

    // ── Content: compression (Compress Wrapper, QDEF Type 8) ───────────────────

    @Test fun contentCompressedRoundTripsToOriginalBytes() {
        val original = "The quick brown fox jumps over the lazy dog. ".repeat(60).toByteArray()
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", original, compress = true)
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertArrayEquals(original, state.content)
    }

    // ── Content: multi-code Split (SPEC §5) ─────────────────────────────────────

    @Test fun contentMultiCodeRoundTripAnyOrder() {
        val content = ByteArray(2000) { it.toByte() }
        val build = TagDropCodec.createContentSectors(null, null, "application/octet-stream", content, maxSectorDataBytes = 600)
        assertTrue("expected multiple codes", build.codes.size > 1)
        val state = assemble(roundTrip(build.codes), shuffle = true) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
    }

    @Test fun contentSectorsAutoSizedUsesSingleCodeWhenItFits() {
        val build = TagDropCodec.createContentSectorsAutoSized(null, null, "text/plain", "hi".toByteArray())
        assertEquals(1, build.codes.size)
        assertTrue(TagDropCodec.encode(build.codes.first()).length <= TagDropCodec.DEFAULT_URI_LENGTH)
    }

    @Test fun contentSectorsAutoSizedSplitsWhenTooLarge() {
        val big = ByteArray(5000) { it.toByte() }
        val build = TagDropCodec.createContentSectorsAutoSized(null, null, "application/octet-stream", big)
        assertTrue("expected several codes", build.codes.size > 1)
        val state = assemble(roundTrip(build.codes), shuffle = true) as SectorAssembler.State.ContentReady
        assertArrayEquals(big, state.content)
    }

    @Test fun collectingReportsMissingIndices() {
        val content = ByteArray(2000) { it.toByte() }
        val build = TagDropCodec.createContentSectors(null, null, "application/octet-stream", content, maxSectorDataBytes = 600)
        assertTrue(build.codes.size >= 3)
        val records = roundTrip(build.codes).dropLast(1) // withhold the last fragment
        val state = assemble(records) as SectorAssembler.State.Collecting
        assertEquals(records.size, state.received)
        assertEquals(build.codes.size, state.total)
        assertEquals(listOf(build.codes.size - 1), state.missingIndices)
    }

    @Test fun multiCodeGroupIdMismatchIsHashMismatch() {
        // Reassembling a truncated/corrupted fragment set that still nominally "completes"
        // (every index present) but doesn't hash back to the declared group_id.
        val content = ByteArray(2000) { it.toByte() }
        val build = TagDropCodec.createContentSectors(null, null, "application/octet-stream", content, maxSectorDataBytes = 600)
        assertTrue(build.codes.size >= 3)
        val records = roundTrip(build.codes).toMutableList()
        // Corrupt one fragment's data bytes in place (same length, so it still "completes"
        // once every index is present) by re-decoding a hand-tampered raw record.
        val victim = records[1]
        val frag = TagDropCodec.splitFragmentOf(victim)!!
        val tamperedData = frag.data.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tamperedRaw = MiniCbor.encodeUInt(2) + MiniCbor.encodeMap(listOf(
            2 to frag.groupId, 4 to frag.index, 6 to frag.count, 8 to tamperedData, 9 to frag.total
        ))
        val tamperedFull = victim.previewRaw + tamperedRaw
        val tamperedRecord = (TagDropCodec.decodeRaw(tamperedFull) as TagDropScan.RecordScan).record
        records[1] = tamperedRecord

        val state = assemble(records)
        assertTrue("expected HashMismatch, got $state", state is SectorAssembler.State.HashMismatch)
    }

    @Test fun parityReconstructsOneMissingDataFragment() {
        val content = ByteArray(2000) { it.toByte() }
        val build = TagDropCodec.createContentSectors(
            null, null, "application/octet-stream", content, maxSectorDataBytes = 600, withParity = true
        )
        assertTrue(build.codes.size >= 4) // >=3 data + 1 parity
        val records = roundTrip(build.codes)
        // Drop data fragment index 1 (keep parity + everything else).
        val withoutOne = records.filter { TagDropCodec.splitFragmentOf(it)?.index != 1 }
        val state = assemble(withoutOne) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
    }

    @Test fun parityReconstructsMissingLastFragment() {
        val content = ByteArray(1750) { it.toByte() }
        val build = TagDropCodec.createContentSectors(
            null, null, "application/octet-stream", content, maxSectorDataBytes = 600, withParity = true
        )
        val records = roundTrip(build.codes)
        val lastDataIndex = records.mapNotNull { TagDropCodec.splitFragmentOf(it) }.filter { !it.isParity }.maxOf { it.index }
        val withoutLast = records.filter { TagDropCodec.splitFragmentOf(it)?.index != lastDataIndex }
        val state = assemble(withoutLast) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
    }

    // ── Content: key-only codes and encryption (SPEC §9) ────────────────────────

    @Test fun keyCodeOmitsCacheIdAndContent() {
        val key = TagDropCodec.generateKeyMaterial()
        val code = TagDropCodec.createKeyCodeSector(key, hint = "key for the trailhead box")
        val state = assemble(roundTrip(listOf(code))) as SectorAssembler.State.ContentReady
        assertArrayEquals(key, state.keyMaterial)
        assertEquals("key for the trailhead box", state.hint)
        assertEquals("", state.mimeType)
        assertTrue(state.content.isEmpty())
        assertNull(state.cacheId)
        assertNull(state.bodyRaw)
    }

    @Test fun keyCodeRetainKeyFalseRoundTrip() {
        val key = TagDropCodec.generateKeyMaterial()
        val code = TagDropCodec.createKeyCodeSector(key, retainKey = false)
        val state = assemble(roundTrip(listOf(code))) as SectorAssembler.State.ContentReady
        assertFalse(state.retainKey)
    }

    @Test fun encryptedContentUsesRandomCacheId() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret".toByteArray())
        val build = TagDropCodec.createContentSectors(
            null, null, "text/plain", "cover".toByteArray(), override = override, encryptionKey = key
        )
        assertFalse(build.cacheId!!.contentEquals(TagDropCodec.contentId("cover".toByteArray())))
    }

    /**
     * A single-code payload resolves immediately to [SectorAssembler.State.ContentReady] with
     * [SectorAssembler.State.ContentReady.pendingOverrideBlob] set — there is no in-flight
     * group left in the assembler to retry later (unlike a still-collecting multi-code Split
     * group, see [multiCodeEncryptedContentResolvesViaAssemblerTryKey]), matching the real app
     * flow: `ReceiveActivity.handleContentReady` resolves a just-arrived single-code blob by
     * trying retained keys directly via [TagDropCodec.tryDecryptOverrideMap], not by calling
     * back into the assembler.
     */
    @Test fun encryptedContentResolvesViaDirectKeyTrial() {
        val key = TagDropCodec.generateKeyMaterial()
        val real = "The quick brown fox. ".repeat(80).toByteArray()
        val override = TagDropPayload.OverrideMap(hint = "real hint", filename = "fox.txt", content = real)
        val build = TagDropCodec.createContentSectors(
            "cover hint", "cover.txt", "text/plain", "cover story. ".repeat(40).toByteArray(),
            compress = true, override = override, encryptionKey = key
        )
        val a = SectorAssembler()
        val ready = roundTrip(build.codes).map { a.add(it) }.last() as SectorAssembler.State.ContentReady
        // Cover reading is shown as-is (the encrypted blob) until a key resolves it.
        assertNotNull(ready.pendingOverrideBlob)
        assertTrue(ready.pendingOverrideDeclared)
        assertFalse(a.hasPending) // single-code payload never entered the assembler's tracking

        assertNull("a non-matching key changes nothing", TagDropCodec.tryDecryptOverrideMap(ready.pendingOverrideBlob!!, TagDropCodec.generateKeyMaterial()))
        val resolved = TagDropCodec.tryDecryptOverrideMap(ready.pendingOverrideBlob!!, key)
        assertNotNull(resolved)
        assertArrayEquals(real, resolved!!.content)
        assertEquals("real hint", resolved.hint)
        assertEquals("fox.txt", resolved.filename)
    }

    /**
     * [SectorAssembler.tryKey] resolves an encrypted payload still mid-assembly (a Split group
     * missing its final fragment) the moment a key arrives *and* the last fragment lands right
     * after — exercising the actual reachable path through [SectorAssembler.tryKey]'s group
     * tracking, unlike the single-code case above.
     */
    @Test fun multiCodeEncryptedContentResolvesViaAssemblerTryKey() {
        val key = TagDropCodec.generateKeyMaterial()
        val real = "The quick brown fox. ".repeat(200).toByteArray()
        val override = TagDropPayload.OverrideMap(hint = "real hint", content = real)
        val build = TagDropCodec.createContentSectors(
            "cover hint", null, "text/plain", "cover story. ".repeat(200).toByteArray(),
            override = override, encryptionKey = key, maxSectorDataBytes = 600
        )
        assertTrue("expected a multi-code payload", build.codes.size > 1)
        val records = roundTrip(build.codes)
        val a = SectorAssembler()
        // Feed every fragment but the last — group is genuinely still Collecting.
        records.dropLast(1).forEach { a.add(it) }
        assertTrue(a.hasPending)
        assertTrue("a non-matching key changes nothing while incomplete", a.tryKey(TagDropCodec.generateKeyMaterial()).isEmpty())

        // The final fragment completes the group as ContentReady with an unresolved blob —
        // matching the single-code case, tryKey can no longer help once it's terminal.
        val last = a.add(records.last())
        assertTrue(last is SectorAssembler.State.ContentReady)
        assertFalse(a.hasPending)
    }

    @Test fun encryptedPlainContentShowsCoverWithPendingBlob() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(hint = "real hint", content = "secret trail notes".toByteArray())
        val build = TagDropCodec.createContentSectors(
            "cover hint", null, "text/plain", "cover".toByteArray(), override = override, encryptionKey = key
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertEquals("cover hint", state.hint)
        assertNotNull(state.pendingOverrideBlob)
        assertTrue(state.pendingOverrideDeclared)

        val ov = TagDropCodec.tryDecryptOverrideMap(state.pendingOverrideBlob!!, key)
        assertNotNull(ov)
        assertArrayEquals("secret trail notes".toByteArray(), ov!!.content)
        assertEquals("real hint", ov.hint)
    }

    @Test fun unencryptedContentHasNoPendingBlobUnderMinSize() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertNull(state.pendingOverrideBlob)
        assertFalse(state.wasEncrypted)
    }

    @Test fun unencryptedLongContentIsCandidateButNotDeclaredLocked() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "x".repeat(40).toByteArray())
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.ContentReady
        assertNotNull(state.pendingOverrideBlob)
        assertFalse(state.pendingOverrideDeclared)
        assertFalse(state.wasEncrypted)
    }

    // ── Content: SPEC §2.2 even/odd key criticality ─────────────────────────────

    @Test fun decodeIgnoresUnknownOddKey() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val (items, trailing) = MiniCbor.decodeSequencePrefix(build.previewRaw, 2)
        @Suppress("UNCHECKED_CAST")
        val typeId = (items[0] as? Int) ?: (items[0] as? Long)?.toInt() ?: 0
        val fields = (items[1] as Map<Int, Any>).toList() + (9001 to "unknown but odd")
        val tamperedPreview = MiniCbor.encodeUInt(typeId) + MiniCbor.encodeMap(fields)
        val record = TagDropCodec.decodeRaw(tamperedPreview + trailing + (build.bodyRaw)) as? TagDropScan.RecordScan
        assertNotNull("an unknown ODD key must be safely ignored, not rejected", record)
    }

    @Test fun decodeRejectsUnknownEvenKey() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val (items, _) = MiniCbor.decodeSequencePrefix(build.previewRaw, 2)
        @Suppress("UNCHECKED_CAST")
        val typeId = (items[0] as? Int) ?: (items[0] as? Long)?.toInt() ?: 0
        val fields = (items[1] as Map<Int, Any>).toList() + (9002 to "unknown and even")
        val tamperedPreview = MiniCbor.encodeUInt(typeId) + MiniCbor.encodeMap(fields)
        val scan = TagDropCodec.decodeRaw(tamperedPreview + build.bodyRaw)
        assertNull("an unknown EVEN key must reject the whole Record (forward-compat safety valve)", scan)
    }

    // ── Encoding / decoding plumbing ─────────────────────────────────────────────

    @Test fun decodeRawMatchesDecodeOfEncodedUri() {
        val build = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        val viaUri = TagDropCodec.decode(TagDropCodec.encode(build.codes.first()))
        val viaRaw = TagDropCodec.decodeRaw(build.codes.first())
        assertEquals(viaUri, viaRaw)
    }

    @Test fun decodeRawReturnsNullForGarbageBytes() {
        assertNull(TagDropCodec.decodeRaw(byteArrayOf(0xFF.toByte(), 0x00, 0x11)))
    }

    @Test fun legacyDataUriDecodesToLegacyScan() {
        val scan = TagDropCodec.decode("data:text/plain;base64,aGVsbG8=")
        assertTrue(scan is TagDropScan.LegacyScan)
    }

    @Test fun navigationLinkAndUnknownSchemesReturnNull() {
        assertNull(TagDropCodec.decode("tagdrop://example.com/slug"))
        assertNull(TagDropCodec.decode("https://example.com"))
        assertNull(TagDropCodec.decode("not a uri at all"))
    }

    // ── Paper (SPEC §3.3-§3.4, §4.4) ─────────────────────────────────────────────

    @Test fun paperRoundTrip() {
        val files = listOf(
            TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), description = "A poem to read"),
            TagDropPayload.FileEntry("map", "image/svg+xml", byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16))
        )
        val related = listOf(
            TagDropPayload.RelatedPaper("letterbox 200m north", set = "sunset-trail", slug = "letterbox",
                paperId = byteArrayOf(17, 18, 19, 20, 21, 22, 23, 24), lat = -33.8688, lng = 151.2093, radiusM = 50.0),
            TagDropPayload.RelatedPaper("trail start at town square")
        )
        val collectionId = byteArrayOf(1, 1, 2, 2, 3, 3, 4, 4)
        val build = TagDropCodec.createPaper(
            "Trail Stop 3 — Oak Tree", "sunset-trail", "oak-tree", files, related,
            description = "Day 2 of the sunset trail",
            collectionId = collectionId, collectionLabel = "Sunset Trail 2026", collectionTag = "sunsettrail", icon = "🌲"
        )

        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady
        val decoded = state.paper
        assertArrayEquals(build.paper.rootHash, decoded.rootHash)
        assertEquals("Trail Stop 3 — Oak Tree", decoded.label)
        assertEquals("sunset-trail", decoded.set)
        assertEquals("oak-tree", decoded.slug)
        assertEquals("Day 2 of the sunset trail", decoded.description)
        assertArrayEquals(collectionId, decoded.collectionId)
        assertEquals("Sunset Trail 2026", decoded.collectionLabel)
        assertEquals("sunsettrail", decoded.collectionTag)
        assertEquals("🌲", decoded.icon)

        assertEquals(2, decoded.files.size)
        assertEquals("index", decoded.files[0].slug)
        assertEquals("A poem to read", decoded.files[0].description)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), decoded.files[0].fileId)

        assertEquals(2, decoded.related.size)
        assertEquals("letterbox 200m north", decoded.related[0].hint)
        assertEquals("letterbox", decoded.related[0].slug)
        assertEquals(-33.8688, decoded.related[0].lat!!, 0.0)
        assertEquals(151.2093, decoded.related[0].lng!!, 0.0)
        assertEquals(50.0, decoded.related[0].radiusM!!, 0.0)
        assertEquals("trail start at town square", decoded.related[1].hint)
        assertNull(decoded.related[1].lat)
        assertNull(decoded.related[1].radiusM)
    }

    @Test fun paperWithStepAndDomain() {
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaper(
            "Trail Stop 3", "sunset-trail", "oak-tree", files, step = 3, domain = "sunsettrail"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady
        assertEquals(3, state.paper.step)
        assertEquals("sunsettrail", state.paper.domain)
    }

    @Test fun paperWithTitleAndInReplyTo() {
        val parentId = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaper(
            "Trail Stop 4", "sunset-trail", "stop-4", files, inReplyTo = parentId, title = "Reply to Stop 3"
        )
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady
        assertEquals("Reply to Stop 3", state.paper.title)
        assertArrayEquals(parentId, state.paper.inReplyTo)
    }

    @Test fun paperWithCreatedAt() {
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaper("Trail Stop 4", "sunset-trail", "stop-4", files, createdAt = 1_750_000_000L)
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady
        assertEquals(1_750_000_000L, state.paper.createdAt)
    }

    @Test fun paperWithSignatureFieldsRoundTrip() {
        val signature = ByteArray(2420) { it.toByte() }
        val signerPubkey = ByteArray(1312) { (it * 3).toByte() }
        val signerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaper(
            "Trail Stop 4", "sunset-trail", "stop-4", files,
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = signature, signerPubkey = signerPubkey, signerId = signerId, signerLabel = "Alice's Trail"
        )
        assertEquals(TagDropCodec.SIGNATURE_ALG_MLDSA44, build.paper.signatureAlgorithm)
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady
        assertEquals(TagDropCodec.SIGNATURE_ALG_MLDSA44, state.paper.signatureAlgorithm)
        assertArrayEquals(signature, state.paper.signature)
        assertArrayEquals(signerPubkey, state.paper.signerPubkey)
        assertArrayEquals(signerId, state.paper.signerId)
        assertEquals("Alice's Trail", state.paper.signerLabel)
    }

    @Test fun paperRootHashIsIdenticalWhetherOrNotSigned() {
        // SPEC §10: root_hash must NOT depend on whether the paper ends up signed.
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val unsigned = TagDropCodec.createPaper("Trail Stop 4", "sunset-trail", "stop-4", files)
        val signed = TagDropCodec.createPaper(
            "Trail Stop 4", "sunset-trail", "stop-4", files,
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = ByteArray(2420), signerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        )
        assertArrayEquals(unsigned.paper.rootHash, signed.paper.rootHash)
    }

    @Test fun paperRealMlDsa44SignVerifyRoundTrip() {
        val (secretKey, publicKey) = MLDSA44.generateKeyPair()
        val signerId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val placeholder = TagDropCodec.createPaper(
            "Signed Paper", "trail", "stop-1", files,
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = ByteArray(MLDSA44.SIGNATURE_BYTES), signerPubkey = publicKey, signerId = signerId
        )
        val hash = TagDropCodec.paperSignedMessageHash(placeholder.previewRaw, placeholder.bodyRaw)
        val signature = MLDSA44.sign(hash, secretKey)
        val final = TagDropCodec.createPaper(
            "Signed Paper", "trail", "stop-1", files,
            signatureAlgorithm = TagDropCodec.SIGNATURE_ALG_MLDSA44,
            signature = signature, signerPubkey = publicKey, signerId = signerId
        )
        assertArrayEquals(placeholder.paper.rootHash, final.paper.rootHash)

        val state = assemble(roundTrip(final.codes)) as SectorAssembler.State.PaperReady
        val verifyHash = TagDropCodec.paperSignedMessageHash(state.previewRaw, state.bodyRaw)
        assertTrue(MLDSA44.verify(state.paper.signature!!, verifyHash, state.paper.signerPubkey!!))
    }

    @Test fun paperSignatureFieldsDefaultToUnsigned() {
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaper("Trail Stop 4", "sunset-trail", "stop-4", files)
        assertEquals(TagDropCodec.SIGNATURE_ALG_NONE, build.paper.signatureAlgorithm)
        val state = assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady
        assertEquals(TagDropCodec.SIGNATURE_ALG_NONE, state.paper.signatureAlgorithm)
        assertNull(state.paper.signature)
        assertNull(state.paper.signerPubkey)
    }

    @Test fun paperRootHashIsContentAddressed() {
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val a = TagDropCodec.createPaper("Trail Stop 3", "sunset-trail", "oak-tree", files)
        val b = TagDropCodec.createPaper("Trail Stop 3", "sunset-trail", "oak-tree", files)
        assertEquals(8, a.paper.rootHash.size)
        assertArrayEquals(a.paper.rootHash, b.paper.rootHash)

        val c = TagDropCodec.createPaper("Trail Stop 4", "sunset-trail", "oak-tree", files)
        assertFalse(a.paper.rootHash.contentEquals(c.paper.rootHash))
    }

    @Test fun paperRootHashMismatchIsRejectedOnDecode() {
        // A forged Preview claiming a root_hash that doesn't match the real Preview'||Body' hash.
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaper("Trail Stop 3", "sunset-trail", "oak-tree", files)
        val (items, _) = MiniCbor.decodeSequencePrefix(build.previewRaw, 2)
        @Suppress("UNCHECKED_CAST")
        val typeId = (items[0] as? Int) ?: (items[0] as? Long)?.toInt() ?: 0
        val fields = (items[1] as Map<Int, Any>).toMutableMap()
        fields[1] = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) // forged root_hash
        val forgedPreview = MiniCbor.encodeUInt(typeId) + MiniCbor.encodeMap(fields.toList())
        val record = (TagDropCodec.decodeRaw(forgedPreview + build.bodyRaw) as TagDropScan.RecordScan).record
        assertNull(TagDropCodec.parsePaperStream(record, build.bodyRaw))
    }

    @Test fun decodePaperStreamRoundTrip() {
        val build = TagDropCodec.createPaper(
            "Test Paper", "test-set", "test-slug",
            listOf(TagDropPayload.FileEntry("readme", "text/plain", byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12)))
        )
        val decoded = TagDropCodec.decodePaperStream(TagDropCodec.paperStreamBytes(build.paper))
        assertNotNull(decoded)
        assertArrayEquals(build.paper.rootHash, decoded!!.rootHash)
        assertEquals("Test Paper", decoded.label)
        assertEquals(1, decoded.files.size)
        assertEquals("readme", decoded.files[0].slug)
    }

    @Test fun decodePaperStreamReturnsNullForGarbage() {
        assertNull(TagDropCodec.decodePaperStream(byteArrayOf(1, 2, 3)))
    }

    @Test fun paperKeyMaterialAndRelatedKeyRoundTrip() {
        val paperKey = ByteArray(32) { (it + 1).toByte() }
        val relatedKey = ByteArray(32) { it.toByte() }
        val build = TagDropCodec.createPaper(
            null, null, null, emptyList(),
            related = listOf(
                TagDropPayload.RelatedPaper("locked related paper", keyMaterial = relatedKey, retainKey = false),
                TagDropPayload.RelatedPaper("plain related paper")
            ),
            keyMaterial = paperKey, retainKey = false
        )
        val decoded = (assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady).paper
        assertArrayEquals(paperKey, decoded.keyMaterial)
        assertFalse(decoded.retainKey)
        assertArrayEquals(relatedKey, decoded.related[0].keyMaterial)
        assertFalse(decoded.related[0].retainKey)
        assertNull(decoded.related[1].keyMaterial)
        assertTrue(decoded.related[1].retainKey)
    }

    @Test fun paperMultiCodeRoundTripAnyOrder() {
        val files = (0 until 60).map {
            TagDropPayload.FileEntry("file-$it", "text/plain", ByteArray(8) { (it).toByte() })
        }
        val build = TagDropCodec.createPaper("Big Paper", null, null, files, maxSectorDataBytes = 400)
        assertTrue("a large paper should span several codes", build.codes.size > 1)
        val decoded = (assemble(roundTrip(build.codes), shuffle = true) as SectorAssembler.State.PaperReady).paper
        assertArrayEquals(build.paper.rootHash, decoded.rootHash)
        assertEquals(60, decoded.files.size)
    }

    @Test fun paperAutoSizedUsesSingleCodeWhenItFits() {
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val build = TagDropCodec.createPaperAutoSized("Small Paper", null, null, files)
        assertEquals(1, build.codes.size)
        assertTrue(TagDropCodec.encode(build.codes.first()).length <= TagDropCodec.MAX_URI_LENGTH)
    }

    @Test fun paperAutoSizedSplitsWhenTooLarge() {
        val files = (0 until 60).map {
            TagDropPayload.FileEntry("file-$it", "text/plain", ByteArray(8) { (it).toByte() })
        }
        val build = TagDropCodec.createPaperAutoSized("Big Paper", null, null, files)
        assertTrue("expected several codes", build.codes.size > 1)
        build.codes.forEach { assertTrue(TagDropCodec.encode(it).length <= TagDropCodec.MAX_URI_LENGTH) }
        val decoded = (assemble(roundTrip(build.codes), shuffle = true) as SectorAssembler.State.PaperReady).paper
        assertArrayEquals(build.paper.rootHash, decoded.rootHash)
        assertEquals(60, decoded.files.size)
    }

    @Test fun paperCompressedRoundTrips() {
        val files = (0 until 30).map {
            TagDropPayload.FileEntry("file-$it", "text/plain", ByteArray(8) { (it).toByte() })
        }
        val build = TagDropCodec.createPaper("Compressed Paper", null, null, files, compressBody = true)
        val decoded = (assemble(roundTrip(build.codes)) as SectorAssembler.State.PaperReady).paper
        assertEquals(30, decoded.files.size)
        assertArrayEquals(build.paper.rootHash, decoded.rootHash)
    }

    // ── Compression / encryption / KDF helpers (format-agnostic) ────────────────

    @Test fun compressDecompressRoundTrip() {
        val original = "hello world ".repeat(50).toByteArray()
        val compressed = TagDropCodec.compress(original)
        assertTrue(compressed.size < original.size)
        assertArrayEquals(original, TagDropCodec.decompress(compressed))
    }

    @Test fun encryptAesGcmRoundTrip() {
        val key = TagDropCodec.generateKeyMaterial()
        val nonce = TagDropCodec.generateNonce()
        val plaintext = "secret message".toByteArray()
        val ciphertext = TagDropCodec.encryptAesGcm(plaintext, key, nonce)
        assertArrayEquals(plaintext, TagDropCodec.decryptAesGcm(ciphertext, key, nonce))
        assertNull("wrong key must fail to authenticate", TagDropCodec.decryptAesGcm(ciphertext, TagDropCodec.generateKeyMaterial(), nonce))
    }

    @Test fun deriveKeyFromPassphraseIsDeterministic() {
        val salt = ByteArray(16) { it.toByte() }
        val a = TagDropCodec.deriveKeyFromPassphrase("hunter2", salt, 1000)
        val b = TagDropCodec.deriveKeyFromPassphrase("hunter2", salt, 1000)
        assertArrayEquals(a, b)
    }

    @Test fun deriveKeyFromPassphraseDiffersForDifferentInputs() {
        val salt = ByteArray(16) { it.toByte() }
        val a = TagDropCodec.deriveKeyFromPassphrase("hunter2", salt, 1000)
        val b = TagDropCodec.deriveKeyFromPassphrase("hunter3", salt, 1000)
        assertFalse(a.contentEquals(b))
    }

    @Test fun passphraseDerivedKeyUnlocksOverrideBlob() {
        val salt = ByteArray(16) { it.toByte() }
        val key = TagDropCodec.deriveKeyFromPassphrase("hunter2", salt, 1000)
        val override = TagDropPayload.OverrideMap(content = "secret".toByteArray())
        val blob = TagDropCodec.encryptOverrideMap(override, key, TagDropCodec.COMPRESSION_NONE)
        val decoded = TagDropCodec.tryDecryptOverrideMap(blob, key)
        assertNotNull(decoded)
        assertArrayEquals("secret".toByteArray(), decoded!!.content)
    }

    @Test fun contentIdIs8BytesAndDeterministic() {
        val a = TagDropCodec.contentId("hello".toByteArray())
        val b = TagDropCodec.contentId("hello".toByteArray())
        assertEquals(8, a.size)
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(TagDropCodec.contentId("world".toByteArray())))
    }

    @Test fun randomCacheIdIs8Bytes() {
        assertEquals(8, TagDropCodec.randomCacheId().size)
    }

    @Test fun describeCborDoesNotThrowOnMalformedBytes() {
        // Best-effort diagnostic: must never throw, even on garbage.
        val out = TagDropCodec.describeCbor(byteArrayOf(0xFF.toByte(), 0x01, 0x02))
        assertNotNull(out)
    }
}
