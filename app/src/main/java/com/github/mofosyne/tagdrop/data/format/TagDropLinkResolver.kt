package com.github.mofosyne.tagdrop.data.format

import com.github.mofosyne.tagdrop.data.db.AppDatabase
import com.github.mofosyne.tagdrop.data.db.FoundCache
import com.github.mofosyne.tagdrop.data.db.ScannedPaper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resolves TagDrop navigation links. Two URL forms are recognised:
 *
 *   tagdrop://<rootHash-hex-or-domain>/<slug>
 *     The portable form carried in QR-encoded content. The host is either plain
 *     lowercase hex -- NOT Base41, unlike the QR-payload encoding, because Base41's
 *     alphabet includes ':', which breaks URL authority parsing if it lands in the
 *     host (SPEC.md §2) -- or a human-readable domain name (SPEC §7 "Domains").
 *     An exact root-hash lookup is always tried first; only on a miss does
 *     resolution fall back to a domain lookup (`ScannedPaper.domain`, falling back
 *     to `ScannedPaper.slug`), so a real root hash can never be shadowed by a
 *     same-looking domain name.
 *
 *   https://<rootHash-hex>.paper.tagdrop.invalid/<slug>
 *     A synthetic same-paper form. rootHash is a subdomain *label*, not a path
 *     segment, so it survives standard URL resolution even for root-relative links
 *     (e.g. "/foo.html"), which replace a base URL's entire path but never its host.
 *     Never appears in a QR code or in authored content -- pages are loaded with
 *     this as their base URL (see ViewDataUriActivity), so both ordinary relative
 *     links (./foo, ../bar, baz.html) and root-relative links (/foo) resolve to it
 *     via standard URL resolution. `.invalid` is an IANA-reserved TLD (RFC 2606)
 *     that never resolves over the network. This form is always a real root hash --
 *     it's derived from an already-scanned paper, never user-typed -- so it has no
 *     domain-name fallback.
 *
 * In both forms, the root hash identifies which physical paper to look up; the slug
 * selects a file within that paper's directory. Both are resolved from the local
 * Room database, so no network is needed -- the offline TagDropNet.
 *
 * The root hash is lowercased before lookup in either form, tolerating manual
 * transcription (e.g. a `tagdrop://` link copied or retyped by hand). Domain names
 * are matched case-insensitively for the same reason.
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
        /** Host isn't a known root hash, and no scanned paper claims it as a domain/slug either (SPEC §7). */
        data class DomainNotFound(val domain: String, val slug: String?)                    : Resolution()
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

    /**
     * Resolves a navigation link. [deviceLat]/[deviceLng] are the device's current position
     * (if known) — used only to pick among several papers that claim the same domain name
     * (SPEC §7 "Picking the closest match"); omitting them just falls back to recency.
     */
    suspend fun resolve(uri: String, deviceLat: Double? = null, deviceLng: Double? = null): Resolution {
        return when {
            uri.startsWith(SCHEME) -> resolveSchemeLink(uri.removePrefix(SCHEME), deviceLat, deviceLng)
            // tagdrop:<base41> with no "//" — an encoding URI, not a navigation link (SPEC §2).
            uri.startsWith(ENCODING_PREFIX) -> Resolution.EncodingUri
            uri.startsWith(HTTPS_PREFIX) -> resolveSyntheticHostLink(uri.removePrefix(HTTPS_PREFIX))
            else -> Resolution.NotTagDrop
        }
    }

    /**
     * `tagdrop://<host>/<slug>` where `host` may be a root hash or a domain name. Tries the
     * exact root-hash lookup first (cheap, primary-keyed); only on a miss does it fall back to
     * a domain lookup, so a real root hash can never be shadowed by a same-looking domain name
     * (SPEC §7 "Resolving a domain link"). A hex-shaped host that matches neither stays
     * PaperNotFound (same as before this field existed); a non-hex-shaped host that matches
     * neither is DomainNotFound.
     */
    private suspend fun resolveSchemeLink(rest: String, deviceLat: Double?, deviceLng: Double?): Resolution {
        val (host, slug) = splitFirstSlash(rest)
        val hex = host.lowercase()
        val hexShaped = HEX_ROOT_HASH.matches(hex)
        if (hexShaped) {
            db.paperDao().getByRootHash(hex)?.let { return resolveWithinPaper(it, slug) }
        }
        pickClosestDomainMatch(host, deviceLat, deviceLng)?.let { return resolveWithinPaper(it, slug) }
        return if (hexShaped) Resolution.PaperNotFound(hex, slug) else Resolution.DomainNotFound(host, slug)
    }

    /** `https://<rootHash-hex>.paper.tagdrop.invalid/<slug>` — always a real root hash, never a domain name. */
    private suspend fun resolveSyntheticHostLink(rest: String): Resolution {
        val (host, slug) = splitFirstSlash(rest)
        if (!isSyntheticHost(host)) return Resolution.NotTagDrop
        val hex = host.removeSuffix(".$SYNTHETIC_HOST_SUFFIX").lowercase()
        if (!HEX_ROOT_HASH.matches(hex)) return Resolution.Invalid
        val paper = db.paperDao().getByRootHash(hex) ?: return Resolution.PaperNotFound(hex, slug)
        return resolveWithinPaper(paper, slug)
    }

    private suspend fun resolveWithinPaper(paper: ScannedPaper, slug: String?): Resolution {
        val s = slug ?: return Resolution.PaperFound(paper, null)

        val manifest = TagDropCodec.decodePaperStream(paper.cborBytes)
            ?: return Resolution.PaperFound(paper, s)   // can't decode, show paper info

        val file = manifest.files.find { it.slug == s }
            ?: return Resolution.FileNotFound(paper, s)

        val cache = db.cacheDao().getById(file.fileId.toHex())
            ?: return Resolution.FileNotCached(paper, file)

        return Resolution.FileFound(cache, paper, s)
    }

    /**
     * Finds scanned papers claiming [domainName] — via `domain`, falling back to `slug` when
     * `domain` is absent (SPEC §7) — matched case-insensitively, and picks the closest one when
     * more than one matches (domains are unilateral/uncoordinated, so collisions are expected,
     * not an error): nearest by device position when both a position and at least one
     * candidate's location are known, otherwise the most recently scanned candidate.
     */
    private suspend fun pickClosestDomainMatch(domainName: String, deviceLat: Double?, deviceLng: Double?): ScannedPaper? {
        val candidates = db.paperDao().getAllPapers().filter {
            (it.domain ?: it.slug)?.equals(domainName, ignoreCase = true) == true
        }
        if (candidates.isEmpty()) return null
        if (deviceLat != null && deviceLng != null) {
            val located = candidates.filter { it.lat != null && it.lng != null }
            if (located.isNotEmpty()) {
                return located.minByOrNull { haversineMeters(deviceLat, deviceLng, it.lat!!, it.lng!!) }
            }
        }
        return candidates.maxByOrNull { it.scannedAt }
    }

    /** Great-circle distance in meters between two lat/lng points — plain doubles, no Android/osmdroid dependency. */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return earthRadiusM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Finds where a cached file (by content ID) sits within any scanned paper's
     * directory. Used to give a top-level page a synthetic base URL so its relative
     * links resolve to sibling files in the same paper.
     */
    suspend fun findPaperContext(cacheId: String): PaperContext? {
        for (paper in db.paperDao().getAllPapers()) {
            val manifest = TagDropCodec.decodePaperStream(paper.cborBytes) ?: continue
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
        val manifest = TagDropCodec.decodePaperStream(paper.cborBytes) ?: return null
        val cssFile = manifest.files.find { it.slug == STYLESHEET_SLUG && it.mimeType == "text/css" } ?: return null
        val cache = db.cacheDao().getById(cssFile.fileId.toHex()) ?: return null
        return cache.contentBytes?.let { String(it, Charsets.UTF_8) }
    }

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
