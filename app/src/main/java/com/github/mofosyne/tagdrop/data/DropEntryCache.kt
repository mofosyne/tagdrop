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

    fun allEntries(): List<DropEntry> = entries.values.flatten()
}
