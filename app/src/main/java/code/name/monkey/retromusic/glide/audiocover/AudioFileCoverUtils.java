/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 */
package code.name.monkey.retromusic.glide.audiocover;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.images.Artwork;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AudioFileCoverUtils {

  private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
      "jpg", "jpeg", "jpe", "png", "webp", "bmp", "gif",
      "avif", "heic", "heif", "tif", "tiff"
  ));

  private static final String[] PREFERRED_NAMES = {
      "cover", "folder", "album", "albumart", "album_art",
      "front", "artwork", "thumbnail"
  };

  public static InputStream fallback(String path) throws FileNotFoundException {
    // Try jaudiotagger's format-aware reader for embedded artwork (MP3, FLAC, OGG, MP4, etc.).
    try {
      Artwork artwork = AudioFileIO.read(new File(path)).getTagOrCreateAndSetDefault().getFirstArtwork();
      if (artwork != null && artwork.getBinaryData() != null && artwork.getBinaryData().length > 0) {
        return new ByteArrayInputStream(artwork.getBinaryData());
      }
    } catch (Exception ignored) {
      // Continue with external image files in the album directory.
    }

    File parent = new File(path).getParentFile();
    if (parent == null || !parent.isDirectory()) {
      throw new FileNotFoundException("Album artwork not found for " + path);
    }

    File[] images = parent.listFiles(file -> {
      if (!file.isFile()) return false;
      String name = file.getName();
      int dot = name.lastIndexOf('.');
      if (dot < 0 || dot == name.length() - 1) return false;
      return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    });

    if (images == null || images.length == 0) {
      throw new FileNotFoundException("Album artwork not found for " + path);
    }

    Arrays.sort(images, Comparator
        .comparingInt(AudioFileCoverUtils::imagePriority)
        .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));

    return new FileInputStream(images[0]);
  }

  private static int imagePriority(File image) {
    String name = image.getName().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    for (int i = 0; i < PREFERRED_NAMES.length; i++) {
      if (base.equals(PREFERRED_NAMES[i]) || base.startsWith(PREFERRED_NAMES[i] + "_")
          || base.startsWith(PREFERRED_NAMES[i] + "-") || base.startsWith(PREFERRED_NAMES[i] + " ")) {
        return i;
      }
    }
    return PREFERRED_NAMES.length;
  }
}
