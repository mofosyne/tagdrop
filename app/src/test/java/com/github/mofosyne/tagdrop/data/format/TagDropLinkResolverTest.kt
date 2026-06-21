package com.github.mofosyne.tagdrop.data.format

import androidx.lifecycle.LiveData
import androidx.room.InvalidationTracker
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.CacheDao
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.KeyDao
import com.github.mofosyne.tagdrop.data.db.PaperDao
import com.github.mofosyne.tagdrop.data.db.RetainedKey
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Hand-rolled in-memory fakes for the three DAOs. TagDropLinkResolver only ever calls
 * paperDao().getByRootHash/getAllPapers and cacheDao().getById, so these avoid pulling in
 * Robolectric/SQLite just to exercise pure lookup logic against a plain Kotlin map.
 */
private class FakePaperDao : PaperDao {
    val papers = mutableMapOf<String, ScannedPaper>()
    override fun getAll(): LiveData<List<ScannedPaper>> = throw NotImplementedError()
    override suspend fun getAllPapers(): List<ScannedPaper> = papers.values.toList()
    override suspend fun getByRootHash(rootHash: String): ScannedPaper? = papers[rootHash]
    override fun observeByRootHash(rootHash: String): LiveData<ScannedPaper?> = throw NotImplementedError()
    override suspend fun insert(paper: ScannedPaper) { papers[paper.rootHash] = paper }
    override suspend fun delete(paper: ScannedPaper) { papers.remove(paper.rootHash) }
}

private class FakeCacheDao : CacheDao {
    val caches = mutableMapOf<String, FoundCache>()
    override fun getAllCaches(): LiveData<List<FoundCache>> = throw NotImplementedError()
    override suspend fun insert(cache: FoundCache) { caches[cache.cacheId] = cache }
    override suspend fun delete(cache: FoundCache) { caches.remove(cache.cacheId) }
    override suspend fun getById(id: String): FoundCache? = caches[id]
    override fun observeById(id: String): LiveData<FoundCache?> = throw NotImplementedError()
    override fun getByCollectionId(collectionId: String): LiveData<List<FoundCache>> = throw NotImplementedError()
    override suspend fun deleteByCollectionId(collectionId: String) {}
    override suspend fun getPendingOverrides(): List<FoundCache> = emptyList()
}

private class FakeKeyDao : KeyDao {
    override suspend fun getAll(): List<RetainedKey> = emptyList()
    override suspend fun insert(key: RetainedKey) {}
    override suspend fun delete(key: RetainedKey) {}
    override suspend fun deleteAll() {}
}

private class FakeAppDatabase(
    val paperDaoFake: FakePaperDao = FakePaperDao(),
    val cacheDaoFake: FakeCacheDao = FakeCacheDao(),
) : AppDatabase() {
    override fun paperDao(): PaperDao = paperDaoFake
    override fun cacheDao(): CacheDao = cacheDaoFake
    override fun keyDao(): KeyDao = FakeKeyDao()
    override fun createInvalidationTracker(): InvalidationTracker = throw NotImplementedError()
    override fun clearAllTables() = throw NotImplementedError()
}

class TagDropLinkResolverTest {

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private val paperDao = FakePaperDao()
    private val cacheDao = FakeCacheDao()
    private val resolver = TagDropLinkResolver(FakeAppDatabase(paperDao, cacheDao))

    /** Stores a paper with the given files and returns it (with its computed root hash already lowercase-hex). */
    private fun storePaper(label: String? = "Test Paper", files: List<TagDropPayload.FileEntry> = emptyList()): ScannedPaper {
        val (manifest, _) = TagDropCodec.createPaper(label = label, set = null, slug = null, files = files)
        val paper = ScannedPaper(
            rootHash = manifest.rootHash.toHex(),
            scannedAt = 0L,
            label = label,
            set = null,
            slug = null,
            cborBytes = TagDropCodec.paperStreamBytes(manifest)
        )
        paperDao.papers[paper.rootHash] = paper
        return paper
    }

    private fun storeCache(cacheId: ByteArray, mimeType: String, content: ByteArray): FoundCache {
        val cache = FoundCache(
            cacheId = cacheId.toHex(), discoveredAt = 0L, hint = null, filename = null,
            mimeType = mimeType, contentBytes = content
        )
        cacheDao.caches[cache.cacheId] = cache
        return cache
    }

    // ── Disambiguation: not a TagDrop link at all ────────────────────────────

    @Test fun ordinaryUrlIsNotTagDrop() = runBlocking {
        assertEquals(TagDropLinkResolver.Resolution.NotTagDrop, resolver.resolve("https://example.com/foo"))
    }

    @Test fun arbitraryStringIsNotTagDrop() = runBlocking {
        assertEquals(TagDropLinkResolver.Resolution.NotTagDrop, resolver.resolve("not a uri at all"))
    }

    @Test fun nonSyntheticHttpsHostIsNotTagDrop() = runBlocking {
        assertEquals(TagDropLinkResolver.Resolution.NotTagDrop, resolver.resolve("https://paper.tagdrop.invalid/slug"))
    }

    @Test fun encodingUriIsPassedThroughUnresolved() = runBlocking {
        assertEquals(TagDropLinkResolver.Resolution.EncodingUri, resolver.resolve("tagdrop:ABCDEFGH"))
    }

    // ── Invalid root hash ─────────────────────────────────────────────────────

    @Test fun navLinkWithNonHexRootHashIsInvalid() = runBlocking {
        assertEquals(TagDropLinkResolver.Resolution.Invalid, resolver.resolve("tagdrop://not-hex-zzzz/slug"))
    }

    @Test fun navLinkWithOddLengthHexIsInvalid() = runBlocking {
        // An odd number of hex digits can't represent a whole number of bytes.
        assertEquals(TagDropLinkResolver.Resolution.Invalid, resolver.resolve("tagdrop://abc/slug"))
    }

    @Test fun syntheticHostWithNonHexRootHashIsInvalid() = runBlocking {
        assertEquals(
            TagDropLinkResolver.Resolution.Invalid,
            resolver.resolve("https://not-hex.paper.tagdrop.invalid/slug")
        )
    }

    // ── Forward-compatibility: root hash length isn't pinned to today's 8 bytes ─

    @Test fun navLinkAcceptsLongerThanTodaysRootHashLength() = runBlocking {
        // 24 hex chars = 12 bytes, longer than today's 8-byte truncation. Should reach the
        // DB lookup (and miss) rather than being rejected by the parser itself.
        val longHex = "001122334455667788990011"
        val result = resolver.resolve("tagdrop://$longHex/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperNotFound(longHex, "index"), result)
    }

    @Test fun navLinkAcceptsShorterThanTodaysRootHashLength() = runBlocking {
        val shortHex = "00ff" // 2 bytes
        val result = resolver.resolve("tagdrop://$shortHex/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperNotFound(shortHex, "index"), result)
    }

    // ── Root hash casing/lookup ───────────────────────────────────────────────

    @Test fun navLinkRootHashIsLowercasedBeforeLookup() = runBlocking {
        val paper = storePaper()
        val result = resolver.resolve("tagdrop://${paper.rootHash.uppercase()}")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), result)
    }

    @Test fun unknownRootHashIsPaperNotFound() = runBlocking {
        val hex = "0011223344556677"
        val result = resolver.resolve("tagdrop://$hex/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperNotFound(hex, "index"), result)
    }

    // ── Slug splitting ────────────────────────────────────────────────────────

    @Test fun navLinkWithNoSlugIsPaperFoundWithNullSlug() = runBlocking {
        val paper = storePaper()
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), resolver.resolve("tagdrop://${paper.rootHash}"))
    }

    @Test fun navLinkWithTrailingSlashIsPaperFoundWithNullSlug() = runBlocking {
        val paper = storePaper()
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), resolver.resolve("tagdrop://${paper.rootHash}/"))
    }

    @Test fun navLinkSlugKeepsOnlyFirstSlashAsSeparator() = runBlocking {
        val paper = storePaper()
        // Only the first '/' separates rootHash from slug -- the rest is part of the slug itself.
        val result = resolver.resolve("tagdrop://${paper.rootHash}/sub/page.html")
        assertEquals(TagDropLinkResolver.Resolution.FileNotFound(paper, "sub/page.html"), result)
    }

    // ── Paper found, file resolution ─────────────────────────────────────────

    @Test fun undecodableManifestStillReturnsPaperFound() = runBlocking {
        val paper = ScannedPaper(
            rootHash = "0011223344556677", scannedAt = 0L, label = null, set = null, slug = null,
            cborBytes = byteArrayOf(1, 2, 3) // garbage, not a valid CBOR sequence
        )
        paperDao.papers[paper.rootHash] = paper
        val result = resolver.resolve("tagdrop://${paper.rootHash}/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, "index"), result)
    }

    @Test fun slugNotInManifestIsFileNotFound() = runBlocking {
        val paper = storePaper()
        val result = resolver.resolve("tagdrop://${paper.rootHash}/missing.html")
        assertEquals(TagDropLinkResolver.Resolution.FileNotFound(paper, "missing.html"), result)
    }

    @Test fun listedFileNotYetCachedIsFileNotCached() = runBlocking {
        val fileId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = TagDropPayload.FileEntry(slug = "doc.html", mimeType = "text/html", fileId = fileId)
        val paper = storePaper(files = listOf(file))
        val result = resolver.resolve("tagdrop://${paper.rootHash}/doc.html")
        assertEquals(TagDropLinkResolver.Resolution.FileNotCached(paper, file), result)
    }

    @Test fun cachedFileIsFileFound() = runBlocking {
        val fileId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = TagDropPayload.FileEntry(slug = "doc.html", mimeType = "text/html", fileId = fileId)
        val paper = storePaper(files = listOf(file))
        val cache = storeCache(fileId, "text/html", "<p>hi</p>".toByteArray())
        val result = resolver.resolve("tagdrop://${paper.rootHash}/doc.html")
        assertEquals(TagDropLinkResolver.Resolution.FileFound(cache, paper, "doc.html"), result)
    }

    // ── Synthetic same-paper host resolves identically to the explicit nav link ─

    @Test fun syntheticHostResolvesSameAsNavLink() = runBlocking {
        val fileId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = TagDropPayload.FileEntry(slug = "doc.html", mimeType = "text/html", fileId = fileId)
        val paper = storePaper(files = listOf(file))
        val cache = storeCache(fileId, "text/html", "<p>hi</p>".toByteArray())
        val url = TagDropLinkResolver.syntheticBaseUrl(paper.rootHash, "doc.html")
        val result = resolver.resolve(url)
        assertEquals(TagDropLinkResolver.Resolution.FileFound(cache, paper, "doc.html"), result)
    }

    @Test fun syntheticHostRootHashIsLowercasedBeforeLookup() = runBlocking {
        val paper = storePaper()
        val result = resolver.resolve("https://${paper.rootHash.uppercase()}.paper.tagdrop.invalid")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), result)
    }

    // ── findPaperContext / findStylesheet ────────────────────────────────────

    @Test fun findPaperContextLocatesFileByCacheId() = runBlocking {
        val fileId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = TagDropPayload.FileEntry(slug = "doc.html", mimeType = "text/html", fileId = fileId)
        val paper = storePaper(files = listOf(file))
        val context = resolver.findPaperContext(fileId.toHex())
        assertEquals(TagDropLinkResolver.PaperContext(paper.rootHash, "doc.html"), context)
    }

    @Test fun findPaperContextReturnsNullForUnknownCacheId() = runBlocking {
        storePaper(files = listOf(TagDropPayload.FileEntry("doc.html", "text/html", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))))
        assertNull(resolver.findPaperContext("ffffffffffffffff"))
    }

    @Test fun findStylesheetReturnsCssContentWhenPresent() = runBlocking {
        val cssId = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val cssFile = TagDropPayload.FileEntry(slug = TagDropLinkResolver.STYLESHEET_SLUG, mimeType = "text/css", fileId = cssId)
        val paper = storePaper(files = listOf(cssFile))
        storeCache(cssId, "text/css", "body { color: red; }".toByteArray())
        assertEquals("body { color: red; }", resolver.findStylesheet(paper.rootHash))
    }

    @Test fun findStylesheetReturnsNullWhenAbsent() = runBlocking {
        val paper = storePaper()
        assertNull(resolver.findStylesheet(paper.rootHash))
    }

    // ── HOME_SLUGS convention ─────────────────────────────────────────────────

    @Test fun homeSlugsContainsExpectedConventionalSlugs() {
        assertEquals(setOf("index", "index.html", "index.md"), TagDropLinkResolver.HOME_SLUGS)
    }

    @Test fun homeSlugsMembershipIsCaseSensitive() {
        assertFalse("INDEX.HTML" in TagDropLinkResolver.HOME_SLUGS)
        assertFalse("Index" in TagDropLinkResolver.HOME_SLUGS)
    }
}
