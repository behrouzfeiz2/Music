package code.name.monkey.retromusic.network

import code.name.monkey.retromusic.App
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.util.CustomArtistImageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class LastFmArtworkLoadResult {
    SAVED,
    NOT_FOUND,
    FAILED,
    ALREADY_CACHED,
    CUSTOM_IMAGE
}

/**
 * Shared entry point for lazy artwork loading, background filling and the
 * manual archive rebuild. A per-artist mutex prevents duplicate API calls
 * and simultaneous image downloads from these three paths.
 */
object LastFmArtistArtworkLoader {
    private val artistLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun load(
        artist: Artist,
        forceRefresh: Boolean = false
    ): LastFmArtworkLoadResult {
        val lock = artistLocks.getOrPut(artist.id) { Mutex() }
        return lock.withLock {
            val context = App.getContext()
            val imageUtil = CustomArtistImageUtil.getInstance(context)

            if (imageUtil.hasCustomArtistImage(artist)) {
                return@withLock LastFmArtworkLoadResult.CUSTOM_IMAGE
            }
            if (!forceRefresh && imageUtil.hasLastFmArtistImage(artist)) {
                return@withLock LastFmArtworkLoadResult.ALREADY_CACHED
            }

            try {
                val imageUrl = LastFmCatalogApi.findArtistImage(
                    artist.name,
                    forceRefresh = forceRefresh
                ) ?: return@withLock LastFmArtworkLoadResult.NOT_FOUND

                if (imageUtil.cacheLastFmArtistImage(artist, imageUrl)) {
                    LastFmArtworkLoadResult.SAVED
                } else {
                    LastFmArtworkLoadResult.FAILED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LastFmArtworkLoadResult.FAILED
            }
        }
    }
}
