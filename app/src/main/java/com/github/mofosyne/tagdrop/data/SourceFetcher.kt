package com.github.mofosyne.tagdrop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and parses a drop-source JSON registry file (SPEC §17).
 * Uses only [HttpURLConnection] and [JSONObject] — no third-party HTTP or JSON libraries.
 * Returns null on any network or parse error.
 *
 * JSON field names match the TagDrop wire format where applicable:
 *   `hint` = key 3, `description` = key 40, `lat` = key 26, `lng` = key 27.
 */
object SourceFetcher {
    suspend fun fetch(url: String): DropSourceJson? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val text = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
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
            DropSourceJson(
                version = json.optInt("version", 1),
                label   = json.optString("label").takeIf { it.isNotEmpty() },
                drops   = drops
            )
        } catch (e: Exception) {
            null
        }
    }
}
