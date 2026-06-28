package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.db.isThumbnailEligible
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropLinkResolver

/** One row in the "History" tab — a single scan event, newest first. */
sealed class HistoryItem {
    abstract val key: String
    abstract val timestamp: Long

    /** Text this row should be matched against for the search box, hashtags included as "#tag". */
    abstract val searchHaystack: String

    /** Distinct tags on this row, for the quick-filter chip row (without the "#" prefix). */
    abstract val tags: List<String>

    /** True if [query] is blank, or found case-insensitively in [searchHaystack] (so typing "#trail" filters by tag). */
    fun matches(query: String): Boolean = query.isBlank() || searchHaystack.contains(query.trim(), ignoreCase = true)

    data class CacheScan(val cache: FoundCache) : HistoryItem() {
        override val key get() = "cache:${cache.cacheId}"
        override val timestamp get() = cache.discoveredAt
        override val searchHaystack get() = listOfNotNull(
            cache.hint, cache.filename, cache.mimeType, cache.collectionLabel, cache.collectionTag?.let { "#$it" }
        ).joinToString(" ")
        override val tags get() = listOfNotNull(cache.collectionTag)
    }

    data class PaperScan(
        val paper: ScannedPaper,
        /** A cached image file to show as this paper's row thumbnail, in place of its emoji icon — same pick as [CollectionItem.Paper.thumbnailCache]. */
        val thumbnailCache: FoundCache? = null
    ) : HistoryItem() {
        override val key get() = "paper:${paper.rootHash}"
        override val timestamp get() = paper.scannedAt
        override val searchHaystack get() = listOfNotNull(
            paper.label, paper.set, paper.slug, paper.collectionLabel, paper.collectionTag?.let { "#$it" }
        ).joinToString(" ")
        override val tags get() = listOfNotNull(paper.collectionTag)
    }

    companion object {
        /** Merges all cached items and scanned paper manifests into one chronological feed. */
        fun build(papers: List<ScannedPaper>, caches: List<FoundCache>): List<HistoryItem> {
            val cachesById = caches.associateBy { it.cacheId }
            val paperScans = papers.map { paper ->
                val files = TagDropCodec.decodePaperStream(paper.cborBytes)?.files.orEmpty()
                var homeCache: FoundCache? = null
                var faviconCache: FoundCache? = null
                var firstImageCache: FoundCache? = null
                for (file in files) {
                    val cache = cachesById[file.fileId.toHex()] ?: continue
                    if (file.slug in TagDropLinkResolver.HOME_SLUGS) homeCache = cache
                    if (cache.isThumbnailEligible) {
                        if (file.slug in TagDropLinkResolver.FAVICON_SLUGS) faviconCache = cache
                        if (firstImageCache == null) firstImageCache = cache
                    }
                }
                val thumbnailCache = faviconCache ?: homeCache?.takeIf { it.isThumbnailEligible } ?: firstImageCache
                PaperScan(paper, thumbnailCache)
            }
            return (caches.map { CacheScan(it) } + paperScans).sortedByDescending { it.timestamp }
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
