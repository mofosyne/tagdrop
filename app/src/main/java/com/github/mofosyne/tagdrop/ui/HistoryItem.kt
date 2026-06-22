package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/** One row in the "History" tab — a single scan event, newest first. */
sealed class HistoryItem {
    abstract val key: String
    abstract val timestamp: Long

    /** Text this row should be matched against for the search box, hashtags included as "#tag". */
    abstract val searchHaystack: String

    /** True if [query] is blank, or found case-insensitively in [searchHaystack] (so typing "#trail" filters by tag). */
    fun matches(query: String): Boolean = query.isBlank() || searchHaystack.contains(query.trim(), ignoreCase = true)

    data class CacheScan(val cache: FoundCache) : HistoryItem() {
        override val key get() = "cache:${cache.cacheId}"
        override val timestamp get() = cache.discoveredAt
        override val searchHaystack get() = listOfNotNull(
            cache.hint, cache.filename, cache.mimeType, cache.collectionLabel, cache.collectionTag?.let { "#$it" }
        ).joinToString(" ")
    }

    data class PaperScan(val paper: ScannedPaper) : HistoryItem() {
        override val key get() = "paper:${paper.rootHash}"
        override val timestamp get() = paper.scannedAt
        override val searchHaystack get() = listOfNotNull(
            paper.label, paper.set, paper.slug, paper.collectionLabel, paper.collectionTag?.let { "#$it" }
        ).joinToString(" ")
    }

    companion object {
        /** Merges all cached items and scanned paper manifests into one chronological feed. */
        fun build(papers: List<ScannedPaper>, caches: List<FoundCache>): List<HistoryItem> =
            (caches.map { CacheScan(it) } + papers.map { PaperScan(it) })
                .sortedByDescending { it.timestamp }
    }
}
