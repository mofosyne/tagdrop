package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.db.isThumbnailEligible
import com.github.mofosyne.tagdrop.data.format.TagDropCodec
import com.github.mofosyne.tagdrop.data.format.TagDropLinkResolver

/**
 * One card on the home screen — a "collection" of one or more pages.
 *
 * - [Paper]: a scanned paper manifest and its file directory.
 * - [AdHoc]: cached items sharing a [collection_id], not claimed by any paper.
 * - [Loose]: a single cached item with no collection — its own one-page collection.
 */
sealed class CollectionItem {
    abstract val key: String
    abstract val timestamp: Long

    /** Text this card should be matched against for the search box, hashtags included as "#tag". */
    abstract val searchHaystack: String

    /** Distinct tags on this card, for the quick-filter chip row (without the "#" prefix). */
    abstract val tags: List<String>

    /** True if [query] is blank, or found case-insensitively in [searchHaystack] (so typing "#trail" filters by tag). */
    fun matches(query: String): Boolean = query.isBlank() || searchHaystack.contains(query.trim(), ignoreCase = true)

    data class Paper(
        val paper: ScannedPaper,
        val totalFiles: Int,
        val cachedFiles: Int,
        val homeCache: FoundCache?,
        /** A cached image file to show as this paper's row thumbnail, in place of its emoji [icon] — its de facto "favicon". */
        val thumbnailCache: FoundCache? = null
    ) : CollectionItem() {
        override val key get() = "paper:${paper.rootHash}"
        override val timestamp get() = paper.scannedAt
        override val searchHaystack get() = listOfNotNull(
            paper.label, paper.set, paper.slug, paper.domain, paper.collectionLabel, paper.collectionTag?.let { "#$it" }
        ).joinToString(" ")
        override val tags get() = listOfNotNull(paper.collectionTag)
    }

    data class AdHoc(
        val collectionId: String,
        val label: String?,
        override val tags: List<String>,
        val icon: String?,
        val items: List<FoundCache>,
        val homeCache: FoundCache?,
        /** A cached image file to show as this collection's row thumbnail, in place of its emoji [icon] — its de facto "favicon". */
        val thumbnailCache: FoundCache? = null
    ) : CollectionItem() {
        override val key get() = "adhoc:$collectionId"
        override val timestamp get() = items.maxOf { it.discoveredAt }
        override val searchHaystack get() = (
            listOfNotNull(label) + tags.map { "#$it" } + items.mapNotNull { it.hint } + items.mapNotNull { it.filename }
        ).joinToString(" ")
    }

    data class Loose(val cache: FoundCache) : CollectionItem() {
        override val key get() = "loose:${cache.cacheId}"
        override val timestamp get() = cache.discoveredAt
        override val searchHaystack get() = listOfNotNull(
            cache.hint, cache.filename, cache.mimeType, cache.collectionLabel, cache.collectionTag?.let { "#$it" }
        ).joinToString(" ")
        override val tags get() = listOfNotNull(cache.collectionTag)
    }

    /** True if this collection was authored in-app (Create Cache/Paper) rather than scanned from someone else's drop. */
    val createdByMe: Boolean get() = when (this) {
        is Paper -> paper.createdByMe
        is AdHoc -> false
        is Loose -> cache.createdByMe
    }

    companion object {
        /**
         * Builds the home-screen collection list from all scanned papers and cached items.
         * A cache is "claimed" by a paper if it's listed in that paper's file directory,
         * and is excluded from AdHoc/Loose grouping.
         */
        fun build(papers: List<ScannedPaper>, caches: List<FoundCache>): List<CollectionItem> {
            val cachesById = caches.associateBy { it.cacheId }
            val claimedIds = mutableSetOf<String>()

            val paperItems = papers.map { paper ->
                val files = TagDropCodec.decodePaperStream(paper.cborBytes)?.files.orEmpty()
                var cachedCount = 0
                var homeCache: FoundCache? = null
                var thumbnailCache: FoundCache? = null
                for (file in files) {
                    val fileId = file.fileId.toHex()
                    claimedIds += fileId
                    val cache = cachesById[fileId]
                    if (cache != null) {
                        cachedCount++
                        if (file.slug in TagDropLinkResolver.HOME_SLUGS) homeCache = cache
                        if (cache.isThumbnailEligible && (thumbnailCache == null || file.slug in TagDropLinkResolver.HOME_SLUGS)) {
                            thumbnailCache = cache
                        }
                    }
                }
                Paper(paper, totalFiles = files.size, cachedFiles = cachedCount, homeCache = homeCache, thumbnailCache = thumbnailCache)
            }

            val unclaimed = caches.filterNot { claimedIds.contains(it.cacheId) }
            val (grouped, loose) = unclaimed.partition { it.collectionId != null }

            val adHocItems = grouped.groupBy { it.collectionId!! }.map { (collectionId, items) ->
                val withLabel = items.firstOrNull { it.collectionLabel != null }
                // Each page may be focused on its own theme, so accumulate every
                // distinct tag seen across the collection's pages as they're discovered.
                val tags = items.mapNotNull { it.collectionTag }.distinct()
                val icon = items.firstOrNull { it.icon != null }?.icon
                val homeCache = items.firstOrNull { it.filename in TagDropLinkResolver.HOME_SLUGS }
                val thumbnailCache = homeCache?.takeIf { it.isThumbnailEligible }
                    ?: items.firstOrNull { it.isThumbnailEligible }
                AdHoc(collectionId, withLabel?.collectionLabel, tags, icon, items, homeCache, thumbnailCache)
            }

            val looseItems = loose.map { Loose(it) }

            return (paperItems + adHocItems + looseItems).sortedByDescending { it.timestamp }
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
