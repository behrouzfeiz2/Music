package code.name.monkey.retromusic.glide.audiocover

import android.media.MediaMetadataRetriever
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class AudioFileCoverFetcher(private val model: AudioFileCover) : DataFetcher<InputStream> {
    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(model.filePath)
            val picture = retriever.embeddedPicture
            stream = if (picture != null && picture.isNotEmpty()) {
                ByteArrayInputStream(picture)
            } else {
                AudioFileCoverUtils.fallback(model.filePath)
            }
            val result = stream
            if (result != null) callback.onDataReady(result)
            else callback.onLoadFailed(FileNotFoundException("Album artwork not found"))
        } catch (error: Exception) {
            try {
                stream = AudioFileCoverUtils.fallback(model.filePath)
                val result = stream
                if (result != null) callback.onDataReady(result)
                else callback.onLoadFailed(error)
            } catch (fallbackError: Exception) {
                callback.onLoadFailed(fallbackError)
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
    }

    override fun cancel() = Unit

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.LOCAL
}
