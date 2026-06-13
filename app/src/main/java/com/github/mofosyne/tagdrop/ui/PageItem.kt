package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import com.github.mofosyne.tagdrop.data.format.TagDropPayload

/** One row in the collection detail ("map") screen. */
sealed class PageItem {
    abstract val key: String

    /** Non-interactive section label, e.g. "Related drops". */
    data class SectionHeader(val title: String) : PageItem() {
        override val key get() = "header:$title"
    }

    /** An interactive row with open/map/delete actions. */
    sealed class Row : PageItem()

    /** A file listed in a paper's directory — may or may not be cached yet. */
    data class PaperFile(val slug: String, val mimeType: String, val cache: FoundCache?) : Row() {
        override val key get() = "file:$slug"
    }

    /** A standalone cached item (ad-hoc collection member or loose scan). */
    data class CacheEntry(val cache: FoundCache) : Row() {
        override val key get() = "cache:${cache.cacheId}"
    }

    /** A hint pointing to a related paper at another location — scanned already, or not. */
    data class RelatedHint(val related: TagDropPayload.RelatedPaper, val scannedPaper: ScannedPaper?) : Row() {
        override val key get() = "related:${related.hint}:${related.slug}"
    }
}
