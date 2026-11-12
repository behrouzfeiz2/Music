/*
 * Copyright (C) 2026 Music app contributors.
 * Licensed under the GNU General Public License v3.0.
 */
package code.name.monkey.retromusic.music.smartplaylist

import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.Repository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/**
 * Resolves selected folder/file paths against the indexed music library.
 * This is the same reliable source used by Best Of Selection and avoids
 * FileUtil/MediaStore path matching for nested artist/album directories.
 */
object FolderSongSelector : KoinComponent {
    suspend fun songsInFiles(files: List<File>): List<Song> {
        val selected = files.mapNotNull { file ->
            runCatching { file.canonicalPath }.getOrNull()?.let { path ->
                path to file.isDirectory
            }
        }.distinctBy { it.first }

        if (selected.isEmpty()) return emptyList()

        val songs = get<Repository>().allSongs().distinctBy { it.id }
        return songs.filter { song ->
            val songPath = runCatching { File(song.data).canonicalPath }
                .getOrDefault(song.data)
            selected.any { (path, isDirectory) ->
                if (isDirectory) {
                    songPath == path || songPath.startsWith(path + File.separator)
                } else {
                    songPath == path
                }
            }
        }
    }
}
