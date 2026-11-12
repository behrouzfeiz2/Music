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
import kotlinx.coroutines.CancellationException
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
        // Each selected top-level folder is an artist; child folders are albums.
        // Resolve each song back to its artist folder through the same folder model
        // used by the library, rather than trusting embedded Artist tags or string prefixes.
        fun canonical(file: File): String =
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                .trimEnd(File.separatorChar)

        val folders = artistFolders
            .filter { it.isDirectory }
            .map { File(canonical(it)) }
            .distinctBy { canonical(it) }
            .sortedBy { canonical(it).length }
            .fold(mutableListOf<File>()) { roots, folder ->
                val path = canonical(folder)
                if (roots.none { root ->
                        val rootPath = canonical(root)
                        path == rootPath || path.startsWith(rootPath + File.separator)
                    }
                ) roots.add(folder)
                roots
            }

        val localArtists = folders.mapNotNull { folder ->
            val selectedPath = canonical(folder)
            val songs = localSongs.filter { song ->
                FolderArtistRepository.folderPathForSong(song.data)?.let { artistPath ->
                    canonical(File(artistPath)) == selectedPath
                } == true
            }
            if (songs.isEmpty()) null else folder.name to songs
        }.sortedBy { it.first.lowercase(Locale.ROOT) }

        if (localArtists.isEmpty()) {
            throw IllegalStateException(
                "Selected folders did not match the Music/<artist>/<album>/<track> structure. " +
                    "Select artist folders (the parent folders containing album folders), not album folders."
            )
        }

        val semaphore = Semaphore(1)
        val successfulRequests = AtomicInteger(0)
        val requestErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val ranked = localArtists.mapIndexed { index, entry ->
            async {
                semaphore.withPermit {
                    try {
                        val artistName = entry.first
                        val response = lastFm.artistTopTracks(artistName, 100, apiKey)
                        if (response.has("error")) {
                            val message = response.get("message")?.takeUnless { it.isJsonNull }?.asString
                                ?: "Last.fm error ${response.get("error")}"
                            requestErrors.add("$artistName: $message")
                            emptyList()
                        } else {
                            successfulRequests.incrementAndGet()
                            val matches = parseMatches(response, artistName, entry.second, index)
                            matches
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        requestErrors.add("${entry.first}: ${error.message ?: error.javaClass.simpleName}")
                        emptyList()
                    } finally {
                        // Keep Last.fm requests strictly sequential and rate-limited.
                        delay(1100)
                    }
                }
            }
        }.awaitAll().flatten()

        if (successfulRequests.get() == 0) {
            val details = requestErrors.take(3).joinToString("; ")
            throw IllegalStateException(
                if (details.isNotBlank()) "Last.fm requests failed: $details"
                else "Last.fm could not be reached."
            )
        }
        if (ranked.isEmpty()) {
            throw IllegalStateException(
                "Last.fm responded for ${successfulRequests.get()} selected artist folder(s), " +
                    "but none of the returned top tracks matched the local audio files. " +
                    "Check folder names and track file names."
            )
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
