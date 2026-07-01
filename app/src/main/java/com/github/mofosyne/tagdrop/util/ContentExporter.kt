package com.github.mofosyne.tagdrop.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.isOpenable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Materializes cached content as real files (via [FileProvider]) so it can be opened in
 * another app or shared, and suggests a filename for "save a copy" actions.
 */
object ContentExporter {

    /** MIME types [MimeTypeMap] doesn't know an extension for. */
    private val FALLBACK_EXTENSIONS = mapOf(
        "text/html" to "html",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/css" to "css",
        "application/json" to "json",
        "image/svg+xml" to "svg",
        "audio/midi" to "mid",
        "audio/x-midi" to "mid",
        "application/epub+zip" to "epub",
        "text/vcard" to "vcf",
        "text/calendar" to "ics",
    )

    /** Writes [cache]'s content into the app's cache dir and returns a shareable content:// URI. */
    fun writeTempFile(context: Context, cache: FoundCache): Uri? = exportToCacheFile(context, cache)

    private fun exportToCacheFile(context: Context, cache: FoundCache): Uri? {
        if (!cache.isOpenable) return null
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

    /** Bundles every cached item with content into a single zip and returns a shareable content:// URI, or null if none have content. */
    fun exportZip(context: Context, caches: List<FoundCache>): Uri? {
        val withContent = caches.filter { it.isOpenable }
        if (withContent.isEmpty()) return null
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "collection-export.zip")
        val usedNames = mutableSetOf<String>()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for (cache in withContent) {
                zip.putNextEntry(ZipEntry(uniqueEntryName(suggestFilename(cache), usedNames)))
                zip.write(cache.contentBytes)
                zip.closeEntry()
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Disambiguates duplicate filenames within a zip by inserting "-2", "-3", etc. before the extension. */
    private fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (!used.add("$base-$i$ext")) i++
        return "$base-$i$ext"
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
