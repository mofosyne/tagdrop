package com.github.mofosyne.tagdrop.data

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
            try {
                val text = getText(url) ?: return@withContext null
                val json = JSONObject(text)
                val arr = json.optJSONArray("sources") ?: return@withContext null
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
