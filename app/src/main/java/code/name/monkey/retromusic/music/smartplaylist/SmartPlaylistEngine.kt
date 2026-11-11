/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.music.folders.FolderArtistRepository
import code.name.monkey.retromusic.network.LastFMService
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.Locale

/**
 * Builds a ranked mix exclusively from tracks already present in the local library.
 * Last.fm supplies popularity rankings; no audio is downloaded.
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
        val ranked = localArtists.mapIndexed { index, entry ->
            async {
                semaphore.withPermit {
                    runCatching {
                        val artistName = entry.key.substringAfterLast('/')
                        val response = lastFm.artistTopTracks(artistName, 50)
                        parseMatches(response, artistName, entry.value, index)
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()

        ranked.sortedWith(
            compareByDescending<RankedTrack> { it.playCount }
                .thenBy { it.artistOrder }
                .thenBy { it.song.title.lowercase(Locale.ROOT) }
        ).distinctBy { it.song.id }.take(maxSongs).map { it.song }
    }

    private fun parseMatches(
        response: JsonObject,
        folderArtist: String,
        localSongs: List<Song>,
        artistOrder: Int
    ): List<RankedTrack> {
        val tracks = response.getAsJsonObject("toptracks")
            ?.getAsJsonArray("track") ?: return emptyList()
        val localByTitle = localSongs.groupBy { normalize(it.title) }
        return tracks.mapNotNull { element ->
            val remote = element.asJsonObject
            val title = remote.get("name")?.asString ?: return@mapNotNull null
            val remoteArtist = remote.getAsJsonObject("artist")?.get("name")?.asString
            if (remoteArtist != null && normalize(remoteArtist) != normalize(folderArtist)) {
                return@mapNotNull null
            }
            val candidates = localByTitle[normalize(title)].orEmpty()
            val match = candidates.maxByOrNull { it.duration } ?: return@mapNotNull null
            val playCount = remote.get("playcount")?.asString?.toLongOrNull() ?: 0L
            RankedTrack(match, playCount, artistOrder)
        }
    }

    private fun normalize(value: String): String {
        val withoutVersion = value.replace(
            Regex("""(?i)\\s*[\\[(][^\\])]*(?:live|remaster(?:ed)?|deluxe|radio edit|mono|stereo|version|bonus track|anniversary)[^\\])]*[\\])\\s*"""),
            " "
        )
        val decomposed = Normalizer.normalize(withoutVersion, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
