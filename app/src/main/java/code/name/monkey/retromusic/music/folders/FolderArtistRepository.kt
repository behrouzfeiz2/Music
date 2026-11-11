/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.folders

import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import java.io.File
import java.text.Collator
import java.util.Base64

/**
 * Builds the artist/album hierarchy from the on-device Music directory.
 *
 * Music/<artist>/<album>/<tracks>
 * Music/<artist>/<tracks> is represented as the Singles album.
 */
object FolderArtistRepository {
    private const val FOLDER_KEY_PREFIX = "__music_folder__:"

    private data class TrackLocation(
        val artistName: String,
        val artistPath: String,
        val albumName: String,
        val albumPath: String
    )

    fun artists(songs: List<Song>): List<Artist> {
        val grouped = songs.mapNotNull { song ->
            location(song.data)?.let { it.artistPath to song }
        }.groupBy({ it.first }, { it.second })

        val collator = Collator.getInstance()
        val result = grouped.mapNotNull { (path, tracks) ->
            buildArtist(path, tracks)
        }
        return when (PreferenceUtil.artistSortOrder) {
            SortOrder.ArtistSortOrder.ARTIST_Z_A ->
                result.sortedWith { left, right -> collator.compare(right.name, left.name) }
            else -> result.sortedWith { left, right -> collator.compare(left.name, right.name) }
        }
    }

    fun artistForFolder(songs: List<Song>, folderPath: String): Artist {
        val normalized = File(folderPath).absoluteFile.normalize().path
        val tracks = songs.filter { song ->
            val path = location(song.data)?.artistPath
            path != null && File(path).absoluteFile.normalize().path == normalized
        }
        return buildArtist(normalized, tracks) ?: Artist.empty
    }

    fun folderPathForSong(path: String): String? = location(path)?.artistPath

    fun keyForFolder(folderPath: String): String =
        FOLDER_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(folderPath.toByteArray(Charsets.UTF_8))

    fun isFolderKey(value: String): Boolean = value.startsWith(FOLDER_KEY_PREFIX)

    fun artistForKey(songs: List<Song>, key: String): Artist? {
        if (!isFolderKey(key)) return null
        val encoded = key.removePrefix(FOLDER_KEY_PREFIX)
        val path = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return artistForFolder(songs, path)
    }

    private fun buildArtist(folderPath: String, tracks: List<Song>): Artist? {
        if (tracks.isEmpty()) return null
        val albums = tracks.mapNotNull { song ->
            location(song.data)?.let { it.albumPath to Pair(it.albumName, song) }
        }.groupBy({ it.first }, { it.second })
            .map { (albumPath, entries) ->
                val albumName = entries.first().first
                val remappedSongs = entries.map { (_, song) ->
                    Song(
                        song.id, song.title, song.trackNumber, song.year, song.duration,
                        song.data, song.dateModified, song.albumId, albumName,
                        song.artistId, File(folderPath).name, song.composer, File(folderPath).name
                    )
                }
                Album(albumPath.hashCode().toLong(), remappedSongs)
            }
        if (albums.isEmpty()) return null
        return Artist(folderPath.hashCode().toLong(), albums, true)
    }

    private fun location(filePath: String): TrackLocation? {
        val file = File(filePath).absoluteFile
        val parent = file.parentFile ?: return null
        val musicRoot = generateSequence(parent) { it.parentFile }
            .firstOrNull { it.name.equals("Music", ignoreCase = true) } ?: return null
        val relative = runCatching { parent.relativeTo(musicRoot) }.getOrNull() ?: return null
        val parts = relative.toPath().map { it.toString() }
        val artistName = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val artistPath = File(musicRoot, artistName).absolutePath
        val albumName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Singles"
        val albumPath = if (albumName == "Singles") {
            File(artistPath, "Singles").absolutePath
        } else {
            File(artistPath, albumName).absolutePath
        }
        return TrackLocation(artistName, artistPath, albumName, albumPath)
    }
}
