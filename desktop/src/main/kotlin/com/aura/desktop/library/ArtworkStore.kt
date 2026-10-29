package com.aura.desktop.library

import com.aura.music.core.model.Track
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Album covers kept by the app: each embedded cover is shrunk once to [MAX_SIDE] px and saved per
 * album, so lists never decode multi-megabyte pictures. Falls back to the usual image files that
 * sit next to the music (cover.jpg, folder.jpg…).
 */
class ArtworkStore(private val dir: Path) {

    init {
        Files.createDirectories(dir)
    }

    fun storeEmbedded(albumId: Long, bytes: ByteArray) {
        val target = dir.resolve("$albumId.jpg")
        if (Files.exists(target)) return
        val encoded = runCatching { shrink(bytes, MAX_SIDE) }.getOrNull() ?: return
        // Write aside and move into place, so a half-written file is never read.
        val temp = dir.resolve("$albumId.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temp, encoded)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.deleteIfExists(temp)
        }
    }

    /** The picture to show for [track], or null for the placeholder. */
    fun fileFor(track: Track): Path? {
        dir.resolve("${track.albumId}.jpg").takeIf(Files::exists)?.let { return it }
        val folder = runCatching { Path.of(track.uri).parent }.getOrNull() ?: return null
        return FOLDER_IMAGES.asSequence().map(folder::resolve).firstOrNull(Files::isRegularFile)
    }

    fun clear() {
        Files.list(dir).use { files -> files.forEach { runCatching { Files.delete(it) } } }
    }

    companion object {
        const val MAX_SIDE = 600
        private val FOLDER_IMAGES = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png", "AlbumArt.jpg", "albumart.jpg")

        /** Decodes [bytes] and re-encodes them as a JPEG no larger than [maxSide] on either side. */
        fun shrink(bytes: ByteArray, maxSide: Int): ByteArray? {
            val image = Image.makeFromEncoded(bytes)
            image.use {
                val scale = minOf(1f, maxSide.toFloat() / max(it.width, it.height))
                val w = (it.width * scale).roundToInt().coerceAtLeast(1)
                val h = (it.height * scale).roundToInt().coerceAtLeast(1)
                Surface.makeRasterN32Premul(w, h).use { surface ->
                    surface.canvas.drawImageRect(it, Rect.makeWH(it.width.toFloat(), it.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.MITCHELL, null, true)
                    return surface.makeImageSnapshot().use { snap -> snap.encodeToData(EncodedImageFormat.JPEG, 88)?.bytes }
                }
            }
        }
    }
}
