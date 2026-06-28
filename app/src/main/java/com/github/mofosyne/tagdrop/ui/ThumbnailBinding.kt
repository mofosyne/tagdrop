package com.github.mofosyne.tagdrop.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.util.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows [thumbnailCache]'s decoded image in this `ImageView`, falling back to [icon] (or, if
 * that's unset, [fallbackIcon]) text in [textIcon] when there's no thumbnail-eligible cache or it
 * fails to decode. The two views share one icon slot (see `item_collection.xml`/`item_page.xml`),
 * so exactly one is ever visible; [fallbackIcon] being non-null means that slot is never blank.
 *
 * Decoding is async; [tag] is used as a request token so a slower decode that completes after
 * this view has been rebound to a different row (RecyclerView recycling during fast scrolling)
 * doesn't clobber the now-current row's icon.
 */
fun ImageView.bindThumbnailOrIcon(
    textIcon: TextView, thumbnailCache: FoundCache?, icon: String?, fallbackIcon: String, scope: CoroutineScope
) {
    fun showIcon() {
        visibility = View.GONE
        setImageDrawable(null)
        textIcon.text = icon ?: fallbackIcon
        textIcon.visibility = View.VISIBLE
    }

    if (thumbnailCache == null) {
        showIcon()
        return
    }

    textIcon.visibility = View.GONE
    visibility = View.VISIBLE
    setImageDrawable(null)
    val requestKey = thumbnailCache.cacheId
    tag = requestKey
    scope.launch {
        val bitmap = ThumbnailLoader.decode(thumbnailCache)
        if (tag != requestKey) return@launch
        if (bitmap != null) setImageBitmap(bitmap) else showIcon()
    }
}
