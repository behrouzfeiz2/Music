package code.name.monkey.retromusic.util

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Stores the user's pinned playlist IDs in the default app preferences.
 * The order in the stored list is also the order shown at the top of the library.
 */
object PlaylistPinStore {
    const val MAX_PINNED_PLAYLISTS = 4
    private const val KEY_PINNED_PLAYLIST_IDS = "pinned_playlist_ids"
    private val lock = Any()

    enum class ToggleResult {
        PINNED,
        UNPINNED,
        LIMIT_REACHED
    }

    fun getPinnedIds(context: Context): List<Long> = synchronized(lock) {
        readIds(context)
    }

    fun toggle(context: Context, playlistId: Long): ToggleResult = synchronized(lock) {
        val ids = readIds(context).toMutableList()
        if (ids.remove(playlistId)) {
            saveIds(context, ids)
            ToggleResult.UNPINNED
        } else {
            if (ids.size >= MAX_PINNED_PLAYLISTS) {
                ToggleResult.LIMIT_REACHED
            } else {
                ids.add(playlistId)
                saveIds(context, ids)
                ToggleResult.PINNED
            }
        }
    }

    fun retainExisting(context: Context, existingPlaylistIds: Collection<Long>) {
        synchronized(lock) {
            val existing = existingPlaylistIds.toSet()
            val current = readIds(context)
            val retained = current.filter { it in existing }.take(MAX_PINNED_PLAYLISTS)
            if (retained != current) saveIds(context, retained)
        }
    }

    private fun readIds(context: Context): List<Long> {
        val value = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getString(KEY_PINNED_PLAYLIST_IDS, "")
            .orEmpty()
        return value.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .distinct()
            .take(MAX_PINNED_PLAYLISTS)
    }

    private fun saveIds(context: Context, ids: List<Long>) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putString(KEY_PINNED_PLAYLIST_IDS, ids.joinToString(","))
        }
    }
}
