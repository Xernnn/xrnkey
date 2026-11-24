package com.xern.xrnkey.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The clipboard history's images, as files in a folder of the keyboard's own credential-encrypted
 * storage, next to the history's list. A copied image is kept as it is when it is small, else
 * downscaled to [MAX_SIDE] on its longest side and written again (a JPEG as JPEG, anything else as
 * PNG); each also gets a small thumbnail for the clipboard panel. A file is named by a hash of
 * what was copied, so the same image copied twice is one file.
 */
class ClipImages(val dir: File) {

    /** A kept image: its file's name in [dir] and its MIME type. */
    data class Stored(val name: String, val mime: String)

    fun file(name: String): File = File(dir, name)

    private fun thumbFile(name: String) = File(dir, name + THUMB)

    /**
     * Keeps a copied image read from [input], which the app named [named] ("image/png", or
     * any image): null when it is no image, too big to read, or can't be written. The type is
     * what the bytes are, whatever the app said. Run off the main thread (it decodes).
     */
    fun store(input: InputStream, named: String): Stored? {
        val bytes = readAtMost(input, READ_LIMIT) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val mime = bounds.outMimeType ?: named
        val longest = maxOf(w, h)
        val asIs = longest <= MAX_SIDE && bytes.size <= KEEP_BYTES && mime in KEPT_AS_IS
        val outMime = if (asIs) mime else if (mime == JPEG) JPEG else PNG
        val name = hash(bytes) + "." + EXTENSIONS.getValue(outMime)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val out = file(name)
        try {
            if (!out.isFile) {
                if (asIs) {
                    write(out) { it.write(bytes) }
                } else {
                    val bitmap = decode(bytes, longest, MAX_SIDE) ?: return null
                    val format = if (outMime == JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                    write(out) { bitmap.compress(format, JPEG_QUALITY, it) }
                    bitmap.recycle()
                }
            }
            if (!thumbFile(name).isFile) {
                decode(bytes, longest, THUMB_SIDE)?.let { t ->
                    write(thumbFile(name)) { t.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    t.recycle()
                }
            }
        } catch (e: IOException) {
            out.delete()
            return null
        }
        return Stored(name, outMime)
    }

    /** The panel's picture of an image: its thumbnail, or null when it is gone. */
    fun thumbnail(name: String): Bitmap? {
        val f = thumbFile(name).takeIf { it.isFile } ?: return null
        return BitmapFactory.decodeFile(f.path)
    }

    /** An image and its thumbnail, gone. */
    fun delete(name: String) {
        file(name).delete()
        thumbFile(name).delete()
    }

    /** The images kept (not the thumbnails, nor a write cut short). */
    fun names(): List<String> = dir.listFiles().orEmpty()
        .map { it.name }
        .filter { !it.endsWith(THUMB) && !it.endsWith(TMP) }

    /** Every file but those of [keep]: what a history no longer lists, or a write cut short. */
    fun keepOnly(keep: Set<String>) {
        for (f in dir.listFiles().orEmpty()) {
            val base = f.name.removeSuffix(TMP).removeSuffix(THUMB)
            if (f.name.endsWith(TMP) || base !in keep) f.delete()
        }
    }

    private inline fun write(f: File, body: (java.io.OutputStream) -> Unit) {
        val tmp = File(f.path + TMP)
        tmp.outputStream().use(body)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("can't keep ${f.name}")
        }
    }

    companion object {
        /** The folder's name, in the keyboard's files. */
        const val DIR = "clip-images"

        /** Unpinned images kept, newest first. */
        const val MAX = 10

        /** The longest side a kept image has; a bigger one is downscaled. */
        const val MAX_SIDE = 1600

        /** The thumbnail's longest side, for the panel's cards. */
        const val THUMB_SIDE = 320

        /** An image this small (and no bigger than [MAX_SIDE]) is kept byte for byte. */
        const val KEEP_BYTES = 3 * 1024 * 1024

        /** Nothing bigger is read. */
        const val READ_LIMIT = 30 * 1024 * 1024

        const val PNG = "image/png"
        const val JPEG = "image/jpeg"
        private const val JPEG_QUALITY = 90
        private const val THUMB = ".thumb"
        private const val TMP = ".tmp"

        /** What a small image may be kept as, as it came (a GIF keeps its frames). */
        private val KEPT_AS_IS = setOf(PNG, JPEG, "image/gif", "image/webp")
        private val EXTENSIONS = mapOf(PNG to "png", JPEG to "jpg", "image/gif" to "gif", "image/webp" to "webp")

        private fun readAtMost(input: InputStream, limit: Int): ByteArray? = input.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        }

        private fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }

        /** [bytes] decoded to at most [side] on the longest side ([longest] is its own). */
        private fun decode(bytes: ByteArray, longest: Int, side: Int): Bitmap? {
            var sample = 1
            while (longest / (sample * 2) >= side) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val l = maxOf(b.width, b.height)
            if (l <= side) return b
            val scale = side.toFloat() / l
            val s = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()), true)
            if (s !== b) b.recycle()
            return s
        }
    }
}
