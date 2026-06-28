package com.github.mofosyne.tagdrop.util

/** Generic placeholder shown for a multi-file paper/collection row with no author-supplied icon. */
const val DEFAULT_COLLECTION_ICON = "📁"

/** Placeholder emoji for [mime], shown when there's no author-supplied icon and no decodable thumbnail. */
fun iconForMimeType(mime: String): String = when {
    mime.startsWith("image/") -> "🖼"
    mime.startsWith("audio/") -> "🎵"
    mime.startsWith("video/") -> "🎬"
    mime == "application/pdf" -> "📕"
    mime == "text/calendar" -> "📅"
    mime == "text/vcard" -> "👤"
    mime.startsWith("text/") -> "📄"
    else -> "📦"
}
