/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.music.folders.FolderArtistRepository
import code.name.monkey.retromusic.network.LastFMService
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uses Last.fm rankings to select tracks that already exist in the local library.
 * This class never downloads audio files.
 */
object SmartPlaylistEngine {
    private data class RankedTrack(val song: Song, val playCount: Long, val artistOrder: Int)

    suspend fun buildLastFmMix(
        localSongs: List<Song>,
        lastFm: LastFMService,
        maxSongs: Int = 40
    ): List<Song> = coroutineScope {
        val localArtists = localSongs.mapNotNull { song ->
            val folder = FolderArtistRepository.folderPathForSong(song.data) ?: return@mapNotNull null
            folder to song
        }.groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .take(12)

        if (localArtists.isEmpty()) return@coroutineScope emptyList()

        val semaphore = Semaphore(3)
        val successfulRequests = AtomicInteger(0)
        val ranked = localArtists.mapIndexed { index, entry ->
            async {
                semaphore.withPermit {
                    runCatching {
                        val artistName = entry.key.substringAfterLast('/')
                        val response = lastFm.artistTopTracks(artistName, 50)
                        successfulRequests.incrementAndGet()
                        parseMatches(response, artistName, entry.value, index)
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()

        if (successfulRequests.get() == 0) {
            throw IllegalStateException("Last.fm could not be reached or returned an API error")
        }

        ranked.sortedWith(
            compareByDescending<RankedTrack> { it.playCount }
                .thenBy { it.artistOrder }
                .thenBy { it.song.title.lowercase(Locale.ROOT) }
        ).distinctBy { it.song.id }.take(maxSongs).map { it.song }
    }

    suspend fun buildArtistBestOf(
        localSongs: List<Song>,
        artistName: String,
        lastFm: LastFMService,
        maxSongs: Int = 30
    ): List<Song> {
        require(localSongs.isNotEmpty()) { "No local songs were supplied for this artist" }
        require(artistName.isNotBlank()) { "Artist folder name is empty" }

        val response = lastFm.artistTopTracks(artistName.trim(), 100)
        val apiError = response.get("message")?.asString
        if (response.has("error")) {
            throw IllegalStateException(apiError ?: "Last.fm API error: ${response.get("error")}")
        }
        return parseMatches(response, artistName, localSongs, 0)
            .sortedByDescending { it.playCount }
            .distinctBy { it.song.id }
            .take(maxSongs)
            .map { it.song }
    }

    private fun parseMatches(
        response: JsonObject,
        folderArtist: String,
        localSongs: List<Song>,
        artistOrder: Int
    ): List<RankedTrack> {
        val apiError = response.get("message")?.asString
        if (response.has("error")) {
            throw IllegalStateException(apiError ?: "Last.fm API error")
        }
        val trackElement = response.getAsJsonObject("toptracks")?.get("track")
            ?: return emptyList()
        val tracks = when {
            trackElement.isJsonArray -> trackElement.asJsonArray.toList()
            trackElement.isJsonObject -> listOf(trackElement)
            else -> emptyList()
        }

        val localByTitle = localSongs.groupBy { normalize(it.title) }
        return tracks.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val remote = element.asJsonObject
            val title = remote.get("name")?.takeUnless { it.isJsonNull }?.asString
                ?: return@mapNotNull null
            val normalizedTitle = normalize(title)
            if (normalizedTitle.isBlank()) return@mapNotNull null

            // The request itself is scoped to this artist. Do not reject legitimate
            // local matches because Last.fm reports a featured artist or alias.
            val candidates = localByTitle[normalizedTitle].orEmpty()
            val match = candidates.maxByOrNull { it.duration } ?: return@mapNotNull null
            val playCount = remote.get("playcount")?.let(::readLong) ?: 0L
            RankedTrack(match, playCount, artistOrder)
        }
    }

    private fun readLong(value: JsonElement): Long? = runCatching {
        if (value.isJsonPrimitive) value.asLong else null
    }.getOrNull()

    private fun normalize(value: String): String {
        val withoutFeaturing = value
            .replace(Regex("(?i)\\s*\\((?:feat\\.?|featuring)\\s+[^)]*\\)"), " ")
            .replace(Regex("(?i)\\s*\\[(?:feat\\.?|featuring)\\s+[^]]*]"), " ")
            .replace(
                Regex("(?i)\\s*[\\[(].*(?:live|remaster(?:ed)?|deluxe|radio edit|mono|stereo|version|bonus track|anniversary).*[)\\]]\\s*"),
                " "
            )
            .replace(
                Regex("(?i)\\b(?:live|remaster(?:ed)?|deluxe|radio edit|mono|stereo|bonus track|anniversary)\\b"),
                " "
            )
        val decomposed = Normalizer.normalize(withoutFeaturing, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
