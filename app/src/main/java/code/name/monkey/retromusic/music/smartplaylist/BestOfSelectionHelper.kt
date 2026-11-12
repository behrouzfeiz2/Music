/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import android.content.Context
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.network.LastFMService
import code.name.monkey.retromusic.repository.RealRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Creates a local playlist from Last.fm popularity data.
 * Only tracks already present in the selected local folders are inserted.
 */
object BestOfSelectionHelper : KoinComponent {
    suspend fun createPlaylist(context: Context, localSongs: List<Song>): Pair<String, Int> {
        require(localSongs.isNotEmpty()) { context.getString(R.string.smart_playlist_empty) }

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val apiKey = preferences.getString("lastfm_api_key", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: LastFMService.API_KEY

        val repository = get<RealRepository>()
        val lastFm = get<LastFMService>()
        val lastFmRankedSongs = SmartPlaylistEngine.buildLastFmMix(
            localSongs = localSongs,
            lastFm = lastFm,
            maxSongs = 50,
            apiKey = apiKey
        )
        if (lastFmRankedSongs.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.smart_playlist_empty))
        }

        // Prefer Last.fm-ranked tracks. If fewer than 50 are available locally,
        // fill the remaining slots with the user's most-played selected tracks.
        val selectedIds = localSongs.map { it.id }.toHashSet()
        val rankedIds = lastFmRankedSongs.map { it.id }.toHashSet()
        val localFavorites = repository.topPlayedSongs()
            .filter { it.id in selectedIds && it.id !in rankedIds }
        val bestSongs = (lastFmRankedSongs + localFavorites + localSongs)
            .distinctBy { it.id }
            .take(50)

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
