package com.github.mofosyne.tagdrop.data

/**
 * In-memory cache of drop entries fetched from enabled drop sources.
 * Keyed by source ID so a single source's entries can be replaced or evicted atomically.
 */
object DropEntryCache {
    private val entries = mutableMapOf<Long, List<DropEntry>>() // sourceId -> entries

    fun update(sourceId: Long, drops: List<DropEntry>) {
        entries[sourceId] = drops
    }

    fun remove(sourceId: Long) {
        entries.remove(sourceId)
    }

    fun hasEntries(sourceId: Long): Boolean = entries.containsKey(sourceId)

    /** All entries across all sources. */
    fun allEntries(): List<DropEntry> = entries.values.flatten()

    /** Entries from the given subset of source IDs only (e.g. only enabled sources). */
    fun allEntries(sourceIds: Set<Long>): List<DropEntry> =
        entries.filterKeys { it in sourceIds }.values.flatten()
}
