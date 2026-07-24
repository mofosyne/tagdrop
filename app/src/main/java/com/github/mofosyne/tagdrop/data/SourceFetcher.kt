package com.github.mofosyne.tagdrop.data

import android.content.Context
import com.github.mofosyne.tagdrop.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and parses drop-source JSON files (SPEC §17).
 * Uses only [HttpURLConnection] and [JSONObject] — no third-party HTTP or JSON libraries.
 * Returns null on any network or parse error.
 *
 * JSON field names match the TagDrop wire format where applicable:
 *   `hint` = key 3, `description` = key 40, `lat` = key 26, `lng` = key 27.
 */
object SourceFetcher {

    const val OFFICIAL_SOURCES_URL = "https://mofosyne.github.io/tagdrop/db/sources.json"

    suspend fun fetch(url: String): DropSourceJson? = withContext(Dispatchers.IO) {
        try {
            val text = getText(url) ?: return@withContext null
            val json = JSONObject(text)
            val dropsArray = json.optJSONArray("drops") ?: return@withContext null
            val drops = (0 until dropsArray.length()).map { i ->
                val obj = dropsArray.getJSONObject(i)
                DropEntry(
                    id            = obj.getString("id"),
                    lat           = obj.getDouble("lat"),
                    lng           = obj.getDouble("lng"),
                    hint          = obj.optString("hint").takeIf { it.isNotEmpty() },
                    description   = obj.optString("description").takeIf { it.isNotEmpty() },
                    status        = obj.optString("status").takeIf { it.isNotEmpty() },
                    statusUpdated = obj.optString("status_updated").takeIf { it.isNotEmpty() },
                    dropType      = obj.optString("drop_type").takeIf { it.isNotEmpty() }
                )
            }
            val related = json.optJSONArray("related_sources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val u    = obj.optString("url").takeIf  { it.isNotEmpty() } ?: return@mapNotNull null
                    RelatedSource(
                        name        = name,
                        url         = u,
                        description = obj.optString("description").takeIf { it.isNotEmpty() },
                        maintainer  = obj.optString("maintainer").takeIf { it.isNotEmpty() }
                    )
                }
            } ?: emptyList()
            DropSourceJson(
                version        = json.optInt("version", 1),
                label          = json.optString("label").takeIf { it.isNotEmpty() },
                drops          = drops,
                relatedSources = related
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchDirectory(url: String = OFFICIAL_SOURCES_URL): SourcesDirectoryJson? =
        withContext(Dispatchers.IO) {
            val text = getText(url) ?: return@withContext null
            parseDirectory(text)
        }

    /**
     * Parses a sources-directory JSON document (the same shape served at [OFFICIAL_SOURCES_URL]
     * and bundled locally as `res/raw/default_sources.json`) into [RelatedSource]s. Pulled out of
     * [fetchDirectory] so both the live fetch and a bundled-resource read go through identical
     * parsing — see SourcesActivity's default-sources handling.
     */
    fun parseDirectory(text: String): SourcesDirectoryJson? {
        return try {
            val json = JSONObject(text)
            val arr = json.optJSONArray("sources") ?: return null
            val sources = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val u    = obj.optString("url").takeIf  { it.isNotEmpty() } ?: return@mapNotNull null
                RelatedSource(
                    name        = name,
                    url         = u,
                    description = obj.optString("description").takeIf { it.isNotEmpty() },
                    maintainer  = obj.optString("maintainer").takeIf { it.isNotEmpty() }
                )
            }
            SourcesDirectoryJson(
                version = json.optInt("version", 1),
                label   = json.optString("label").takeIf { it.isNotEmpty() },
                sources = sources
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the sources bundled at build time from docs/db/sources.json (see
     * `copySourcesJsonToRawRes` in app/build.gradle) — the same directory "Browse recommended
     * sources" fetches live from [OFFICIAL_SOURCES_URL], parsed through the identical
     * [parseDirectory] path so fresh-install seeding and "Reload default sources" share one
     * source of truth for which sources ship with the app, rather than a separately
     * hand-maintained hardcoded list.
     */
    fun readBundledDefaultSources(context: Context): List<RelatedSource> {
        val text = context.resources.openRawResource(R.raw.default_sources)
            .bufferedReader().use { it.readText() }
        return parseDirectory(text)?.sources ?: emptyList()
    }

    private fun getText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            val text = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            text
        } catch (e: Exception) {
            connection.disconnect()
            null
        }
    }
}
