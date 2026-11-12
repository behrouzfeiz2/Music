package code.name.monkey.retromusic.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import code.name.monkey.retromusic.App
import code.name.monkey.retromusic.util.ImageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads and caches Last.fm album artwork using the artist/album names from
 * the Music/<artist>/<album> folder hierarchy. Files stay inside app storage.
 */
object LastFmAlbumArtworkLoader {
    private const val CACHE_DIRECTORY = "lastfm_album_artwork"
    private const val NEGATIVE_CACHE_MILLIS = 14L * 24L * 60L * 60L * 1000L
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    private val albumLocks = ConcurrentHashMap<String, Mutex>()
    private val missingLookups = ConcurrentHashMap<String, Long>()
    private val apiRequestMutex = Mutex()
    private var lastApiRequestAt = 0L

    fun getCachedFile(artist: String, album: String): File? {
        if (!isValidName(artist, album)) return null
        val file = cacheFile(cacheKey(artist, album))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun load(artist: String, album: String): File? = withContext(Dispatchers.IO) {
        if (!isValidName(artist, album)) return@withContext null

        val key = cacheKey(artist, album)
        cacheFile(key).takeIf { it.isFile && it.length() > 0L }?.let {
            return@withContext it
        }

        val now = System.currentTimeMillis()
        val missingAt = missingLookups[key]
        if (missingAt != null && now - missingAt < NEGATIVE_CACHE_MILLIS) {
            return@withContext null
        }

        val mutex = albumLocks.getOrPut(key) { Mutex() }
        mutex.withLock {
            cacheFile(key).takeIf { it.isFile && it.length() > 0L }?.let {
                return@withLock it
            }

            val lastMissingAt = missingLookups[key]
            if (lastMissingAt != null &&
                System.currentTimeMillis() - lastMissingAt < NEGATIVE_CACHE_MILLIS
            ) return@withLock null

            try {
                val imageUrl = apiRequestMutex.withLock {
                    val waitMillis = (1100L - (System.currentTimeMillis() - lastApiRequestAt))
                        .coerceAtLeast(0L)
                    if (waitMillis > 0L) delay(waitMillis)
                    try {
                        LastFmCatalogApi.findAlbumImage(artist.trim(), album.trim())
                    } finally {
                        lastApiRequestAt = System.currentTimeMillis()
                    }
                }
                if (imageUrl.isNullOrBlank()) {
                    missingLookups[key] = System.currentTimeMillis()
                    return@withLock null
                }

                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("Accept", "image/*")
                    connection.setRequestProperty("User-Agent", "Music Android app (Branex.ir)")
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) return@withLock null

                    val bytes = connection.inputStream.use { it.readBytes() }
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@withLock null

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > 2048 ||
                        bounds.outHeight / sampleSize > 2048
                    ) {
                        sampleSize *= 2
                    }

                    val bitmap = BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    ) ?: return@withLock null

                    val resized = ImageUtil.resizeBitmap(bitmap, 2048)
                    val target = cacheFile(key)
                    val directory = target.parentFile ?: return@withLock null
                    if (!directory.exists() && !directory.mkdirs()) return@withLock null

                    val temporary = File(directory, "$key.tmp")
                    val saved = temporary.outputStream().buffered().use { output ->
                        resized.compress(Bitmap.CompressFormat.JPEG, 92, output)
                    }
                    if (!saved) {
                        temporary.delete()
                        return@withLock null
                    }
                    if (target.exists()) target.delete()
                    if (!temporary.renameTo(target)) {
                        temporary.delete()
                        return@withLock null
                    }
                    missingLookups.remove(key)
                    target
                } finally {
                    connection.disconnect()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun isValidName(artist: String, album: String): Boolean =
        artist.isNotBlank() &&
            album.isNotBlank() &&
            !artist.equals("Unknown Artist", ignoreCase = true) &&
            !album.equals("Unknown Album", ignoreCase = true) &&
            !album.equals("Singles", ignoreCase = true)

    private fun cacheFile(key: String): File =
        File(File(App.getContext().filesDir, CACHE_DIRECTORY), "$key.jpg")

    private fun cacheKey(artist: String, album: String): String {
        val value = "${artist.trim().lowercase()}\u0000${album.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
