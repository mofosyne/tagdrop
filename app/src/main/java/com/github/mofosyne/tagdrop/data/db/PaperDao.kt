package com.github.mofosyne.tagdrop.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface PaperDao {
    @Query("SELECT * FROM scanned_papers ORDER BY scannedAt DESC")
    fun getAll(): LiveData<List<ScannedPaper>>

    @Query("SELECT * FROM scanned_papers WHERE rootHash = :rootHash LIMIT 1")
    suspend fun getByRootHash(rootHash: String): ScannedPaper?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(paper: ScannedPaper)

    @Delete
    suspend fun delete(paper: ScannedPaper)
}
