package com.github.mofosyne.tagdrop.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drop_sources")
data class DropSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
    val lastFetchedAt: Long? = null,
    val entryCount: Int = 0
)
