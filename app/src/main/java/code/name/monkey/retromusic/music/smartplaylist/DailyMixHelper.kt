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
import code.name.monkey.retromusic.repository.Repository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/**
 * Creates a shuffled mix from songs indexed inside the selected folders.
 * Uses the same indexed-library/path-matching strategy as Best Of Selection.
 */
object DailyMixHelper : KoinComponent {
    private const val PLAYLIST_PREFIX = "Daily Mix"
    private const val PLAYLIST_SIZE = 30

    suspend fun createPlaylist(
        context: Context,
        selectedFolders: List<File>
    ): Pair<String, Int> {
        val localSongs = FolderSongSelector.songsInFiles(selectedFolders)
        require(localSongs.isNotEmpty()) {
            context.getString(code.name.monkey.retromusic.R.string.smart_playlist_empty)
        }

        val repository = get<Repository>()
        val buckets = localSongs.distinctBy { it.id }
            .groupBy { song ->
                FolderArtistRepository.folderPathForSong(song.data)
                    ?: File(song.data).parent.orEmpty()
            }
            .values
            .map { it.shuffled().toMutableList() }
            .shuffled()

        // Round-robin artist folders so one large archive cannot dominate.
        val songs = mutableListOf<Song>()
        var index = 0
        while (songs.size < PLAYLIST_SIZE && buckets.any { index < it.size }) {
            buckets.forEach { bucket ->
                if (index < bucket.size && songs.size < PLAYLIST_SIZE) {
                    songs.add(bucket[index])
                }
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
