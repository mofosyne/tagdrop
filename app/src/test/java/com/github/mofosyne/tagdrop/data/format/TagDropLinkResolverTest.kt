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
    override suspend fun getRepliesTo(parentId: String): List<FoundCache> =
        caches.values.filter { it.inReplyTo == parentId }
    override suspend fun updateMimeType(cacheId: String, mimeType: String) {
        caches[cacheId]?.let { caches[cacheId] = it.copy(mimeType = mimeType, mimeTypeIsGuessed = false) }
    }
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
    private fun storePaper(
        label: String? = "Test Paper",
        files: List<TagDropPayload.FileEntry> = emptyList(),
        slug: String? = null,
        domain: String? = null,
        scannedAt: Long = 0L,
        lat: Double? = null,
        lng: Double? = null
    ): ScannedPaper {
        val (manifest, _) = TagDropCodec.createPaper(label = label, set = null, slug = slug, files = files, domain = domain)
        val paper = ScannedPaper(
            rootHash = manifest.rootHash.toHex(),
            scannedAt = scannedAt,
            label = label,
            set = null,
            slug = slug,
            domain = domain,
            lat = lat,
            lng = lng,
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

    // ── Non-hex host: treated as an (uncoordinated) domain name, not rejected ───

    @Test fun navLinkWithNonHexHostAndNoDomainClaimIsDomainNotFound() = runBlocking {
        assertEquals(
            TagDropLinkResolver.Resolution.DomainNotFound("not-hex-zzzz", "slug"),
            resolver.resolve("tagdrop://not-hex-zzzz/slug")
        )
    }

    @Test fun navLinkWithOddLengthHexAndNoDomainClaimIsDomainNotFound() = runBlocking {
        // An odd number of hex digits can't represent a whole number of bytes, so this host
        // isn't hex-shaped either -- treated as an (unclaimed) domain name instead of rejected.
        assertEquals(
            TagDropLinkResolver.Resolution.DomainNotFound("abc", "slug"),
            resolver.resolve("tagdrop://abc/slug")
        )
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
        val result = resolver.resolve("tagdrop://@$longHex/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperNotFound(longHex, "index"), result)
    }

    @Test fun navLinkAcceptsShorterThanTodaysRootHashLength() = runBlocking {
        val shortHex = "00ff" // 2 bytes
        val result = resolver.resolve("tagdrop://@$shortHex/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperNotFound(shortHex, "index"), result)
    }

    // ── Root hash casing/lookup ───────────────────────────────────────────────

    @Test fun navLinkRootHashIsLowercasedBeforeLookup() = runBlocking {
        val paper = storePaper()
        val result = resolver.resolve("tagdrop://@${paper.rootHash.uppercase()}")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), result)
    }

    @Test fun unknownRootHashIsPaperNotFound() = runBlocking {
        val hex = "0011223344556677"
        val result = resolver.resolve("tagdrop://@$hex/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperNotFound(hex, "index"), result)
    }

    // ── Slug splitting ────────────────────────────────────────────────────────

    @Test fun navLinkWithNoSlugIsPaperFoundWithNullSlug() = runBlocking {
        val paper = storePaper()
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), resolver.resolve("tagdrop://@${paper.rootHash}"))
    }

    @Test fun navLinkWithTrailingSlashIsPaperFoundWithNullSlug() = runBlocking {
        val paper = storePaper()
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), resolver.resolve("tagdrop://@${paper.rootHash}/"))
    }

    @Test fun navLinkSlugKeepsOnlyFirstSlashAsSeparator() = runBlocking {
        val paper = storePaper()
        // Only the first '/' separates rootHash from slug -- the rest is part of the slug itself.
        val result = resolver.resolve("tagdrop://@${paper.rootHash}/sub/page.html")
        assertEquals(TagDropLinkResolver.Resolution.FileNotFound(paper, "sub/page.html"), result)
    }

    // ── Paper found, file resolution ─────────────────────────────────────────

    @Test fun undecodableManifestStillReturnsPaperFound() = runBlocking {
        val paper = ScannedPaper(
            rootHash = "0011223344556677", scannedAt = 0L, label = null, set = null, slug = null,
            cborBytes = byteArrayOf(1, 2, 3) // garbage, not a valid CBOR sequence
        )
        paperDao.papers[paper.rootHash] = paper
        val result = resolver.resolve("tagdrop://@${paper.rootHash}/index")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, "index"), result)
    }

    @Test fun slugNotInManifestIsFileNotFound() = runBlocking {
        val paper = storePaper()
        val result = resolver.resolve("tagdrop://@${paper.rootHash}/missing.html")
        assertEquals(TagDropLinkResolver.Resolution.FileNotFound(paper, "missing.html"), result)
    }

    @Test fun listedFileNotYetCachedIsFileNotCached() = runBlocking {
        val fileId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = TagDropPayload.FileEntry(slug = "doc.html", mimeType = "text/html", fileId = fileId)
        val paper = storePaper(files = listOf(file))
        val result = resolver.resolve("tagdrop://@${paper.rootHash}/doc.html")
        assertEquals(TagDropLinkResolver.Resolution.FileNotCached(paper, file), result)
    }

    @Test fun cachedFileIsFileFound() = runBlocking {
        val fileId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val file = TagDropPayload.FileEntry(slug = "doc.html", mimeType = "text/html", fileId = fileId)
        val paper = storePaper(files = listOf(file))
        val cache = storeCache(fileId, "text/html", "<p>hi</p>".toByteArray())
        val result = resolver.resolve("tagdrop://@${paper.rootHash}/doc.html")
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

    // ── Domain name resolution (SPEC §7 "Domains") ───────────────────────────

    @Test fun domainFieldResolvesToClaimingPaper() = runBlocking {
        val paper = storePaper(label = "Hello World Cafe", domain = "helloworld")
        val result = resolver.resolve("tagdrop://helloworld")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), result)
    }

    @Test fun domainMatchIsCaseInsensitive() = runBlocking {
        val paper = storePaper(label = "Hello World Cafe", domain = "helloworld")
        val result = resolver.resolve("tagdrop://HelloWorld")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), result)
    }

    @Test fun slugFallsBackAsDomainWhenDomainFieldAbsent() = runBlocking {
        val paper = storePaper(label = "Old Style Paper", slug = "oldstyle")
        val result = resolver.resolve("tagdrop://oldstyle")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(paper, null), result)
    }

    @Test fun unclaimedDomainNameIsDomainNotFound() = runBlocking {
        storePaper(label = "Unrelated Paper", domain = "somewhere")
        val result = resolver.resolve("tagdrop://nowhere/index")
        assertEquals(TagDropLinkResolver.Resolution.DomainNotFound("nowhere", "index"), result)
    }

    // ── @ marker: syntax alone decides hash vs. domain (issue #51) ───────────

    @Test fun bareHashShapedDomainNeverFallsBackToRootHashLookup() = runBlocking {
        // No `@` means the host is only ever looked up as a domain/slug claim -- never
        // attempted as a root hash, even though it's hex-shaped and a real paper exists
        // under that root hash. (SPEC §7 "Domain and pinned links".)
        val real = storePaper(label = "Real Paper")
        val result = resolver.resolve("tagdrop://${real.rootHash}")
        assertEquals(TagDropLinkResolver.Resolution.DomainNotFound(real.rootHash, null), result)
    }

    @Test fun pinnedHashLinkIsNeverShadowedByMatchingDomainClaim() = runBlocking {
        // A second paper claims the first paper's root hash as its own domain name. With the
        // `@` marker present, only the hash is ever looked up, so the impostor's domain claim
        // can't shadow the real paper -- the vulnerability this grammar exists to close.
        val real = storePaper(label = "Real Paper")
        storePaper(label = "Impostor", domain = real.rootHash)
        val result = resolver.resolve("tagdrop://@${real.rootHash}")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(real, null), result)
    }

    @Test fun domainShapedLikeHashStillResolvesAsDomainWhenClaimed() = runBlocking {
        // Flip side: a paper whose domain field happens to be hex-shaped still resolves
        // through the domain path when referenced without `@`.
        val impostor = storePaper(label = "Impostor", domain = "0011223344556677")
        val result = resolver.resolve("tagdrop://0011223344556677")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(impostor, null), result)
    }

    @Test fun combinedFormResolvesByHashIgnoringLabel() = runBlocking {
        // `<domain>@<rootHash-hex>/<slug>` -- the domain half is a decorative label, never
        // validated against the hash, so an unrelated/wrong label doesn't block resolution.
        val real = storePaper(label = "Real Paper")
        val result = resolver.resolve("tagdrop://totally-unrelated-label@${real.rootHash}")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(real, null), result)
    }

    @Test fun multipleAtSignsInHostFailsSafelyAsInvalid() = runBlocking {
        // Only the first '@' is treated as the marker; everything after it (including any
        // further '@') must still be hex-shaped. A malformed host with a stray second '@'
        // fails closed as Invalid rather than partially matching.
        val real = storePaper(label = "Real Paper")
        val result = resolver.resolve("tagdrop://label@stray@${real.rootHash}")
        assertEquals(TagDropLinkResolver.Resolution.Invalid, result)
    }

    @Test fun multipleDomainClaimsPickClosestByDevicePosition() = runBlocking {
        val near = storePaper(label = "Near Cafe", domain = "cafe", lat = 10.0, lng = 10.0, scannedAt = 1L)
        storePaper(label = "Far Cafe", domain = "cafe", lat = 50.0, lng = 50.0, scannedAt = 2L)
        val result = resolver.resolve("tagdrop://cafe", deviceLat = 10.1, deviceLng = 10.1)
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(near, null), result)
    }

    @Test fun multipleDomainClaimsFallBackToMostRecentWithoutDevicePosition() = runBlocking {
        storePaper(label = "Older Cafe", domain = "cafe", scannedAt = 1L)
        val newer = storePaper(label = "Newer Cafe", domain = "cafe", scannedAt = 2L)
        val result = resolver.resolve("tagdrop://cafe")
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(newer, null), result)
    }

    @Test fun multipleDomainClaimsFallBackToMostRecentWhenNoCandidateHasLocation() = runBlocking {
        storePaper(label = "Older Cafe", domain = "cafe", scannedAt = 1L)
        val newer = storePaper(label = "Newer Cafe", domain = "cafe", scannedAt = 2L)
        // Device position is known, but no candidate declares a location -- falls back to recency.
        val result = resolver.resolve("tagdrop://cafe", deviceLat = 10.0, deviceLng = 10.0)
        assertEquals(TagDropLinkResolver.Resolution.PaperFound(newer, null), result)
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
