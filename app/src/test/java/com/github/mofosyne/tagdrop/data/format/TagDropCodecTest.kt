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
                    paperId = byteArrayOf(17, 18, 19, 20, 21, 22, 23, 24)),
                TagDropPayload.RelatedPaper("trail start at town square")
            )
        )

        val uri = TagDropCodec.encode(original)
        assertTrue(uri.startsWith("tagdrop://v1/p/"))

        val decoded = TagDropCodec.decode(uri) as TagDropPayload.PaperManifest
        assertArrayEquals(rootHash, decoded.rootHash)
        assertEquals("Trail Stop 3 — Oak Tree", decoded.label)
        assertEquals("sunset-trail", decoded.set)
        assertEquals("oak-tree", decoded.slug)

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
        assertEquals("trail start at town square", decoded.related[1].hint)
        assertNull(decoded.related[1].set)
        assertNull(decoded.related[1].paperId)
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
