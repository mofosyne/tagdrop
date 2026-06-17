package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper

/**
 * Resolves TagDrop navigation links. Two URL forms are recognised:
 *
 *   tagdrop://<rootHash-hex>/<slug>
 *     The portable form carried in QR-encoded content. rootHash is plain lowercase
 *     hex here -- NOT Base41, unlike the QR-payload encoding -- because Base41's
 *     alphabet includes ':', which breaks URL authority parsing if it lands in the
 *     host (SPEC.md §2).
 *
 *   https://<rootHash-hex>.paper.tagdrop.invalid/<slug>
 *     A synthetic same-paper form. rootHash is a subdomain *label*, not a path
 *     segment, so it survives standard URL resolution even for root-relative links
 *     (e.g. "/foo.html"), which replace a base URL's entire path but never its host.
 *     Never appears in a QR code or in authored content -- pages are loaded with
 *     this as their base URL (see ViewDataUriActivity), so both ordinary relative
 *     links (./foo, ../bar, baz.html) and root-relative links (/foo) resolve to it
 *     via standard URL resolution. `.invalid` is an IANA-reserved TLD (RFC 2606)
 *     that never resolves over the network.
 *
 * In both forms, the root hash identifies which physical paper to look up; the slug
 * selects a file within that paper's directory. Both are resolved from the local
 * Room database, so no network is needed -- the offline TagDropNet.
 *
 * The root hash is lowercased before lookup in either form, tolerating manual
 * transcription (e.g. a `tagdrop://` link copied or retyped by hand).
 *
 * Encoding URIs (tagdrop:<base41-cbor-sequence>, no "//") are passed through as
 * EncodingUri so callers know not to treat them as navigation.
 */
class TagDropLinkResolver(private val db: AppDatabase) {

    sealed class Resolution {
        /** Not a recognised navigation link at all. */
        object NotTagDrop  : Resolution()
        /** A QR encoding URI (tagdrop:<base41-cbor-sequence>) — not a navigation link. */
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
                val (rootHashHex, slug) = splitFirstSlash(rest)
                val hex = rootHashHex.lowercase()
                if (!HEX_ROOT_HASH.matches(hex)) return Resolution.Invalid
                Ref(hex, slug)
            }
            // tagdrop:<base41> with no "//" — an encoding URI, not a navigation link (SPEC §2).
            uri.startsWith(ENCODING_PREFIX) -> return Resolution.EncodingUri
            uri.startsWith(HTTPS_PREFIX) -> {
                val (host, slug) = splitFirstSlash(uri.removePrefix(HTTPS_PREFIX))
                if (!isSyntheticHost(host)) return Resolution.NotTagDrop
                val hex = host.removeSuffix(".$SYNTHETIC_HOST_SUFFIX").lowercase()
                if (!HEX_ROOT_HASH.matches(hex)) return Resolution.Invalid
                Ref(hex, slug)
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
        private const val HTTPS_PREFIX = "https://"

        /**
         * Synthetic host suffix for the same-paper base URL — see class doc. The full host
         * is "<rootHash-hex>.$SYNTHETIC_HOST_SUFFIX" (root hash as a subdomain label).
         */
        const val SYNTHETIC_HOST_SUFFIX = "paper.tagdrop.invalid"

        /** True if [host] is a same-paper synthetic host ("<rootHash-hex>.$SYNTHETIC_HOST_SUFFIX"). */
        fun isSyntheticHost(host: String?): Boolean = host != null && host.endsWith(".$SYNTHETIC_HOST_SUFFIX")

        /** Builds the same-paper base URL for [rootHashHex]/[slug] — see ViewDataUriActivity.loadHtml. */
        fun syntheticBaseUrl(rootHashHex: String, slug: String): String =
            "$HTTPS_PREFIX$rootHashHex.$SYNTHETIC_HOST_SUFFIX/$slug"

        /** Slug convention (SPEC §7) for a paper-wide CSS stylesheet, inlined into rendered Markdown. */
        const val STYLESHEET_SLUG = "style.css"

        /**
         * Slug convention: a paper file with one of these slugs is its homepage/landing page,
         * highlighted as the primary "Open" action (mirrors [STYLESHEET_SLUG] above — pure
         * naming convention, no SPEC.md change needed).
         */
        val HOME_SLUGS = setOf("index", "index.html", "index.md")

        /**
         * Matches a root hash as lowercase hex pairs, with no fixed length pinned to today's
         * 8-byte truncation. root_hash's actual byte length is defined by the versioned CBOR
         * payload (SPEC §2/§3, key 2) -- if a future version changes it, this still matches,
         * and an unknown hash is rejected by the DB lookup (PaperNotFound) rather than here.
         */
        private val HEX_ROOT_HASH = Regex("([0-9a-f]{2})+")
    }
}
