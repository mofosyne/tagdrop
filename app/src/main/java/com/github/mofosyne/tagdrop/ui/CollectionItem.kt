package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.TagDropCodec

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

    data class Paper(val paper: ScannedPaper, val totalFiles: Int, val cachedFiles: Int) : CollectionItem() {
        override val key get() = "paper:${paper.rootHash}"
        override val timestamp get() = paper.scannedAt
    }

    data class AdHoc(val collectionId: String, val label: String?, val tags: List<String>, val icon: String?, val items: List<FoundCache>) : CollectionItem() {
        override val key get() = "adhoc:$collectionId"
        override val timestamp get() = items.maxOf { it.discoveredAt }
    }

    data class Loose(val cache: FoundCache) : CollectionItem() {
        override val key get() = "loose:${cache.cacheId}"
        override val timestamp get() = cache.discoveredAt
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
                for (file in files) {
                    val fileId = file.fileId.toHex()
                    claimedIds += fileId
                    if (cachesById.containsKey(fileId)) cachedCount++
                }
                Paper(paper, totalFiles = files.size, cachedFiles = cachedCount)
            }

            val unclaimed = caches.filterNot { claimedIds.contains(it.cacheId) }
            val (grouped, loose) = unclaimed.partition { it.collectionId != null }

            val adHocItems = grouped.groupBy { it.collectionId!! }.map { (collectionId, items) ->
                val withLabel = items.firstOrNull { it.collectionLabel != null }
                // Each page may be focused on its own theme, so accumulate every
                // distinct tag seen across the collection's pages as they're discovered.
                val tags = items.mapNotNull { it.collectionTag }.distinct()
                val icon = items.firstOrNull { it.icon != null }?.icon
                AdHoc(collectionId, withLabel?.collectionLabel, tags, icon, items)
            }

            val looseItems = loose.map { Loose(it) }

            return (paperItems + adHocItems + looseItems).sortedByDescending { it.timestamp }
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
