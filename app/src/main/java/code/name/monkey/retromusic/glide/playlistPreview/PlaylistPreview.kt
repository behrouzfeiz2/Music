package code.name.monkey.retromusic.glide.playlistPreview

import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.toSongs
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.network.LastFmAlbumArtworkLoader

class PlaylistPreview(val playlistWithSongs: PlaylistWithSongs) {

    val playlistEntity: PlaylistEntity get() = playlistWithSongs.playlistEntity
    val songs: List<Song> get() = playlistWithSongs.songs.toSongs()

    // Include cached Last.fm album artwork state in Glide's model identity so a
    // newly downloaded fallback cover invalidates an older generated preview.
    private val artworkCacheSignature: Long = songs.distinctBy { it.albumId }.fold(1L) { value, song ->
        val modified = LastFmAlbumArtworkLoader.getCachedFile(song.artistName, song.albumName)
            ?.lastModified() ?: 0L
        31L * value + modified
    }

    override fun equals(other: Any?): Boolean {
        println("Glide equals $this $other")
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PlaylistPreview
        if (other.playlistEntity.playListId != playlistEntity.playListId) return false
        if (other.songs.size != songs.size) return false
        if (other.artworkCacheSignature != artworkCacheSignature) return false
        return true
    }

    override fun hashCode(): Int {
        var result = playlistEntity.playListId.hashCode()
        result = 31 * result + playlistWithSongs.songs.size
        result = 31 * result + artworkCacheSignature.hashCode()
        return result
    }
}