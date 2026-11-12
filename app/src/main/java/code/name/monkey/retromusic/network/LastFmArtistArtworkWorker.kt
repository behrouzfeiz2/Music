package code.name.monkey.retromusic.network

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.music.folders.FolderArtistRepository
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.util.CustomArtistImageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fills missing Last.fm artwork in small batches so a large music library
 * never keeps a single WorkManager execution alive for too long.
 */
class LastFmArtistArtworkWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters), KoinComponent {

    private val repository: RealRepository by inject()

    override suspend fun doWork(): androidx.work.ListenableWorker.Result = workerMutex.withLock {
        val artists = try {
            withContext(Dispatchers.IO) {
                FolderArtistRepository.artists(repository.allSongs()).distinctBy { it.id }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@withLock androidx.work.ListenableWorker.Result.retry()
        }

        val preferences = applicationContext.getSharedPreferences(
            PROGRESS_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val processed = preferences.getStringSet(PROCESSED_IDS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        val pending = artists.asSequence()
            .filter(::isEligible)
            .filterNot { processed.contains(it.id.toString()) }
            .take(BATCH_SIZE)
            .toList()

        if (pending.isEmpty()) return@withLock androidx.work.ListenableWorker.Result.success()

        val imageUtil = CustomArtistImageUtil.getInstance(applicationContext)
        pending.forEachIndexed { index, artist ->
            if (isStopped) return@withLock androidx.work.ListenableWorker.Result.retry()

            // Network errors are isolated to this artist. LastFmCatalogApi
            // already performs up to three attempts with 20s timeouts.
            try {
                if (imageUtil.hasCustomArtistImage(artist) ||
                    imageUtil.hasLastFmArtistImage(artist)
                ) {
                    // Nothing to download.
                } else {
                    LastFmArtistArtworkLoader.load(artist)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep moving; one unreachable artist must not block the queue.
            }

            processed.add(artist.id.toString())
            preferences.edit()
                .putStringSet(PROCESSED_IDS, HashSet(processed))
                .apply()

            if (index < pending.lastIndex) delay(ARTIST_REQUEST_INTERVAL_MS)
        }

        val hasMore = artists.any { isEligible(it) && !processed.contains(it.id.toString()) }
        if (hasMore) {
            val nextBatch = OneTimeWorkRequestBuilder<LastFmArtistArtworkWorker>()
                .setConstraints(LastFmArtistArtworkScheduler.networkConstraints())
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                LastFmArtistArtworkScheduler.BATCH_CHAIN_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                nextBatch
            )
        }
        androidx.work.ListenableWorker.Result.success()
    }

    private fun isEligible(artist: Artist): Boolean =
        artist.name.isNotBlank() &&
            artist.name != "Unknown Artist" &&
            artist.name != "Various Artists"

    companion object {
        private const val PROGRESS_PREFERENCES = "lastfm_background_artwork"
        private const val PROCESSED_IDS = "processed_artist_ids"
        private const val BATCH_SIZE = 4
        private const val ARTIST_REQUEST_INTERVAL_MS = 1100L

        // Also prevents the periodic worker and the one-time chain from
        // processing the same batch simultaneously in the app process.
        private val workerMutex = Mutex()
    }
}
