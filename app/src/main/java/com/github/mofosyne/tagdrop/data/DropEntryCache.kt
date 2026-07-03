package com.github.mofosyne.tagdrop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory cache of drop entries fetched from enabled drop sources.
 * Keyed by source ID so a single source's entries can be replaced or evicted atomically.
 *
 * [changes] emits a new value whenever the cache is mutated so observers (e.g. MapFragment)
 * can re-render immediately — independent of Room LiveData timing.
 */
object DropEntryCache {
    private val entries = mutableMapOf<Long, List<DropEntry>>() // sourceId -> entries

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun update(sourceId: Long, drops: List<DropEntry>) {
        entries[sourceId] = drops
        _changes.value++
    }

    fun remove(sourceId: Long) {
        entries.remove(sourceId)
        _changes.value++
    }

    fun hasEntries(sourceId: Long): Boolean = entries.containsKey(sourceId)

    /** All entries across all sources. */
    fun allEntries(): List<DropEntry> = entries.values.flatten()

    /** Entries from the given subset of source IDs only (e.g. only enabled sources). */
    fun allEntries(sourceIds: Set<Long>): List<DropEntry> =
        entries.filterKeys { it in sourceIds }.values.flatten()
}
