package com.github.mofosyne.tagdrop.data

/**
 * One drop location from a [DropSourceJson] registry file (SPEC §17).
 *
 * Field names match the TagDrop wire format (SPEC §3) so that a drops.json entry can be
 * generated mechanically from a scan with minimal extra input:
 * - [id]            — cache_id hex (16 chars), SHA-256(content)[0:8], matches Content's cacheId
 * - [lat]/[lng]     — WGS-84 coordinates (same naming as the wire format's keys 26/27)
 * - [hint]          — short location clue / name (wire format key 3)
 * - [description]   — longer location description, e.g. directions (wire format key 40)
 * - [status]        — "working" | "unknown" | "broken" | "removed" (default "unknown")
 * - [statusUpdated] — ISO 8601 date of last status check
 * - [dropType]      — type tag; default "tagdrop", allows future extensibility
 */
data class DropEntry(
    val id: String,
    val lat: Double,
    val lng: Double,
    val hint: String? = null,
    val description: String? = null,
    val status: String? = null,        // "working" | "unknown" | "broken" | "removed"
    val statusUpdated: String? = null,
    val dropType: String? = null       // default "tagdrop"
)

/**
 * One entry in a sources directory (SPEC §17 — sources.json schema).
 * Allows a registry to recommend other registries, or the official
 * TagDrop sources list to advertise known community registries.
 */
data class RelatedSource(
    val name: String,
    val url: String,
    val description: String? = null,
    val maintainer: String? = null
)

/** Top-level shape of a drop-source JSON registry file (SPEC §17). */
data class DropSourceJson(
    val version: Int,
    val label: String? = null,
    val drops: List<DropEntry> = emptyList(),
    val relatedSources: List<RelatedSource> = emptyList()
)

/** Top-level shape of the TagDrop known-sources directory (docs/db/sources.json). */
data class SourcesDirectoryJson(
    val version: Int,
    val label: String? = null,
    val sources: List<RelatedSource> = emptyList()
)
