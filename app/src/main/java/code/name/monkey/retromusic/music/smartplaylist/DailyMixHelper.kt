/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import android.content.Context
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.music.folders.FolderArtistRepository
import code.name.monkey.retromusic.repository.RealRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Persists a shuffled mix made only from tracks in the folders selected by the user.
 */
object DailyMixHelper : KoinComponent {
    private const val PLAYLIST_PREFIX = "Daily Mix"

    suspend fun createPlaylist(context: Context, localSongs: List<Song>): Pair<String, Int> {
        require(localSongs.isNotEmpty()) { "No songs found in the selected folders." }

        val repository = get<RealRepository>()
        val buckets = localSongs.distinctBy { it.id }
            .groupBy { song ->
                FolderArtistRepository.folderPathForSong(song.data)
                    ?: java.io.File(song.data).parent.orEmpty()
            }
            .values
            .map { it.shuffled().toMutableList() }
            .shuffled()

        // Round-robin the selected artist folders so a large archive from one
        // artist cannot crowd every other selected artist out of the mix.
        val songs = mutableListOf<Song>()
        var index = 0
        while (songs.size < 50 && buckets.any { index < it.size }) {
            buckets.forEach { bucket ->
                if (index < bucket.size && songs.size < 50) songs.add(bucket[index])
            }
            index++
        }
        var suffix = 1
        var playlistName = "$PLAYLIST_PREFIX $suffix"
        while (repository.checkPlaylistExists(playlistName).isNotEmpty()) {
            suffix++
            playlistName = "$PLAYLIST_PREFIX $suffix"
        }

        val playlistId = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))
        repository.insertSongs(songs.toSongsEntity(playlistId))
        return playlistName to songs.size
    }
}
