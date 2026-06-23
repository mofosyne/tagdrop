package com.github.mofosyne.tagdrop.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.mofosyne.tagdrop.data.db.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Full local backup/restore for the entire Room database (every scanned/created cache, paper
 * manifest, and retained key — this device's only copy of that history). Rather than
 * re-serializing each table, this snapshots the live SQLite file directly so it stays exact
 * and automatically covers any future column without code changes here.
 */
object BackupManager {
    private const val MANIFEST_ENTRY = "backup-info.txt"
    private const val DB_ENTRY = "tagdrop.db"

    private val FILENAME_FMT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun suggestFilename(): String = "tagdrop-backup-${FILENAME_FMT.format(Date())}.zip"

    sealed class RestoreResult {
        object Success : RestoreResult()
        object IncompatibleVersion : RestoreResult()
        object InvalidFile : RestoreResult()
    }

    /** Flushes pending writes, zips the live db file plus a small manifest, and returns a shareable content:// URI. Call off the main thread. */
    fun export(context: Context): Uri {
        val db = AppDatabase.get(context)
        // Forces every committed write out of the WAL file and into the main db file, so
        // copying just that one file below yields a complete, consistent snapshot.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()

        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "backup.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(
                "schemaVersion=${AppDatabase.SCHEMA_VERSION}\n".toByteArray() +
                "appVersion=$versionName\n".toByteArray() +
                "exportedAt=${System.currentTimeMillis()}\n".toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Replaces the live database with the one inside [uri]. On [RestoreResult.Success] the
     * caller must restart the app — every existing Room/LiveData handle still points at the
     * now-closed connection. Call off the main thread.
     */
    fun restore(context: Context, uri: Uri): RestoreResult {
        val tempZip = File(context.cacheDir, "restore-incoming.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempZip.outputStream().use { output -> input.copyTo(output) }
        } ?: return RestoreResult.InvalidFile

        val result = runCatching { ZipFile(tempZip) }.getOrNull()?.use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: return@use RestoreResult.InvalidFile
            val manifestText = zip.getInputStream(manifestEntry).bufferedReader().readText()
            val schemaVersion = Regex("schemaVersion=(\\d+)").find(manifestText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@use RestoreResult.InvalidFile
            if (schemaVersion > AppDatabase.SCHEMA_VERSION) return@use RestoreResult.IncompatibleVersion

            val dbEntry = zip.getEntry(DB_ENTRY) ?: return@use RestoreResult.InvalidFile

            AppDatabase.close()
            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
            dbFile.parentFile?.mkdirs()
            zip.getInputStream(dbEntry).use { input -> dbFile.outputStream().use { output -> input.copyTo(output) } }
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            RestoreResult.Success
        } ?: RestoreResult.InvalidFile

        tempZip.delete()
        return result
    }
}
