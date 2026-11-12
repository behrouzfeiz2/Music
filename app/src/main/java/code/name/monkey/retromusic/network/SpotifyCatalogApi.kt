package code.name.monkey.retromusic.network

import android.net.Uri
import android.util.Base64
import code.name.monkey.retromusic.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class SpotifyTrack(val name: String, val album: String, val durationMs: Long, val popularity: Int, val spotifyUrl: String)
data class SpotifyAlbum(val name: String, val releaseDate: String, val imageUrl: String?, val spotifyUrl: String)
data class SpotifyArtistProfile(val name: String, val imageUrl: String?, val spotifyUrl: String, val tracks: List<SpotifyTrack>, val albums: List<SpotifyAlbum>)

/** Client-credentials catalog lookup. The compiled secret is extractable from the APK. */
object SpotifyCatalogApi {
    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0L
    private val artistImageCache = ConcurrentHashMap<String, String>()

    suspend fun findArtistImage(name: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            artistImageCache[normalize(name)]?.let { return@withContext it }
            if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank() || BuildConfig.SPOTIFY_CLIENT_SECRET.isBlank()) {
                return@withContext null
            }
            val token = getAccessToken() ?: return@withContext null
            val query = Uri.encode("artist:\\"$name\\"")
            val response = getJson("https://api.spotify.com/v1/search?q=$query&type=artist&limit=5", token)
            val items = response.optJSONObject("artists")?.optJSONArray("items")
                ?: return@withContext null
            var selected: JSONObject? = null
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (normalize(item.optString("name")) == normalize(name)) {
                    selected = item
                    break
                }
            }
            val image = selected?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                ?.takeIf { it.isNotBlank() }
            if (image != null) artistImageCache[normalize(name)] = image
            image
        }

    suspend fun findArtist(name: String): SpotifyArtistProfile? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank() || BuildConfig.SPOTIFY_CLIENT_SECRET.isBlank()) {
                return@withContext null
            }
            val token = getAccessToken() ?: return@withContext null
            val query = Uri.encode("artist:\"$name\"")
            val artistSearch = getJson("https://api.spotify.com/v1/search?q=$query&type=artist&limit=5", token)
            val artists = artistSearch.optJSONObject("artists")?.optJSONArray("items") ?: return@withContext null
            val wanted = normalize(name)
            var selected: JSONObject? = null
            for (i in 0 until artists.length()) {
                val item = artists.optJSONObject(i) ?: continue
                if (normalize(item.optString("name")) == wanted) { selected = item; break }
            }
            val artist = selected ?: artists.optJSONObject(0) ?: return@withContext null
            val artistName = artist.optString("name", name)
            val artistId = artist.optString("id")
            if (artistId.isBlank()) return@withContext null

            val trackQuery = Uri.encode("artist:\"$artistName\"")
            val trackSearch = getJson("https://api.spotify.com/v1/search?q=$trackQuery&type=track&limit=10&market=US", token)
            val trackItems = trackSearch.optJSONObject("tracks")?.optJSONArray("items")
            val tracks = buildList {
                if (trackItems != null) for (i in 0 until trackItems.length()) {
                    val item = trackItems.optJSONObject(i) ?: continue
                    val trackArtists = item.optJSONArray("artists") ?: continue
                    val exact = (0 until trackArtists.length()).any { j ->
                        normalize(trackArtists.optJSONObject(j)?.optString("name").orEmpty()) == normalize(artistName)
                    }
                    if (exact) add(SpotifyTrack(
                        item.optString("name"), item.optJSONObject("album")?.optString("name").orEmpty(),
                        item.optLong("duration_ms"), item.optInt("popularity"),
                        item.optJSONObject("external_urls")?.optString("spotify").orEmpty()
                    ))
                }
            }.sortedByDescending { it.popularity }

            val albumSearch = getJson("https://api.spotify.com/v1/artists/$artistId/albums?include_groups=album,single,compilation&limit=20&market=US", token)
            val albumItems = albumSearch.optJSONArray("items")
            val albums = buildList {
                if (albumItems != null) for (i in 0 until albumItems.length()) {
                    val item = albumItems.optJSONObject(i) ?: continue
                    val image = item.optJSONArray("images")?.optJSONObject(0)?.optString("url")
                    add(SpotifyAlbum(
                        item.optString("name"), item.optString("release_date"),
                        image?.takeIf { it.isNotBlank() },
                        item.optJSONObject("external_urls")?.optString("spotify").orEmpty()
                    ))
                }
            }.distinctBy { normalize(it.name) }

            SpotifyArtistProfile(
                artistName,
                artist.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() },
                artist.optJSONObject("external_urls")?.optString("spotify").orEmpty(),
                tracks, albums
            )
        }

    @Synchronized
    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        accessToken?.takeIf { now < tokenExpiresAt - 60_000L }?.let { return it }
        val credentials = "${BuildConfig.SPOTIFY_CLIENT_ID}:${BuildConfig.SPOTIFY_CLIENT_SECRET}"
        val basic = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val connection = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Basic $basic")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write("grant_type=client_credentials".toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            accessToken = response.optString("access_token").takeIf { it.isNotBlank() }
            tokenExpiresAt = now + response.optLong("expires_in", 3600L) * 1000L
            accessToken
        } catch (_: Exception) {
            null
        } finally { connection.disconnect() }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Spotify API returned HTTP $code")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}
