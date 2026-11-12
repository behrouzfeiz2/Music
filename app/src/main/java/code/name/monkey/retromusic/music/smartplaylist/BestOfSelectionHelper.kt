/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import android.content.Context
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.toSong
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.RealRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Creates an offline 50-track playlist from the selected local folders.
 * Favorites are prioritized; remaining tracks are selected randomly.
 */
object BestOfSelectionHelper : KoinComponent {
    suspend fun createPlaylist(context: Context, localSongs: List<Song>): Pair<String, Int> {
        require(localSongs.isNotEmpty()) { context.getString(R.string.smart_playlist_empty) }

        val repository = get<RealRepository>()
        val selectedIds = localSongs.map { it.id }.toHashSet()
        val favorites = repository.favoritePlaylistSongs()
            .map { it.toSong() }
            .filter { it.id in selectedIds }
            .distinctBy { it.id }
            .shuffled()
        val favoriteIds = favorites.map { it.id }.toHashSet()
        val remaining = localSongs
            .distinctBy { it.id }
            .filterNot { it.id in favoriteIds }
            .shuffled()

        val bestSongs = (favorites + remaining).take(50)
        require(bestSongs.isNotEmpty()) { context.getString(R.string.smart_playlist_empty) }

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
