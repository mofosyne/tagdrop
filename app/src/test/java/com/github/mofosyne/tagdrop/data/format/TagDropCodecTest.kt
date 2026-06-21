package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class TagDropCodecTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Round-trips each sector through encode → decode, like a real scan would. */
    private fun roundTrip(sectors: List<Sector>): List<Sector> =
        sectors.map { (TagDropCodec.decode(TagDropCodec.encode(it)) as TagDropScan.SectorScan).sector }

    /** Feeds [sectors] (optionally shuffled) into a fresh assembler and returns the final state. */
    private fun assemble(sectors: List<Sector>, shuffle: Boolean = false): SectorAssembler.State {
        val a = SectorAssembler()
        val order = if (shuffle) sectors.shuffled(java.util.Random(42)) else sectors
        var last: SectorAssembler.State = SectorAssembler.State.Idle
        for (s in order) last = a.add(s)
        return last
    }

    /** Decodes the `core_meta_item` (first CBOR item) from a sector's `sector_bytes`. */
    @Suppress("UNCHECKED_CAST")
    private fun coreOf(sector: Sector): Map<Int, Any> =
        MiniCbor.decodeSequencePrefix(sector.sectorBytes, 1).first[0] as Map<Int, Any>

    @Suppress("UNCHECKED_CAST")
    private fun partMetaOf(sector: Sector): Map<Int, Any> =
        MiniCbor.decodeSequence(TagDropCodec.sectorCbor(sector))[2] as Map<Int, Any>

    // ── Content: single sector ─────────────────────────────────────────────────

    @Test fun contentSingleRoundTrip() {
        val sectors = TagDropCodec.createContentSectors(
            "under the bridge", "poem.html", "text/html", "<h1>Hello</h1>".toByteArray()
        )
        assertEquals(1, sectors.size)
        val uri = TagDropCodec.encode(sectors.first())
        assertTrue(uri.startsWith("tagdrop:"))
        assertFalse(uri.startsWith("tagdrop://"))

        val state = assemble(roundTrip(sectors)) as SectorAssembler.State.ContentReady
        assertEquals("under the bridge", state.hint)
        assertEquals("poem.html", state.filename)
        assertEquals("text/html", state.mimeType)
        assertArrayEquals("<h1>Hello</h1>".toByteArray(), state.content)
        assertArrayEquals(TagDropCodec.contentId("<h1>Hello</h1>".toByteArray()), state.cacheId)
    }

    @Test fun contentOptionalFieldsNull() {
        val sectors = TagDropCodec.createContentSectors(null, null, "text/plain", "hello".toByteArray())
        val state = assemble(roundTrip(sectors)) as SectorAssembler.State.ContentReady
        assertNull(state.hint)
        assertNull(state.filename)
        assertNull(state.collectionId)
        assertNull(state.collectionLabel)
        assertNull(state.collectionTag)
        assertNull(state.icon)
    }

    @Test fun contentWithCollectionAndIcon() {
        val collectionId = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80.toByte())
        val sectors = TagDropCodec.createContentSectors(
            null, null, "text/plain", "hi".toByteArray(),
            collectionId = collectionId, collectionLabel = "Spring 2026 Sticker Hunt",
            collectionTag = "springtrail2026", icon = "🌳"
        )
        val state = assemble(roundTrip(sectors)) as SectorAssembler.State.ContentReady
        assertArrayEquals(collectionId, state.collectionId)
        assertEquals("Spring 2026 Sticker Hunt", state.collectionLabel)
        assertEquals("springtrail2026", state.collectionTag)
        assertEquals("🌳", state.icon)
    }

    @Test fun contentWithCompressionDecompressedOnAssembly() {
        val raw = "<html><body>test</body></html>".repeat(20).toByteArray()
        val sectors = TagDropCodec.createContentSectors(null, null, "text/html", raw, compress = true)
        assertEquals(TagDropCodec.COMPRESSION_DEFLATE.toLong(), coreOf(sectors.first())[12])
        val state = assemble(roundTrip(sectors)) as SectorAssembler.State.ContentReady
        assertArrayEquals(raw, state.content)
        // cache_id is over the uncompressed content, regardless of compression (SPEC §4.4).
        assertArrayEquals(TagDropCodec.contentId(raw), state.cacheId)
    }

    @Test fun contentIdIsContentAddressed() {
        val a = TagDropCodec.createContentSectors(null, null, "text/plain", "same".toByteArray()).first()
        val b = TagDropCodec.createContentSectors("different hint", "x.txt", "text/plain", "same".toByteArray()).first()
        // Same content bytes ⇒ same cache_id, regardless of metadata (SPEC §4.4).
        assertArrayEquals(a.partMeta.cacheId, b.partMeta.cacheId)
        assertArrayEquals(TagDropCodec.contentId("same".toByteArray()), a.partMeta.cacheId)
    }

    // ── Content: multi-sector ──────────────────────────────────────────────────

    @Test fun contentMultiSectorRoundTripAnyOrder() {
        val content = ByteArray(3000) { it.toByte() }
        val sectors = TagDropCodec.createContentSectors(
            "big file", "data.bin", "application/octet-stream", content, maxSectorDataBytes = 600
        )
        assertTrue("expected several sectors", sectors.size > 1)
        sectors.forEachIndexed { i, s ->
            assertEquals(i, s.partMeta.sectorIndex)
            assertEquals(sectors.size, s.partMeta.sectorCount)
            assertArrayEquals(sectors.first().partMeta.cacheId, s.partMeta.cacheId)
            assertTrue(TagDropCodec.encode(s).length <= TagDropCodec.MAX_URI_LENGTH)
        }
        val state = assemble(roundTrip(sectors), shuffle = true) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
        assertArrayEquals(TagDropCodec.contentId(content), state.cacheId)
        assertEquals("big file", state.hint)
    }

    @Test fun multiSectorAddsContentSha256ButSingleSectorOmitsIt() {
        val small = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray())
        assertFalse("single-sector content omits content_sha256", coreOf(small.first()).containsKey(8))

        val big = TagDropCodec.createContentSectors(
            null, null, "text/plain", ByteArray(2000) { it.toByte() }, maxSectorDataBytes = 600
        )
        assertTrue("multi-sector content carries content_sha256", coreOf(big.first()).containsKey(8))
    }

    @Test fun multiSectorCorruptionIsHashMismatch() {
        val content = ByteArray(2000) { it.toByte() }
        val sectors = TagDropCodec.createContentSectors(
            null, null, "text/plain", content, maxSectorDataBytes = 600
        ).toMutableList()
        // Corrupt the last byte of the final sector (pure content tail) → content_sha256 fails.
        val last = sectors.last()
        val tampered = last.sectorBytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        sectors[sectors.lastIndex] = Sector(last.type, last.partMeta, tampered)
        assertTrue(assemble(sectors) is SectorAssembler.State.HashMismatch)
    }

    @Test fun collectingReportsMissingIndices() {
        val sectors = TagDropCodec.createContentSectors(
            null, null, "text/plain", ByteArray(2500) { it.toByte() }, maxSectorDataBytes = 600
        )
        val a = SectorAssembler()
        // Add all but index 2.
        sectors.filter { it.partMeta.sectorIndex != 2 }.forEach { a.add(it) }
        val state = a.currentState() as SectorAssembler.State.Collecting
        assertEquals(listOf(2), state.missingIndices)
        assertEquals(sectors.size, state.total)
    }

    // ── Envelope structure (SPEC §2) ───────────────────────────────────────────

    @Test fun sectorEnvelopeIsFourCborItems() {
        val sector = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray()).first()
        val items = MiniCbor.decodeSequence(TagDropCodec.sectorCbor(sector))
        assertEquals(4, items.size)
        assertEquals(1L, items[0])          // version
        assertEquals(0L, items[1])          // type = Content
        assertTrue(items[2] is Map<*, *>)   // part_meta
        assertTrue(items[3] is ByteArray)   // sector_bytes
    }

    @Test fun envelopeFirstTwoBytesEncodeVersionAndType() {
        val content = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray()).first()
        assertArrayEquals(byteArrayOf(0x01, 0x00), TagDropCodec.sectorCbor(content).copyOf(2))

        val paper = TagDropCodec.createPaper(null, null, null, emptyList()).second.first()
        assertArrayEquals(byteArrayOf(0x01, 0x01), TagDropCodec.sectorCbor(paper).copyOf(2))
    }

    @Test fun partMetaCarriesSectorFields() {
        val sector = TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray()).first()
        val pm = partMetaOf(sector)
        assertTrue(pm.containsKey(2))   // cache_id
        assertTrue(pm.containsKey(7))   // total_bytes
        assertEquals(0L, pm[42])        // sector_index
        assertEquals(1L, pm[43])        // sector_count
        assertFalse(pm.containsKey(44)) // parity_scheme absent on a data sector
    }

    // ── decode / decodeRaw → TagDropScan ───────────────────────────────────────

    @Test fun decodeRawMatchesDecodeOfEncodedUri() {
        val sector = TagDropCodec.createContentSectors("hint", "f.txt", "text/plain", "hi".toByteArray()).first()
        val uri = TagDropCodec.encode(sector)
        val viaUri = (TagDropCodec.decode(uri) as TagDropScan.SectorScan).sector
        val viaRaw = (TagDropCodec.decodeRaw(Base41.decode(uri.removePrefix("tagdrop:"))) as TagDropScan.SectorScan).sector
        assertEquals(viaUri, viaRaw)
    }

    @Test fun decodeRawReturnsNullForGarbageBytes() {
        assertNull(TagDropCodec.decodeRaw(byteArrayOf(1, 2, 3)))
        assertNull(TagDropCodec.decodeRaw(ByteArray(0)))
    }

    @Test fun decodeRawReturnsNullForUnsupportedVersion() {
        val pm = MiniCbor.encodeMap(listOf(2 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 7 to 0, 42 to 0, 43 to 1))
        val seq = MiniCbor.encodeUInt(2) + MiniCbor.encodeUInt(0) + pm + MiniCbor.encodeBytes(ByteArray(0))
        assertNull(TagDropCodec.decodeRaw(seq))
    }

    @Test fun legacyDataUriDecodesToLegacyScan() {
        val uri = "data:text/html;base64,AAAA"
        val scan = TagDropCodec.decode(uri)
        assertTrue(scan is TagDropScan.LegacyScan)
        assertEquals(uri, (scan as TagDropScan.LegacyScan).payload.dataUri)
    }

    @Test fun navigationLinkAndUnknownSchemesReturnNull() {
        assertNull(TagDropCodec.decode("tagdrop://ABCD/some-slug"))
        assertNull(TagDropCodec.decode("https://example.com"))
        assertNull(TagDropCodec.decode(""))
        assertNull(TagDropCodec.decode("tagdrop:!!!!INVALID!!!!"))
    }

    @Test fun decodeIgnoresUnknownKeys() {
        // SPEC §3: unknown keys must be ignored (forward compatibility).
        val core = MiniCbor.encodeMap(listOf(4 to "text/plain", 99 to "a field from the future"))
        val bulky = MiniCbor.encodeMap(emptyList())
        val stream = core + bulky + "hello".toByteArray()
        val pm = MiniCbor.encodeMap(listOf(2 to byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 7 to stream.size, 42 to 0, 43 to 1))
        val cbor = MiniCbor.encodeUInt(1) + MiniCbor.encodeUInt(0) + pm + MiniCbor.encodeBytes(stream)

        val sector = (TagDropCodec.decodeRaw(cbor) as TagDropScan.SectorScan).sector
        val state = SectorAssembler().add(sector) as SectorAssembler.State.ContentReady
        assertEquals("text/plain", state.mimeType)
        assertArrayEquals("hello".toByteArray(), state.content)
    }

    // ── Paper (SPEC §4.3) ──────────────────────────────────────────────────────

    @Test fun paperRoundTrip() {
        val files = listOf(
            TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), description = "A poem to read"),
            TagDropPayload.FileEntry("map", "image/svg+xml", byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16))
        )
        val related = listOf(
            TagDropPayload.RelatedPaper("letterbox 200m north", set = "sunset-trail", slug = "letterbox",
                paperId = byteArrayOf(17, 18, 19, 20, 21, 22, 23, 24), lat = -33.8688, lng = 151.2093),
            TagDropPayload.RelatedPaper("trail start at town square")
        )
        val collectionId = byteArrayOf(1, 1, 2, 2, 3, 3, 4, 4)
        val (paper, sectors) = TagDropCodec.createPaper(
            "Trail Stop 3 — Oak Tree", "sunset-trail", "oak-tree", files, related,
            description = "Day 2 of the sunset trail",
            collectionId = collectionId, collectionLabel = "Sunset Trail 2026", collectionTag = "sunsettrail", icon = "🌲"
        )

        val state = assemble(roundTrip(sectors)) as SectorAssembler.State.PaperReady
        val decoded = state.paper
        assertArrayEquals(paper.rootHash, decoded.rootHash)
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
        assertEquals("trail start at town square", decoded.related[1].hint)
        assertNull(decoded.related[1].lat)
    }

    @Test fun paperRootHashIsContentAddressed() {
        val files = listOf(TagDropPayload.FileEntry("index", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val a = TagDropCodec.createPaper("Trail Stop 3", "sunset-trail", "oak-tree", files).first
        val b = TagDropCodec.createPaper("Trail Stop 3", "sunset-trail", "oak-tree", files).first
        assertEquals(8, a.rootHash.size)
        assertArrayEquals(a.rootHash, b.rootHash)

        val c = TagDropCodec.createPaper("Trail Stop 4", "sunset-trail", "oak-tree", files).first
        assertFalse(a.rootHash.contentEquals(c.rootHash))
    }

    @Test fun decodePaperStreamRoundTrip() {
        val (paper, _) = TagDropCodec.createPaper(
            "Test Paper", "test-set", "test-slug",
            listOf(TagDropPayload.FileEntry("readme", "text/plain", byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12)))
        )
        val decoded = TagDropCodec.decodePaperStream(TagDropCodec.paperStreamBytes(paper))
        assertNotNull(decoded)
        assertArrayEquals(paper.rootHash, decoded!!.rootHash)
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
        val (_, sectors) = TagDropCodec.createPaper(
            null, null, null, emptyList(),
            related = listOf(
                TagDropPayload.RelatedPaper("locked related paper", keyMaterial = relatedKey, retainKey = false),
                TagDropPayload.RelatedPaper("plain related paper")
            ),
            keyMaterial = paperKey, retainKey = false
        )
        val decoded = (assemble(roundTrip(sectors)) as SectorAssembler.State.PaperReady).paper
        assertArrayEquals(paperKey, decoded.keyMaterial)
        assertFalse(decoded.retainKey)
        assertArrayEquals(relatedKey, decoded.related[0].keyMaterial)
        assertFalse(decoded.related[0].retainKey)
        assertNull(decoded.related[1].keyMaterial)
        assertTrue(decoded.related[1].retainKey)
    }

    @Test fun paperMultiSectorRoundTrip() {
        val files = (0 until 60).map {
            TagDropPayload.FileEntry("file-$it", "text/plain", ByteArray(8) { (it).toByte() })
        }
        val (paper, sectors) = TagDropCodec.createPaper("Big Paper", null, null, files, maxSectorDataBytes = 400)
        assertTrue("a large paper should span several sectors", sectors.size > 1)
        val decoded = (assemble(roundTrip(sectors), shuffle = true) as SectorAssembler.State.PaperReady).paper
        assertArrayEquals(paper.rootHash, decoded.rootHash)
        assertEquals(60, decoded.files.size)
    }

    // ── Key-only code (SPEC §9) ────────────────────────────────────────────────

    @Test fun keyCodeOmitsCacheIdAndContent() {
        val key = TagDropCodec.generateKeyMaterial()
        val sector = TagDropCodec.createKeyCodeSector(key, hint = "key for the trailhead box")
        assertNull("a key-only code omits cache_id (SPEC §9)", sector.partMeta.cacheId)
        assertFalse("part_meta omits key 2", partMetaOf(sector).containsKey(2))

        val core = coreOf(sector)
        assertTrue(core.containsKey(30))   // key_material
        assertFalse(core.containsKey(4))   // mime_type
        assertFalse(core.containsKey(5))   // content

        val state = assemble(roundTrip(listOf(sector))) as SectorAssembler.State.ContentReady
        assertArrayEquals(key, state.keyMaterial)
        assertEquals("key for the trailhead box", state.hint)
        assertEquals("", state.mimeType)
        assertTrue(state.content.isEmpty())
        assertNull(state.cacheId)
    }

    @Test fun keyCodeRetainKeyFalseRoundTrip() {
        val key = TagDropCodec.generateKeyMaterial()
        val sector = TagDropCodec.createKeyCodeSector(key, retainKey = false)
        val state = assemble(roundTrip(listOf(sector))) as SectorAssembler.State.ContentReady
        assertFalse(state.retainKey)
    }

    // ── Encryption / hidden override map (SPEC §9) ─────────────────────────────

    @Test fun encryptedContentUsesRandomCacheId() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(content = "secret".toByteArray())
        val sector = TagDropCodec.createContentSectors(
            null, null, "text/plain", "cover".toByteArray(), override = override, encryptionKey = key
        ).first()
        assertEquals(TagDropCodec.ENCRYPTION_AES256GCM.toLong(), coreOf(sector)[28])
        assertFalse(sector.partMeta.cacheId!!.contentEquals(TagDropCodec.contentId("cover".toByteArray())))
    }

    @Test fun encryptedDeflateContentAwaitsKeyThenResolves() {
        val key = TagDropCodec.generateKeyMaterial()
        val real = "The quick brown fox. ".repeat(80).toByteArray()
        val override = TagDropPayload.OverrideMap(hint = "real hint", filename = "fox.txt", content = real)
        val sectors = TagDropCodec.createContentSectors(
            "cover hint", "cover.txt", "text/plain", "cover story. ".repeat(40).toByteArray(),
            compress = true, override = override, encryptionKey = key
        )
        val a = SectorAssembler()
        roundTrip(sectors).forEach { a.add(it) }

        // The content slot is an AES-GCM blob that won't inflate as plain content → awaits a key.
        assertTrue(a.currentState() is SectorAssembler.State.AwaitingKey)
        assertTrue("a non-matching key changes nothing", a.tryKey(TagDropCodec.generateKeyMaterial()).isEmpty())

        val resolved = a.tryKey(key)
        assertEquals(1, resolved.size)
        assertArrayEquals(real, resolved[0].content)
        assertEquals("real hint", resolved[0].hint)
        assertEquals("fox.txt", resolved[0].filename)
        assertNull(resolved[0].pendingOverrideBlob)
    }

    @Test fun encryptedPlainContentShowsCoverWithPendingBlob() {
        val key = TagDropCodec.generateKeyMaterial()
        val override = TagDropPayload.OverrideMap(hint = "real hint", content = "secret trail notes".toByteArray())
        // compression none → the blob "decompresses" trivially (identity) and is shown as opaque cover.
        val sectors = TagDropCodec.createContentSectors(
            "cover hint", null, "text/plain", "cover".toByteArray(), override = override, encryptionKey = key
        )
        val state = assemble(roundTrip(sectors)) as SectorAssembler.State.ContentReady
        assertEquals("cover hint", state.hint)
        assertNotNull(state.pendingOverrideBlob)
        assertTrue(state.wasEncrypted)

        // A later matching key self-corrects to the real fields (as ReceiveActivity.unlockPending does).
        val ov = TagDropCodec.tryDecryptOverrideMap(state.pendingOverrideBlob!!, key, state.pendingOverrideCompression)
        assertNotNull(ov)
        assertArrayEquals("secret trail notes".toByteArray(), ov!!.content)
        assertEquals("real hint", ov.hint)
    }

    @Test fun unencryptedContentHasNoPendingBlobUnderMinSize() {
        val state = assemble(
            roundTrip(TagDropCodec.createContentSectors(null, null, "text/plain", "hi".toByteArray()))
        ) as SectorAssembler.State.ContentReady
        assertNull(state.pendingOverrideBlob)
        assertFalse(state.wasEncrypted)
    }

    // ── Erasure coding: XOR parity (SPEC §5) ───────────────────────────────────

    @Test fun paritySectorReconstructsOneMissingDataSector() {
        val content = ByteArray(2000) { it.toByte() }
        val sectors = TagDropCodec.createContentSectors(null, null, "text/plain", content, maxSectorDataBytes = 600)
        assertTrue(sectors.size >= 3)
        val parity = TagDropCodec.paritySector(sectors)
        assertEquals(sectors.size, parity.partMeta.sectorIndex)       // index == sector_count
        assertEquals(TagDropCodec.PARITY_XOR, parity.partMeta.paritySchemeRaw)

        val a = SectorAssembler()
        // Drop data sector index 1; everything else (plus parity) is present.
        sectors.filter { it.partMeta.sectorIndex != 1 }.forEach { a.add(it) }
        assertTrue(a.currentState() is SectorAssembler.State.Collecting)

        val state = a.add(parity) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
    }

    @Test fun parityReconstructsMissingLastSector() {
        val content = ByteArray(1750) { it.toByte() }
        val sectors = TagDropCodec.createContentSectors(null, null, "text/plain", content, maxSectorDataBytes = 600)
        val parity = TagDropCodec.paritySector(sectors)

        val a = SectorAssembler()
        sectors.dropLast(1).forEach { a.add(it) }   // drop the (shorter) final data sector
        val state = a.add(parity) as SectorAssembler.State.ContentReady
        assertArrayEquals(content, state.content)
    }

    // ── Diagnostics ────────────────────────────────────────────────────────────

    @Test fun describeCborShowsEnvelopeAndCoreFields() {
        val sector = TagDropCodec.createContentSectors("a hint", "f.txt", "text/plain", "hi".toByteArray()).first()
        val text = TagDropCodec.describeCbor(TagDropCodec.sectorCbor(sector))
        assertTrue(text.contains("version: 1"))
        assertTrue(text.contains("type: 0 (Content)"))
        assertTrue(text.contains("part_meta:"))
        assertTrue(text.contains("42 (sector_index)"))
        assertTrue(text.contains("43 (sector_count)"))
        assertTrue(text.contains("3 (hint/label): \"a hint\""))
        assertTrue(text.contains("4 (mime_type): \"text/plain\""))
    }

    @Test fun describeCborShowsPaperDirectory() {
        val (_, sectors) = TagDropCodec.createPaper(
            "Test Paper", "test-set", "test-slug",
            listOf(TagDropPayload.FileEntry("readme", "text/plain", byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12))),
            listOf(TagDropPayload.RelatedPaper("hint text", set = "test-set", slug = "other")),
            icon = "🌳"
        )
        val text = TagDropCodec.describeCbor(TagDropCodec.sectorCbor(sectors.first()))
        assertTrue(text.contains("type: 1 (Paper)"))
        assertTrue(text.contains("3 (hint/label): \"Test Paper\""))
        assertTrue(text.contains("15 (files): ["))
        assertTrue(text.contains("20 (slug): \"readme\""))
        assertTrue(text.contains("16 (related): ["))
        assertTrue(text.contains("24 (icon): \"🌳\""))
    }

    @Test fun describeCborHandlesMalformedBytes() {
        assertTrue(TagDropCodec.describeCbor(byteArrayOf(0x01)).contains("Failed to decode as CBOR sequence"))
    }

    // ── Content addressing ─────────────────────────────────────────────────────

    @Test fun contentIdIs8BytesAndDeterministic() {
        val content = "same content produces same id".toByteArray()
        assertEquals(8, TagDropCodec.contentId(content).size)
        assertArrayEquals(TagDropCodec.contentId(content), TagDropCodec.contentId(content))
        assertFalse(TagDropCodec.contentId("A".toByteArray()).contentEquals(TagDropCodec.contentId("B".toByteArray())))
    }

    @Test fun randomCacheIdIs8Bytes() {
        assertEquals(8, TagDropCodec.randomCacheId().size)
    }

    // ── Compression helpers ────────────────────────────────────────────────────

    @Test fun compressDecompressRoundTrip() {
        val original = "The quick brown fox jumps over the lazy dog".repeat(20).toByteArray()
        val compressed = TagDropCodec.compress(original)
        assertTrue("compression should reduce size", compressed.size < original.size)
        assertArrayEquals(original, TagDropCodec.decompress(compressed))
        assertArrayEquals(original, TagDropCodec.decompressPayload(compressed, TagDropCodec.COMPRESSION_DEFLATE))
        assertArrayEquals(original, TagDropCodec.decompressPayload(original, TagDropCodec.COMPRESSION_NONE))
    }

    // ── Encryption primitives (SPEC §9) ────────────────────────────────────────

    @Test fun encryptAesGcmRoundTrip() {
        val key = TagDropCodec.generateKeyMaterial()
        val nonce = TagDropCodec.generateNonce()
        assertEquals(32, key.size)
        assertEquals(12, nonce.size)
        val plaintext = "secret message".toByteArray()
        val ciphertext = TagDropCodec.encryptAesGcm(plaintext, key, nonce)
        assertArrayEquals(plaintext, TagDropCodec.decryptAesGcm(ciphertext, key, nonce))
        assertNull(TagDropCodec.decryptAesGcm(ciphertext, TagDropCodec.generateKeyMaterial(), nonce))
        assertNull(TagDropCodec.decryptAesGcm(ciphertext, key, TagDropCodec.generateNonce()))
    }

    // ── Passphrase-based key derivation (SPEC §9) ──────────────────────────────

    @Test fun deriveKeyFromPassphraseIsDeterministic() {
        val salt = ByteArray(16) { it.toByte() }
        val key1 = TagDropCodec.deriveKeyFromPassphrase("correct horse battery staple", salt, 1000)
        val key2 = TagDropCodec.deriveKeyFromPassphrase("correct horse battery staple", salt, 1000)
        assertEquals(32, key1.size)
        assertArrayEquals(key1, key2)
    }

    @Test fun deriveKeyFromPassphraseDiffersForDifferentInputs() {
        val salt = ByteArray(16) { it.toByte() }
        val baseline = TagDropCodec.deriveKeyFromPassphrase("trailhead2026", salt, 1000)
        assertFalse(baseline.contentEquals(TagDropCodec.deriveKeyFromPassphrase("wrong guess", salt, 1000)))
        assertFalse(baseline.contentEquals(TagDropCodec.deriveKeyFromPassphrase("trailhead2026", ByteArray(16) { (it + 1).toByte() }, 1000)))
    }

    @Test fun passphraseDerivedKeyUnlocksOverrideBlob() {
        val passphrase = "trailhead2026"
        val salt = ByteArray(16) { it.toByte() }
        val key = TagDropCodec.deriveKeyFromPassphrase(passphrase, salt, 1000)
        val blob = TagDropCodec.encryptOverrideMap(
            TagDropPayload.OverrideMap(content = "the treasure is under the oak".toByteArray()), key, TagDropCodec.COMPRESSION_NONE
        )
        val rederived = TagDropCodec.deriveKeyFromPassphrase(passphrase, salt, 1000)
        val ov = TagDropCodec.tryDecryptOverrideMap(blob, rederived, TagDropCodec.COMPRESSION_NONE)
        assertArrayEquals("the treasure is under the oak".toByteArray(), ov!!.content)
        // Wrong passphrase derives a non-matching key.
        val wrong = TagDropCodec.deriveKeyFromPassphrase("nope", salt, 1000)
        assertNull(TagDropCodec.tryDecryptOverrideMap(blob, wrong, TagDropCodec.COMPRESSION_NONE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun deriveKeyFromPassphraseWithZeroIterationsThrows() {
        TagDropCodec.deriveKeyFromPassphrase("pass", ByteArray(16), 0)
    }
}
