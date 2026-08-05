package com.github.mofosyne.tagdrop.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FoundCache::class, ScannedPaper::class, RetainedKey::class, DropSource::class, TrustedSigner::class], version = 25, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun paperDao(): PaperDao
    abstract fun keyDao(): KeyDao
    abstract fun dropSourceDao(): DropSourceDao
    abstract fun signerDao(): SignerDao

    companion object {
        const val DB_NAME = "tagdrop.db"
        const val SCHEMA_VERSION = 19

        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN collectionId TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN collectionId TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN collectionLabel TEXT")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN collectionTag TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN collectionLabel TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN collectionTag TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN lng REAL")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN lng REAL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN icon TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN icon TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN createdByMe INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN createdByMe INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN pendingNonce BLOB")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN pendingCompression INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS retained_keys (
                        keyHex TEXT NOT NULL PRIMARY KEY,
                        discoveredAt INTEGER NOT NULL,
                        hint TEXT
                    )"""
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SPEC §9 v4: `encrypted`/`pendingNonce` are retired (an override map's nonce now
                // travels embedded in its blob) in favor of `pendingOverrideBlob`. SQLite can't
                // drop columns on all supported versions, so recreate the table.
                db.execSQL(
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
                db.execSQL(
                    """INSERT INTO `found_caches_new`
                        (`cacheId`, `discoveredAt`, `hint`, `filename`, `mimeType`, `contentBytes`,
                         `collectionId`, `collectionLabel`, `collectionTag`, `lat`, `lng`, `icon`,
                         `createdByMe`, `pendingOverrideBlob`, `pendingCompression`)
                       SELECT `cacheId`, `discoveredAt`, `hint`, `filename`, `mimeType`, `contentBytes`,
                              `collectionId`, `collectionLabel`, `collectionTag`, `lat`, `lng`, `icon`,
                              `createdByMe`, NULL, `pendingCompression`
                       FROM `found_caches`"""
                )
                db.execSQL("DROP TABLE `found_caches`")
                db.execSQL("ALTER TABLE `found_caches_new` RENAME TO `found_caches`")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN wasEncrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN kdfAlg INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN kdfSalt BLOB")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN locationRadiusM REAL")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN locationRadiusM REAL")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN inReplyTo TEXT")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN description TEXT")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN inReplyTo TEXT")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN createdAt INTEGER")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN pendingOverrideDeclared INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN domain TEXT")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN locationLabel TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN locationLabel TEXT")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN pixelArt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE found_caches ADD COLUMN mimeTypeIsGuessed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drop_sources ADD COLUMN lastFetchFailed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Author-declared created_at (SPEC §3, key 52), used as a deterministic
                // tie-break when several scanned papers claim the same domain name (SPEC §7).
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN createdAt INTEGER")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Verified Authorship (SPEC §10): a TOFU cache of signer_id -> signer_pubkey
                // (mirrors the web reader's IndexedDB 'signers' store), plus the verification
                // result computed once at scan time and persisted alongside each cache/paper.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS trusted_signers (
                        signerIdHex TEXT NOT NULL PRIMARY KEY,
                        publicKey BLOB NOT NULL,
                        label TEXT,
                        firstSeenAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL("ALTER TABLE found_caches ADD COLUMN signatureStatus INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN signerIdHex TEXT")
                db.execSQL("ALTER TABLE found_caches ADD COLUMN signerLabel TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN signatureStatus INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN signerIdHex TEXT")
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN signerLabel TEXT")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Paper-Preview's title (SPEC §7 "Postcards") was already decoded from the wire
                // format but never persisted, so a single-file paper's own short subject/caption
                // had nowhere to be shown — only `label` reached the UI. Mirrors FoundCache's
                // existing `title` column.
                db.execSQL("ALTER TABLE scanned_papers ADD COLUMN title TEXT")
            }
        }

        private fun migration20To21(context: Context) = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS drop_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        addedAt INTEGER NOT NULL,
                        lastFetchedAt INTEGER,
                        entryCount INTEGER NOT NULL DEFAULT 0,
                        lastFetchFailed INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                // Seed default sources for existing installs (disabled by default).
                seedDefaultSources(db, System.currentTimeMillis(), context)
            }
        }

        /**
         * Seeds the drop_sources table from the same bundled docs/db/sources.json directory
         * SourcesActivity's "Reload default sources" reads (see
         * SourceFetcher.readBundledDefaultSources / app/build.gradle's
         * copySourcesJsonToRawRes) — a single source of truth for which sources ship with the
         * app, instead of a separately hand-maintained hardcoded list here.
         */
        private fun seedDefaultSources(db: SupportSQLiteDatabase, now: Long, context: Context) {
            com.github.mofosyne.tagdrop.data.SourceFetcher.readBundledDefaultSources(context).forEach { source ->
                db.execSQL(
                    "INSERT INTO drop_sources (name, url, enabled, addedAt, lastFetchedAt, entryCount, lastFetchFailed) VALUES (?, ?, 0, ?, NULL, 0, 0)",
                    arrayOf(source.name, source.url, now)
                )
            }
        }

        private fun seedCallback(context: Context) = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedDefaultSources(db, System.currentTimeMillis(), context)
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            val appContext = context.applicationContext
            INSTANCE ?: Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DB_NAME
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, migration20To21(appContext), MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
            .addCallback(seedCallback(appContext))
            .build().also { INSTANCE = it }
        }

        /** Closes the live connection so its underlying file can be safely replaced (e.g. restore), and forgets the instance so the next [get] reopens it. */
        @Synchronized
        fun close() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
