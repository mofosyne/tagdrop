package com.github.mofosyne.tagdrop.ui

import com.github.mofosyne.tagdrop.data.db.FoundCache

/** One row in the collection detail ("map") screen. */
sealed class PageItem {
    abstract val key: String

    /** A file listed in a paper's directory — may or may not be cached yet. */
    data class PaperFile(val slug: String, val mimeType: String, val cache: FoundCache?) : PageItem() {
        override val key get() = "file:$slug"
    }

    /** A standalone cached item (ad-hoc collection member or loose scan). */
    data class CacheEntry(val cache: FoundCache) : PageItem() {
        override val key get() = "cache:${cache.cacheId}"
    }
}
