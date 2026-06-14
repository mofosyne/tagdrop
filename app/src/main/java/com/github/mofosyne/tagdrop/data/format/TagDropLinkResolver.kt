package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/**
 * Resolves TagDrop navigation links. Two URL forms are recognised:
 *
 *   tagdrop://<rootHash-base45>/<slug>
 *     The portable form carried in QR-encoded content. rootHash is Base45-encoded.
 *
 *   https://paper.tagdrop.invalid/<rootHash-hex>/<slug>
 *     A synthetic same-paper form (rootHash as plain hex). Never appears in a QR
 *     code or in authored content -- pages are loaded with this as their base URL
 *     (see ViewDataUriActivity), so ordinary relative links (./foo, ../bar, baz.html)
 *     resolve to it via standard URL resolution. `.invalid` is an IANA-reserved TLD
 *     (RFC 2606) that never resolves over the network.
 *
 * In both forms, the root hash identifies which physical paper to look up; the slug
 * selects a file within that paper's directory. Both are resolved from the local
 * Room database, so no network is needed -- the offline TagDropNet.
 *
 * Encoding URIs (tagdrop:<base45-cbor-sequence>, no "//") are passed through as
 * EncodingUri so callers know not to treat them as navigation.
 */
class TagDropLinkResolver(private val db: AppDatabase) {

    sealed class Resolution {
        /** Not a recognised navigation link at all. */
        object NotTagDrop  : Resolution()
        /** A QR encoding URI (tagdrop:<base45-cbor-sequence>) — not a navigation link. */
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
        data class FileFound     (val cache: FoundCache, val paper: ScannedPaper, val slug: String) : Resolution()
    }

    /** Identifies where a cached file sits within a scanned paper's directory. */
    data class PaperContext(val rootHashHex: String, val slug: String)

    suspend fun resolve(uri: String): Resolution {
        val ref = when {
            uri.startsWith(SCHEME) -> {
                val rest = uri.removePrefix(SCHEME)
                val (rootHashB45, slug) = splitFirstSlash(rest)
                val rootHashHex = runCatching { Base45.decode(rootHashB45).toHex() }.getOrElse {
                    return Resolution.Invalid
                }
                Ref(rootHashHex, slug)
            }
            // tagdrop:<base45> with no "//" — an encoding URI, not a navigation link (SPEC §2).
            uri.startsWith(ENCODING_PREFIX) -> return Resolution.EncodingUri
            uri.startsWith(SYNTHETIC_BASE) -> {
                val (rootHashHex, slug) = splitFirstSlash(uri.removePrefix(SYNTHETIC_BASE))
                if (!HEX_ROOT_HASH.matches(rootHashHex)) return Resolution.Invalid
                Ref(rootHashHex, slug)
            }
            else -> return Resolution.NotTagDrop
        }

        val paper = db.paperDao().getByRootHash(ref.rootHashHex)
            ?: return Resolution.PaperNotFound(ref.rootHashHex, ref.slug)

        val slug = ref.slug ?: return Resolution.PaperFound(paper, null)

        val manifest = TagDropCodec.decodePaperManifestCbor(paper.cborBytes)
            ?: return Resolution.PaperFound(paper, slug)   // can't decode, show paper info

        val file = manifest.files.find { it.slug == slug }
            ?: return Resolution.FileNotFound(paper, slug)

        val cache = db.cacheDao().getById(file.fileId.toHex())
            ?: return Resolution.FileNotCached(paper, file)

        return Resolution.FileFound(cache, paper, slug)
    }

    /**
     * Finds where a cached file (by content ID) sits within any scanned paper's
     * directory. Used to give a top-level page a synthetic base URL so its relative
     * links resolve to sibling files in the same paper.
     */
    suspend fun findPaperContext(cacheId: String): PaperContext? {
        for (paper in db.paperDao().getAllPapers()) {
            val manifest = TagDropCodec.decodePaperManifestCbor(paper.cborBytes) ?: continue
            val file = manifest.files.find { it.fileId.toHex() == cacheId } ?: continue
            return PaperContext(paper.rootHash, file.slug)
        }
        return null
    }

    /**
     * Looks up the paper-wide stylesheet sibling for the paper identified by [rootHashHex]
     * (SPEC §7 convention: a file with slug "style.css" and mimeType "text/css"), returning
     * its cached content as text, or null if the paper, file, or its content isn't available.
     */
    suspend fun findStylesheet(rootHashHex: String): String? {
        val paper = db.paperDao().getByRootHash(rootHashHex) ?: return null
        val manifest = TagDropCodec.decodePaperManifestCbor(paper.cborBytes) ?: return null
        val cssFile = manifest.files.find { it.slug == STYLESHEET_SLUG && it.mimeType == "text/css" } ?: return null
        val cache = db.cacheDao().getById(cssFile.fileId.toHex()) ?: return null
        return cache.contentBytes?.let { String(it, Charsets.UTF_8) }
    }

    private data class Ref(val rootHashHex: String, val slug: String?)

    /** Splits "<head>/<tail>" into (head, tail); tail is null if absent or empty. */
    private fun splitFirstSlash(s: String): Pair<String, String?> {
        val slash = s.indexOf('/')
        val head  = if (slash < 0) s else s.substring(0, slash)
        val tail  = if (slash < 0 || slash == s.lastIndex) null else s.substring(slash + 1).ifEmpty { null }
        return head to tail
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val SCHEME = "tagdrop://"
        private const val ENCODING_PREFIX = "tagdrop:"

        /** Synthetic host used as a same-paper base URL — see class doc. */
        const val SYNTHETIC_HOST = "paper.tagdrop.invalid"
        const val SYNTHETIC_BASE = "https://$SYNTHETIC_HOST/"

        /** Slug convention (SPEC §7) for a paper-wide CSS stylesheet, inlined into rendered Markdown. */
        const val STYLESHEET_SLUG = "style.css"

        private val HEX_ROOT_HASH = Regex("[0-9a-f]{16}")
    }
}
