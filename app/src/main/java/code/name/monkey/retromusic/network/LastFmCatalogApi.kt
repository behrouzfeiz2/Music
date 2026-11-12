package code.name.monkey.retromusic.network

import android.net.Uri
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.App
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Artist image lookup through the Last.fm API already used by Music.
 * Archive scans call this sequentially to avoid excessive API traffic.
 */
object LastFmCatalogApi {
    private val imageCache = ConcurrentHashMap<String, String>()

    suspend fun findArtistImage(name: String, forceRefresh: Boolean = false): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = normalize(name)
            if (!forceRefresh) imageCache[key]?.let { return@withContext it }

            val preferences = PreferenceManager.getDefaultSharedPreferences(App.getContext())
            val apiKey = preferences.getString("lastfm_api_key", null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: LastFMService.API_KEY
            val endpoint = "https://ws.audioscrobbler.com/2.0/?method=artist.getinfo" +
                "&artist=${Uri.encode(name)}&api_key=${Uri.encode(apiKey)}&format=json&autocorrect=1"

            var attempt = 0
            while (true) {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 20000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "Music Android app (Branex.ir)")
                    val code = connection.responseCode
                    if (code == 429 && attempt < 2) {
                        val waitSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 3L
                        connection.disconnect()
                        Thread.sleep(waitSeconds.coerceIn(2L, 20L) * 1000L)
                        attempt++
                        continue
                    }

                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        throw IllegalStateException("Last.fm request failed (HTTP $code)")
                    }

                    val root = JSONObject(body)
                    if (root.has("error")) {
                        val errorCode = root.optInt("error")
                        val message = root.optString("message").take(120)
                        // Last.fm error 6 commonly means this artist is not in its catalogue.
                        // Treat it as a normal "no image" result so one unknown artist
                        // does not abort the entire library scan.
                        if (errorCode == 6) return@withContext null
                        throw IllegalStateException(
                            "Last.fm API error $errorCode" +
                                if (message.isNotBlank()) ": $message" else ""
                        )
                    }

                    val images = root.optJSONObject("artist")?.optJSONArray("image")
                        ?: return@withContext null
                    var imageUrl: String? = null
                    for (size in listOf("mega", "extralarge", "large", "medium")) {
                        for (i in 0 until images.length()) {
                            val item = images.optJSONObject(i) ?: continue
                            if (item.optString("size") != size) continue
                            val candidate = item.optString("#text")
                            if ((candidate.startsWith("https://") || candidate.startsWith("http://")) &&
                                !candidate.contains("2a96cbd8b46e442fc41c2b86b821562f")
                            ) {
                                imageUrl = candidate.replaceFirst("http://", "https://")
                                break
                            }
                        }
                        if (imageUrl != null) break
                    }
                    imageUrl?.let { imageCache[key] = it }
                    return@withContext imageUrl
                } catch (error: java.io.IOException) {
                    // Retry transient network failures (including timeouts) before giving up.
                    if (attempt < 2) {
                        attempt++
                        kotlinx.coroutines.delay(attempt * 1500L)
                        continue
                    }
                    throw error
                } finally {
                    connection.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
