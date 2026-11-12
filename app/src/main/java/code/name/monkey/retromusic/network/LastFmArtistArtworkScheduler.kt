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
        if (!preferences.getBoolean("initial_scan_scheduled", false)) {
            val initialWork = OneTimeWorkRequestBuilder<LastFmArtistArtworkWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(
                BATCH_CHAIN_NAME,
                ExistingWorkPolicy.KEEP,
                initialWork
            )
            preferences.edit().putBoolean("initial_scan_scheduled", true).apply()
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
