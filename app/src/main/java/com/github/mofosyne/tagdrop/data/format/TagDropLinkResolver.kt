package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/**
 * Resolves TagDrop navigation links of the form:
 *   tagdrop://<rootHash-base45>/<slug>
 *
 * The root hash encodes which physical paper to look up; the slug selects a file
 * within that paper's directory. Both are resolved from the local Room database,
 * so no network is needed — the offline TagDropNet.
 *
 * Encoding URIs (tagdrop://v1/...) are passed through as EncodingUri so callers
 * know not to treat them as navigation.
 */
class TagDropLinkResolver(private val db: AppDatabase) {

    sealed class Resolution {
        /** Not a tagdrop:// URI at all. */
        object NotTagDrop  : Resolution()
        /** A QR encoding URI (tagdrop://v1/...) — not a navigation link. */
        object EncodingUri : Resolution()
        /** Could not parse the root hash. */
        object Invalid     : Resolution()
        /** Root hash not in the local DB — paper hasn't been scanned yet. */
        data class PaperNotFound (val rootHashHex: String, val slug: String?)              : Resolution()
        /** Paper found but no specific file was requested. */
        data class PaperFound    (val paper: ScannedPaper, val slug: String?)              : Resolution()
        /** Paper found but it has no file with this slug. */
        data class FileNotFound  (val paper: ScannedPaper, val slug: String)               : Resolution()
        /** File listed in directory but the file QR hasn't been scanned yet. */
        data class FileNotCached (val paper: ScannedPaper, val file: TagDropPayload.FileEntry) : Resolution()
        /** File found in the local cache — ready to display. */
        data class FileFound     (val cache: FoundCache)                                   : Resolution()
    }

    suspend fun resolve(uri: String): Resolution {
        if (!uri.startsWith("tagdrop://")) return Resolution.NotTagDrop

        val rest = uri.removePrefix("tagdrop://")
        // 'v' is lowercase — never appears in Base45 — so this is unambiguous.
        if (rest.startsWith("v1/")) return Resolution.EncodingUri

        val slash       = rest.indexOf('/')
        val rootHashB45 = if (slash < 0) rest else rest.substring(0, slash)
        val slug        = if (slash < 0 || slash == rest.lastIndex) null
                          else rest.substring(slash + 1).ifEmpty { null }

        val rootHashHex = runCatching { Base45.decode(rootHashB45).toHex() }.getOrElse {
            return Resolution.Invalid
        }

        val paper = db.paperDao().getByRootHash(rootHashHex)
            ?: return Resolution.PaperNotFound(rootHashHex, slug)

        if (slug == null) return Resolution.PaperFound(paper, null)

        val manifest = TagDropCodec.decodePaperManifestCbor(paper.cborBytes)
            ?: return Resolution.PaperFound(paper, slug)   // can't decode, show paper info

        val file = manifest.files.find { it.slug == slug }
            ?: return Resolution.FileNotFound(paper, slug)

        val cache = db.cacheDao().getById(file.fileId.toHex())
            ?: return Resolution.FileNotCached(paper, file)

        return Resolution.FileFound(cache)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
