package com.github.mofosyne.tagdrop.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.github.mofosyne.tagdrop.data.db.FoundCache
import java.io.File
import java.io.FileOutputStream

/**
 * Materializes cached content as real files (via [FileProvider]) so it can be opened in
 * another app or shared, and suggests a filename for "save a copy" actions.
 */
object ContentExporter {

    /** MIME types [MimeTypeMap] doesn't know an extension for. */
    private val FALLBACK_EXTENSIONS = mapOf(
        "text/html" to "html",
        "text/plain" to "txt",
        "application/json" to "json",
        "image/svg+xml" to "svg",
        "audio/midi" to "mid",
        "audio/x-midi" to "mid",
        "application/epub+zip" to "epub",
    )

    /** Writes [cache]'s content into the app's cache dir and returns a shareable content:// URI. */
    private fun exportToCacheFile(context: Context, cache: FoundCache): Uri? {
        val bytes = cache.contentBytes ?: return null
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, suggestFilename(cache))
        FileOutputStream(file).use { it.write(bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** ACTION_VIEW intent to open [cache]'s content in another app, or null if it has no content. */
    fun openIntent(context: Context, cache: FoundCache): Intent? {
        val uri = exportToCacheFile(context, cache) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, cache.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** ACTION_SEND intent to share [cache]'s content, or null if it has no content. */
    fun shareIntent(context: Context, cache: FoundCache): Intent? {
        val uri = exportToCacheFile(context, cache) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = cache.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Suggested filename for [cache]: its declared filename or hint, with an extension guessed from its MIME type. */
    fun suggestFilename(cache: FoundCache): String {
        val base = cache.filename?.let(::sanitize)?.takeIf { it.isNotBlank() }
            ?: cache.hint?.let(::sanitize)?.takeIf { it.isNotBlank() }
            ?: cache.cacheId.take(12)
        if (hasExtension(base)) return base
        val ext = extensionFor(cache.mimeType) ?: return base
        return "$base.$ext"
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length - 1
    }

    private fun extensionFor(mimeType: String): String? =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: FALLBACK_EXTENSIONS[mimeType]
}
