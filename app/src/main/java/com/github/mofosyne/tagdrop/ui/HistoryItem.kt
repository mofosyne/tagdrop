package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/** One row in the "History" tab — a single scan event, newest first. */
sealed class HistoryItem {
    abstract val key: String
    abstract val timestamp: Long

    data class CacheScan(val cache: FoundCache) : HistoryItem() {
        override val key get() = "cache:${cache.cacheId}"
        override val timestamp get() = cache.discoveredAt
    }

    data class PaperScan(val paper: ScannedPaper) : HistoryItem() {
        override val key get() = "paper:${paper.rootHash}"
        override val timestamp get() = paper.scannedAt
    }

    companion object {
        /** Merges all cached items and scanned paper manifests into one chronological feed. */
        fun build(papers: List<ScannedPaper>, caches: List<FoundCache>): List<HistoryItem> =
            (caches.map { CacheScan(it) } + papers.map { PaperScan(it) })
                .sortedByDescending { it.timestamp }
    }
}
