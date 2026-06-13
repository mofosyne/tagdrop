package com.github.mofosyne.tagdrop.data.format

import org.junit.Assert.*
import org.junit.Test

class TagDropCodecTest {

    // ── Single ────────────────────────────────────────────────────────────────

    @Test fun singleRoundTrip() {
        val original = TagDropPayload.Single(
            cacheId     = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            hint        = "under the bridge",
            filename    = "poem.html",
            mimeType    = "text/html",
            compression = TagDropCodec.COMPRESSION_NONE,
            content     = "<h1>Hello</h1>".toByteArray()
        )
        val uri = TagDropCodec.encode(original)
        assertTrue(uri.startsWith("tagdrop://v1/s/"))

        val decoded = TagDropCodec.decode(uri) as TagDropPayload.Single
        assertArrayEquals(original.cacheId, decoded.cacheId)
        assertEquals(original.hint, decoded.hint)
        assertEquals(original.filename, decoded.filename)
        assertEquals(original.mimeType, decoded.mimeType)
        assertEquals(original.compression, decoded.compression)
        assertArrayEquals(original.content, decoded.content)
    }

    @Test fun singleOptionalFieldsNull() {
        val original = TagDropPayload.Single(
            cacheId = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            hint = null, filename = null,
            mimeType = "text/plain",
            compression = TagDropCodec.COMPRESSION_NONE,
            content = "hello".toByteArray()
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.Single
        assertNull(decoded.hint)
        assertNull(decoded.filename)
        assertNull(decoded.collectionId)
        assertNull(decoded.collectionLabel)
        assertNull(decoded.collectionTag)
    }

    @Test fun singleWithCollectionId() {
        val collectionId = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80.toByte())
        val original = TagDropPayload.Single(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            hint = null, filename = null,
            mimeType = "text/plain",
            compression = TagDropCodec.COMPRESSION_NONE,
            content = "hello".toByteArray(),
            collectionId = collectionId
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.Single
        assertArrayEquals(collectionId, decoded.collectionId)
    }

    @Test fun singleWithCollectionLabelAndTag() {
        val collectionId = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80.toByte())
        val original = TagDropPayload.Single(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            hint = null, filename = null,
            mimeType = "text/plain",
            compression = TagDropCodec.COMPRESSION_NONE,
            content = "hello".toByteArray(),
            collectionId = collectionId,
            collectionLabel = "Spring 2026 Sticker Hunt",
            collectionTag = "springtrail2026"
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.Single
        assertEquals("Spring 2026 Sticker Hunt", decoded.collectionLabel)
        assertEquals("springtrail2026", decoded.collectionTag)
    }

    @Test fun singleWithIcon() {
        val original = TagDropPayload.Single(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            hint = null, filename = null,
            mimeType = "text/plain",
            compression = TagDropCodec.COMPRESSION_NONE,
            content = "hello".toByteArray(),
            icon = "🌳"
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.Single
        assertEquals("🌳", decoded.icon)
    }

    @Test fun singleWithCompression() {
        val original = TagDropPayload.Single(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            hint = null, filename = null,
            mimeType = "text/html",
            compression = TagDropCodec.COMPRESSION_DEFLATE,
            content = TagDropCodec.compress("<html><body>test</body></html>".toByteArray())
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.Single
        assertEquals(TagDropCodec.COMPRESSION_DEFLATE, decoded.compression)
    }

    // ── Manifest ──────────────────────────────────────────────────────────────

    @Test fun manifestRoundTrip() {
        val sha256 = ByteArray(32) { it.toByte() }
        val original = TagDropPayload.Manifest(
            cacheId     = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte()),
            hint        = "trail start",
            filename    = "story.html",
            mimeType    = "text/html",
            compression = TagDropCodec.COMPRESSION_NONE,
            chunkCount  = 4,
            totalBytes  = 3200,
            sha256      = sha256
        )
        val uri = TagDropCodec.encode(original)
        assertTrue(uri.startsWith("tagdrop://v1/m/"))

        val decoded = TagDropCodec.decode(uri) as TagDropPayload.Manifest
        assertArrayEquals(original.cacheId, decoded.cacheId)
        assertEquals("trail start", decoded.hint)
        assertEquals(4, decoded.chunkCount)
        assertEquals(3200, decoded.totalBytes)
        assertArrayEquals(sha256, decoded.sha256)
        assertNull(decoded.collectionId)
        assertNull(decoded.collectionLabel)
        assertNull(decoded.collectionTag)
        assertNull(decoded.icon)
    }

    @Test fun manifestWithIcon() {
        val original = TagDropPayload.Manifest(
            cacheId     = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte()),
            hint        = "trail start",
            filename    = "story.html",
            mimeType    = "text/html",
            compression = TagDropCodec.COMPRESSION_NONE,
            chunkCount  = 4,
            totalBytes  = 3200,
            sha256      = ByteArray(32) { it.toByte() },
            icon        = "📖"
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.Manifest
        assertEquals("📖", decoded.icon)
    }

    // ── Chunk ─────────────────────────────────────────────────────────────────

    @Test fun chunkRoundTrip() {
        val original = TagDropPayload.Chunk(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            index   = 2,
            data    = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        )
        val uri = TagDropCodec.encode(original)
        assertTrue(uri.startsWith("tagdrop://v1/c/"))

        val decoded = TagDropCodec.decode(uri) as TagDropPayload.Chunk
        assertArrayEquals(original.cacheId, decoded.cacheId)
        assertEquals(2, decoded.index)
        assertArrayEquals(original.data, decoded.data)
    }

    // ── PaperManifest ─────────────────────────────────────────────────────────

    @Test fun paperManifestRoundTrip() {
        val rootHash = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
                                   0xEE.toByte(), 0xFF.toByte(), 0x11, 0x22)
        val collectionId = byteArrayOf(1, 1, 2, 2, 3, 3, 4, 4)
        val original = TagDropPayload.PaperManifest(
            rootHash = rootHash,
            label    = "Trail Stop 3 — Oak Tree",
            set      = "sunset-trail",
            slug     = "oak-tree",
            files    = listOf(
                TagDropPayload.FileEntry("index", "text/html",      byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
                TagDropPayload.FileEntry("map",   "image/svg+xml",  byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16))
            ),
            related  = listOf(
                TagDropPayload.RelatedPaper("letterbox 200m north", set = "sunset-trail", slug = "letterbox",
                    paperId = byteArrayOf(17, 18, 19, 20, 21, 22, 23, 24),
                    lat = -33.8688, lng = 151.2093),
                TagDropPayload.RelatedPaper("trail start at town square")
            ),
            collectionId    = collectionId,
            collectionLabel = "Sunset Trail 2026",
            collectionTag   = "sunsettrail",
            icon            = "🌲"
        )

        val uri = TagDropCodec.encode(original)
        assertTrue(uri.startsWith("tagdrop://v1/p/"))

        val decoded = TagDropCodec.decode(uri) as TagDropPayload.PaperManifest
        assertArrayEquals(rootHash, decoded.rootHash)
        assertEquals("Trail Stop 3 — Oak Tree", decoded.label)
        assertEquals("sunset-trail", decoded.set)
        assertEquals("oak-tree", decoded.slug)
        assertArrayEquals(collectionId, decoded.collectionId)
        assertEquals("Sunset Trail 2026", decoded.collectionLabel)
        assertEquals("sunsettrail", decoded.collectionTag)
        assertEquals("🌲", decoded.icon)

        assertEquals(2, decoded.files.size)
        assertEquals("index", decoded.files[0].slug)
        assertEquals("text/html", decoded.files[0].mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), decoded.files[0].fileId)
        assertEquals("map", decoded.files[1].slug)

        assertEquals(2, decoded.related.size)
        assertEquals("letterbox 200m north", decoded.related[0].hint)
        assertEquals("sunset-trail", decoded.related[0].set)
        assertEquals("letterbox", decoded.related[0].slug)
        assertArrayEquals(byteArrayOf(17, 18, 19, 20, 21, 22, 23, 24), decoded.related[0].paperId)
        assertEquals(-33.8688, decoded.related[0].lat!!, 0.0)
        assertEquals(151.2093, decoded.related[0].lng!!, 0.0)
        assertEquals("trail start at town square", decoded.related[1].hint)
        assertNull(decoded.related[1].set)
        assertNull(decoded.related[1].paperId)
        assertNull(decoded.related[1].lat)
        assertNull(decoded.related[1].lng)
    }

    @Test fun paperManifestEmptyFilesAndRelated() {
        val original = TagDropPayload.PaperManifest(
            rootHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            label = null, set = null, slug = null,
            files = emptyList(), related = emptyList()
        )
        val decoded = TagDropCodec.decode(TagDropCodec.encode(original)) as TagDropPayload.PaperManifest
        assertTrue(decoded.files.isEmpty())
        assertTrue(decoded.related.isEmpty())
    }

    @Test fun decodePaperManifestCborRoundTrip() {
        val original = TagDropPayload.PaperManifest(
            rootHash = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 1, 2, 3, 4),
            label = "Test Paper", set = "test-set", slug = "test-slug",
            files = listOf(TagDropPayload.FileEntry("readme", "text/plain", byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12))),
            related = emptyList()
        )
        val cbor = TagDropCodec.paperManifestCbor(original)
        val decoded = TagDropCodec.decodePaperManifestCbor(cbor)
        assertNotNull(decoded)
        assertArrayEquals(original.rootHash, decoded!!.rootHash)
        assertEquals("Test Paper", decoded.label)
        assertEquals(1, decoded.files.size)
        assertEquals("readme", decoded.files[0].slug)
    }

    // ── Raw CBOR (diagnostics) ────────────────────────────────────────────────

    @Test fun singleCborMatchesEncodedUri() {
        val original = TagDropPayload.Single(
            cacheId     = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            hint        = "under the bridge",
            filename    = "poem.html",
            mimeType    = "text/html",
            compression = TagDropCodec.COMPRESSION_NONE,
            content     = "<h1>Hello</h1>".toByteArray()
        )
        val cbor = TagDropCodec.singleCbor(original)
        val uriCbor = Base45.decode(TagDropCodec.encode(original).removePrefix("tagdrop://v1/s/"))
        assertArrayEquals(uriCbor, cbor)

        val decoded = MiniCbor.decodeMap(cbor)
        assertEquals("under the bridge", decoded[3])
        assertEquals("text/html", decoded[4])
    }

    @Test fun manifestCborMatchesEncodedUri() {
        val original = TagDropPayload.Manifest(
            cacheId     = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80.toByte()),
            hint        = "trail start",
            filename    = "story.html",
            mimeType    = "text/html",
            compression = TagDropCodec.COMPRESSION_NONE,
            chunkCount  = 4,
            totalBytes  = 3200,
            sha256      = ByteArray(32) { it.toByte() }
        )
        val cbor = TagDropCodec.manifestCbor(original)
        val uriCbor = Base45.decode(TagDropCodec.encode(original).removePrefix("tagdrop://v1/m/"))
        assertArrayEquals(uriCbor, cbor)
    }

    @Test fun chunkCborMatchesEncodedUri() {
        val original = TagDropPayload.Chunk(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            index   = 2,
            data    = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        )
        val cbor = TagDropCodec.chunkCbor(original)
        val uriCbor = Base45.decode(TagDropCodec.encode(original).removePrefix("tagdrop://v1/c/"))
        assertArrayEquals(uriCbor, cbor)
    }

    @Test fun rawCborDispatchesByPayloadType() {
        val single = TagDropPayload.Single(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), hint = null, filename = null,
            mimeType = "text/plain", compression = TagDropCodec.COMPRESSION_NONE, content = "hi".toByteArray()
        )
        assertArrayEquals(TagDropCodec.singleCbor(single), TagDropCodec.rawCbor(single))

        val manifest = TagDropPayload.Manifest(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), hint = null, filename = null,
            mimeType = "text/plain", compression = TagDropCodec.COMPRESSION_NONE,
            chunkCount = 1, totalBytes = 10, sha256 = ByteArray(32)
        )
        assertArrayEquals(TagDropCodec.manifestCbor(manifest), TagDropCodec.rawCbor(manifest))

        val chunk = TagDropPayload.Chunk(cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), index = 0, data = byteArrayOf(1))
        assertArrayEquals(TagDropCodec.chunkCbor(chunk), TagDropCodec.rawCbor(chunk))

        val paper = TagDropPayload.PaperManifest(
            rootHash = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), label = null, set = null, slug = null,
            files = emptyList(), related = emptyList()
        )
        assertArrayEquals(TagDropCodec.paperManifestCbor(paper), TagDropCodec.rawCbor(paper))

        assertNull(TagDropCodec.rawCbor(TagDropPayload.Legacy("data:text/plain;base64,aGk=")))
    }

    @Test fun singleCborDescribable() {
        val original = TagDropPayload.Single(
            cacheId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), hint = "a hint", filename = "f.txt",
            mimeType = "text/plain", compression = TagDropCodec.COMPRESSION_NONE, content = "hi".toByteArray()
        )
        val text = TagDropCodec.describeCbor(TagDropCodec.singleCbor(original))
        assertTrue(text.contains("3 (hint/label): \"a hint\""))
        assertTrue(text.contains("4 (mime_type): \"text/plain\""))
        assertTrue(text.contains("5 (content):"))
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    @Test fun describeCborIncludesKeyNamesAndValues() {
        val original = TagDropPayload.PaperManifest(
            rootHash = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(),
                                    0xEE.toByte(), 0xFF.toByte(), 0x11, 0x22),
            label = "Test Paper", set = "test-set", slug = "test-slug",
            files = listOf(TagDropPayload.FileEntry("readme", "text/plain", byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12))),
            related = listOf(TagDropPayload.RelatedPaper("hint text", set = "test-set", slug = "other",
                lat = -33.8688, lng = 151.2093)),
            icon = "🌳"
        )
        val cbor = TagDropCodec.paperManifestCbor(original)
        val text = TagDropCodec.describeCbor(cbor)

        assertTrue(text.contains("${cbor.size} bytes"))
        assertTrue(text.contains("1 (version): 1"))
        assertTrue(text.contains("2 (cache_id/root_hash): aa bb cc dd ee ff 11 22 (8 bytes)"))
        assertTrue(text.contains("3 (hint/label): \"Test Paper\""))
        assertTrue(text.contains("15 (files): ["))
        assertTrue(text.contains("20 (slug): \"readme\""))
        assertTrue(text.contains("16 (related): ["))
        assertTrue(text.contains("24 (icon): \"🌳\""))
        assertTrue(text.contains("26 (lat): -33.8688"))
        assertTrue(text.contains("27 (lng): 151.2093"))
    }

    @Test fun describeCborHandlesMalformedBytes() {
        val text = TagDropCodec.describeCbor(byteArrayOf(0x01))
        assertTrue(text.contains("Failed to decode as CBOR map"))
    }

    // ── Legacy ────────────────────────────────────────────────────────────────

    @Test fun legacyDataUri() {
        val uri = "data:text/html;base64,AAAA"
        val decoded = TagDropCodec.decode(uri)
        assertTrue(decoded is TagDropPayload.Legacy)
        assertEquals(uri, (decoded as TagDropPayload.Legacy).dataUri)
    }

    @Test fun unknownSchemeReturnsNull() {
        assertNull(TagDropCodec.decode("https://example.com"))
        assertNull(TagDropCodec.decode("tagdrop://v1/x/ABCD"))
        assertNull(TagDropCodec.decode(""))
    }

    @Test fun malformedBase45ReturnsNull() {
        assertNull(TagDropCodec.decode("tagdrop://v1/s/!!!!INVALID!!!!"))
    }

    // ── Content addressing ────────────────────────────────────────────────────

    @Test fun contentIdIs8Bytes() {
        val id = TagDropCodec.contentId("hello".toByteArray())
        assertEquals(8, id.size)
    }

    @Test fun contentIdDeterministic() {
        val content = "same content produces same id".toByteArray()
        assertArrayEquals(TagDropCodec.contentId(content), TagDropCodec.contentId(content))
    }

    @Test fun contentIdDiffersForDifferentContent() {
        val id1 = TagDropCodec.contentId("content A".toByteArray())
        val id2 = TagDropCodec.contentId("content B".toByteArray())
        assertFalse(id1.contentEquals(id2))
    }

    @Test fun rootHashOf8Bytes() {
        val hash = TagDropCodec.rootHashOf(byteArrayOf(1, 2, 3, 4))
        assertEquals(8, hash.size)
    }

    @Test fun rootHashDeterministic() {
        val cbor = byteArrayOf(0xA1.toByte(), 0x01, 0x01)  // {1: 1}
        assertArrayEquals(TagDropCodec.rootHashOf(cbor), TagDropCodec.rootHashOf(cbor))
    }

    // ── createSingle factory ─────────────────────────────────────────────────

    @Test fun createSingleSetsContentAddressedId() {
        val content = "Hello, TagDrop!".toByteArray()
        val payload = TagDropCodec.createSingle(null, null, "text/plain", content)
        assertArrayEquals(TagDropCodec.contentId(content), payload.cacheId)
        assertEquals(TagDropCodec.COMPRESSION_NONE, payload.compression)
        assertArrayEquals(content, payload.content)
    }

    @Test fun createSingleWithCompression() {
        val content = "<html><body>Hello!</body></html>".toByteArray()
        val payload = TagDropCodec.createSingle(null, null, "text/html", content, compress = true)
        assertEquals(TagDropCodec.COMPRESSION_DEFLATE, payload.compression)
        assertArrayEquals(TagDropCodec.contentId(content), payload.cacheId)
        assertArrayEquals(content, TagDropCodec.decompress(payload.content))
    }

    @Test fun createSingleWithCollectionId() {
        val collectionId = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val payload = TagDropCodec.createSingle(null, null, "text/plain", "hi".toByteArray(), collectionId = collectionId)
        assertArrayEquals(collectionId, payload.collectionId)
    }

    @Test fun createSingleWithCollectionLabelAndTag() {
        val collectionId = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val payload = TagDropCodec.createSingle(
            null, null, "text/plain", "hi".toByteArray(),
            collectionId = collectionId, collectionLabel = "Garden Trail", collectionTag = "gardentrail"
        )
        assertEquals("Garden Trail", payload.collectionLabel)
        assertEquals("gardentrail", payload.collectionTag)
    }

    @Test fun createSingleWithIcon() {
        val payload = TagDropCodec.createSingle(null, null, "text/plain", "hi".toByteArray(), icon = "🌳")
        assertEquals("🌳", payload.icon)
    }

    // ── Compression helpers ───────────────────────────────────────────────────

    @Test fun compressDecompressRoundTrip() {
        val original = "The quick brown fox jumps over the lazy dog".repeat(20).toByteArray()
        val compressed = TagDropCodec.compress(original)
        assertTrue("compression should reduce size", compressed.size < original.size)
        assertArrayEquals(original, TagDropCodec.decompress(compressed))
    }

    @Test fun decompressPayloadNonePassthrough() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, TagDropCodec.decompressPayload(bytes, TagDropCodec.COMPRESSION_NONE))
    }

    @Test fun decompressPayloadDeflate() {
        val original = "test payload".toByteArray()
        val compressed = TagDropCodec.compress(original)
        assertArrayEquals(original, TagDropCodec.decompressPayload(compressed, TagDropCodec.COMPRESSION_DEFLATE))
    }
}
