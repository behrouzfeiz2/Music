package code.name.monkey.retromusic.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LastFmArtistArtworkScheduler {
    const val BATCH_CHAIN_NAME = "lastfm-artist-artwork-batches"
    private const val PERIODIC_WORK_NAME = "lastfm-artist-artwork-weekly"

    fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val constraints = networkConstraints()

        // Start the initial archive pass only once. WorkManager persists it
        // across app exits; the weekly worker discovers artists added later.
        val preferences = context.applicationContext.getSharedPreferences(
            "lastfm_background_artwork",
            Context.MODE_PRIVATE
        )
        // Version 2 switches the source of artist names from audio metadata
        // to the Music/<artist>/<album> folder hierarchy. Re-run once so older
        // metadata-based progress IDs do not suppress the folder-based scan.
        val artistSourceVersion = 2
        val sourceChanged = preferences.getInt("artist_source_version", 0) != artistSourceVersion
        if (sourceChanged || !preferences.getBoolean("initial_scan_scheduled", false)) {
            if (sourceChanged) {
                preferences.edit()
                    .remove("processed_artist_ids")
                    .putInt("artist_source_version", artistSourceVersion)
                    .putBoolean("initial_scan_scheduled", true)
                    .apply()
            }
            val initialWork = OneTimeWorkRequestBuilder<LastFmArtistArtworkWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(
                BATCH_CHAIN_NAME,
                if (sourceChanged) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                initialWork
            )
            if (!sourceChanged) {
                preferences.edit().putBoolean("initial_scan_scheduled", true).apply()
            }
        }

        // Recheck periodically so artists added to the library later are included.
        val periodicWork = PeriodicWorkRequestBuilder<LastFmArtistArtworkWorker>(
            7, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }
}
