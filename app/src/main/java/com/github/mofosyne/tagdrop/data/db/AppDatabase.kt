package com.github.mofosyne.tagdrop.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FoundCache::class, ScannedPaper::class, RetainedKey::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun paperDao(): PaperDao
    abstract fun keyDao(): KeyDao

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN collectionLabel TEXT")
                database.execSQL("ALTER TABLE found_caches ADD COLUMN collectionTag TEXT")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN collectionLabel TEXT")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN collectionTag TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN lat REAL")
                database.execSQL("ALTER TABLE found_caches ADD COLUMN lng REAL")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN lat REAL")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN lng REAL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN icon TEXT")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN icon TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN createdByMe INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE scanned_papers ADD COLUMN createdByMe INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE found_caches ADD COLUMN pendingNonce BLOB")
                database.execSQL("ALTER TABLE found_caches ADD COLUMN pendingCompression INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS retained_keys (
                        keyHex TEXT NOT NULL PRIMARY KEY,
                        discoveredAt INTEGER NOT NULL,
                        hint TEXT
                    )"""
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // SPEC §9 v4: `encrypted`/`pendingNonce` are retired (an override map's nonce now
                // travels embedded in its blob) in favor of `pendingOverrideBlob`. SQLite can't
                // drop columns on all supported versions, so recreate the table.
                database.execSQL(
                    """CREATE TABLE `found_caches_new` (
                        `cacheId` TEXT NOT NULL,
                        `discoveredAt` INTEGER NOT NULL,
                        `hint` TEXT,
                        `filename` TEXT,
                        `mimeType` TEXT NOT NULL,
                        `contentBytes` BLOB,
                        `collectionId` TEXT,
                        `collectionLabel` TEXT,
                        `collectionTag` TEXT,
                        `lat` REAL,
                        `lng` REAL,
                        `icon` TEXT,
                        `createdByMe` INTEGER NOT NULL DEFAULT 0,
                        `pendingOverrideBlob` BLOB,
                        `pendingCompression` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`cacheId`)
                    )"""
                )
                database.execSQL(
                    """INSERT INTO `found_caches_new`
                        (`cacheId`, `discoveredAt`, `hint`, `filename`, `mimeType`, `contentBytes`,
                         `collectionId`, `collectionLabel`, `collectionTag`, `lat`, `lng`, `icon`,
                         `createdByMe`, `pendingOverrideBlob`, `pendingCompression`)
                       SELECT `cacheId`, `discoveredAt`, `hint`, `filename`, `mimeType`, `contentBytes`,
                              `collectionId`, `collectionLabel`, `collectionTag`, `lat`, `lng`, `icon`,
                              `createdByMe`, NULL, `pendingCompression`
                       FROM `found_caches`"""
                )
                database.execSQL("DROP TABLE `found_caches`")
                database.execSQL("ALTER TABLE `found_caches_new` RENAME TO `found_caches`")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE found_caches ADD COLUMN wasEncrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tagdrop.db"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .build().also { INSTANCE = it }
        }
    }
}
