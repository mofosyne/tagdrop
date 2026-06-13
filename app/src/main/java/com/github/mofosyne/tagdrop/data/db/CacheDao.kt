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
}
