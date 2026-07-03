package com.github.mofosyne.tagdrop.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DropSourceDao {
    @Query("SELECT * FROM drop_sources ORDER BY addedAt DESC")
    fun getAll(): LiveData<List<DropSource>>

    @Query("SELECT * FROM drop_sources WHERE enabled = 1")
    suspend fun getEnabled(): List<DropSource>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: DropSource): Long

    @Update
    suspend fun update(source: DropSource)

    @Delete
    suspend fun delete(source: DropSource)
}
