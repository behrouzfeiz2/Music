/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import android.content.Context
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.repository.Repository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/**
 * Builds a random, balanced 30-track playlist from multiple selected folders.
 *
 * Reads the indexed library once and matches songs by their real file paths.
 * This deliberately avoids FileUtil.listFilesDeep(), whose audio-only filter
 * can prune the album subdirectories in an artist/album folder hierarchy.
 */
object BestOfSelectionHelper : KoinComponent {
    private const val PLAYLIST_SIZE = 30

    suspend fun createPlaylist(
        context: Context,
        selectedFolders: List<File>
    ): Pair<String, Int> {
        val folders = selectedFolders
            .filter { it.isDirectory }
            .mapNotNull { folder -> runCatching { folder.canonicalPath }.getOrNull() }
            .distinct()

        require(folders.isNotEmpty()) {
            context.getString(R.string.best_of_selection_folders_only)
        }

        val repository = get<Repository>()
        val allSongs = repository.allSongs().distinctBy { it.id }
        val orderedFolders = folders.sortedByDescending { it.length }
        val buckets = folders.associateWith { mutableListOf<code.name.monkey.retromusic.model.Song>() }

        allSongs.forEach { song ->
            val songPath = runCatching { File(song.data).canonicalPath }
                .getOrDefault(song.data)
            val matchingFolder = orderedFolders.firstOrNull { folderPath ->
                songPath == folderPath || songPath.startsWith(folderPath + File.separator)
            }
            if (matchingFolder != null) {
                buckets[matchingFolder]?.add(song)
            }
        }

        // Round-robin across selected folders so one large artist archive
        // cannot consume all 30 places in the playlist.
        val shuffledBuckets = buckets.values
            .filter { it.isNotEmpty() }
            .map { it.shuffled() }
            .shuffled()
        val bestSongs = mutableListOf<code.name.monkey.retromusic.model.Song>()
        var index = 0
        while (bestSongs.size < PLAYLIST_SIZE && shuffledBuckets.any { index < it.size }) {
            shuffledBuckets.forEach { bucket ->
                if (index < bucket.size && bestSongs.size < PLAYLIST_SIZE) {
                    bestSongs.add(bucket[index])
                }
            }
            index++
        }

        require(bestSongs.isNotEmpty()) {
            context.getString(R.string.smart_playlist_empty)
        }

        val baseName = context.getString(R.string.best_of_selection_playlist_name)
        var playlistName = baseName
        var suffix = 2
        while (repository.checkPlaylistExists(playlistName).isNotEmpty()) {
            playlistName = "$baseName ($suffix)"
            suffix++
        }

        val playlistId = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))
        repository.insertSongs(bestSongs.toSongsEntity(playlistId))
        return playlistName to bestSongs.size
    }
}
