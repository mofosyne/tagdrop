package com.github.mofosyne.tagdrop.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KeyDao {
    @Query("SELECT * FROM retained_keys")
    suspend fun getAll(): List<RetainedKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: RetainedKey)

    @Delete
    suspend fun delete(key: RetainedKey)

    @Query("DELETE FROM retained_keys")
    suspend fun deleteAll()
}
