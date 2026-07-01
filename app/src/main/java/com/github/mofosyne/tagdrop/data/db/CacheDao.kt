package com.github.mofosyne.tagdrop.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CacheDao {
    @Query("SELECT * FROM found_caches ORDER BY discoveredAt DESC")
    fun getAllCaches(): LiveData<List<FoundCache>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: FoundCache)

    @Delete
    suspend fun delete(cache: FoundCache)

    @Query("SELECT * FROM found_caches WHERE cacheId = :id LIMIT 1")
    suspend fun getById(id: String): FoundCache?

    @Query("SELECT * FROM found_caches WHERE cacheId = :id LIMIT 1")
    fun observeById(id: String): LiveData<FoundCache?>

    @Query("SELECT * FROM found_caches WHERE collectionId = :collectionId ORDER BY discoveredAt DESC")
    fun getByCollectionId(collectionId: String): LiveData<List<FoundCache>>

    @Query("DELETE FROM found_caches WHERE collectionId = :collectionId")
    suspend fun deleteByCollectionId(collectionId: String)

    /** Items carrying a hidden override-map blob not yet unlocked by any retained `key_material` (SPEC §9). */
    @Query("SELECT * FROM found_caches WHERE pendingOverrideBlob IS NOT NULL")
    suspend fun getPendingOverrides(): List<FoundCache>

    /** Other cached items whose `in_reply_to` points at [parentId] (SPEC §7) — the reverse of [FoundCache.inReplyTo]. */
    @Query("SELECT * FROM found_caches WHERE inReplyTo = :parentId ORDER BY discoveredAt DESC")
    suspend fun getRepliesTo(parentId: String): List<FoundCache>

    /** Updates the stored MIME type for a single cache entry (used by the "Set type…" override in ViewDataUriActivity). */
    @Query("UPDATE found_caches SET mimeType = :mimeType, mimeTypeIsGuessed = 0 WHERE cacheId = :cacheId")
    suspend fun updateMimeType(cacheId: String, mimeType: String)
}
