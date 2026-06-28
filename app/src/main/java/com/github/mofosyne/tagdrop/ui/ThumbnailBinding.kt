package com.github.mofosyne.tagdrop.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.util.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows [thumbnailCache]'s decoded image in this `ImageView`, falling back to [icon] text in
 * [textIcon] when there's no thumbnail-eligible cache or it fails to decode. The two views share
 * one icon slot (see `item_collection.xml`/`item_page.xml`), so exactly one is ever visible.
 *
 * Decoding is async; [tag] is used as a request token so a slower decode that completes after
 * this view has been rebound to a different row (RecyclerView recycling during fast scrolling)
 * doesn't clobber the now-current row's icon.
 */
fun ImageView.bindThumbnailOrIcon(textIcon: TextView, thumbnailCache: FoundCache?, icon: String?, scope: CoroutineScope) {
    fun showIcon() {
        visibility = View.GONE
        setImageDrawable(null)
        textIcon.text = icon
        textIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
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
