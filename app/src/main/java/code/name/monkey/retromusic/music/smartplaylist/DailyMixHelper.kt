/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import android.content.Context
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.model.Song
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
        val songs = localSongs.distinctBy { it.id }.shuffled().take(50)
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
