package com.github.mofosyne.tagdrop.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FoundCache::class, ScannedPaper::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun paperDao(): PaperDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS scanned_papers (
                        rootHash TEXT NOT NULL PRIMARY KEY,
                        scannedAt INTEGER NOT NULL,
                        label TEXT,
                        set TEXT,
                        slug TEXT,
                        cborBytes BLOB NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN collectionId TEXT")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN collectionId TEXT")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tagdrop.db"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build().also { INSTANCE = it }
        }
    }
}
