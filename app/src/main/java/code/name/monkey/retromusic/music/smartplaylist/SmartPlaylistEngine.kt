/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.network.LastFMService
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.Locale
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uses Last.fm rankings to select tracks that already exist in the local library.
 * This class never downloads audio files.
 */
object SmartPlaylistEngine {
    private data class RankedTrack(val song: Song, val playCount: Long, val artistOrder: Int)

    suspend fun buildLastFmMix(
        localSongs: List<Song>,
        artistFolders: List<File>,
        lastFm: LastFMService,
        maxSongs: Int = 50,
        apiKey: String = LastFMService.API_KEY
    ): List<Song> = coroutineScope {
        // In this library layout, the selected directory is the artist and its
        // child directories are albums. Audio tags are not reliable for artist identity.
        val folders = artistFolders
            .filter { it.isDirectory }
            .map { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }
            .distinctBy { it.path }
            .sortedBy { it.path.length }
            .fold(mutableListOf<File>()) { roots, folder ->
                if (roots.none { root ->
                        folder.path == root.path ||
                            folder.path.startsWith(root.path + File.separator)
                    }
                ) roots.add(folder)
                roots
            }

        val localArtists = folders.mapNotNull { folder ->
            val folderPath = folder.path.trimEnd(File.separatorChar) + File.separator
            val songs = localSongs.filter { song ->
                val songPath = runCatching { File(song.data).canonicalPath }
                    .getOrDefault(File(song.data).absolutePath)
                songPath.startsWith(folderPath)
            }
            if (songs.isEmpty()) null else folder.name to songs
        }.sortedBy { it.first.lowercase(Locale.ROOT) }

        if (localArtists.isEmpty()) {
            throw IllegalStateException("No audio files were found inside the selected artist folders")
        }

        val semaphore = Semaphore(1)
        val successfulRequests = AtomicInteger(0)
        val ranked = localArtists.mapIndexed { index, entry ->
            async {
                semaphore.withPermit {
                    runCatching {
                        val artistName = entry.first
                        val response = lastFm.artistTopTracks(artistName, 100, apiKey)
                        val matches = parseMatches(response, artistName, entry.second, index)
                        successfulRequests.incrementAndGet()
                        matches
                    }.getOrDefault(emptyList())
                }.also {
                    // One request at a time, with a conservative interval.
                    delay(1100)
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
