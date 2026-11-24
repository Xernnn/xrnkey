package com.xern.xrnkey.clip

import java.io.File

/**
 * One clip: its text, when it was copied (ms), and whether it is pinned. An image's clip has no
 * text: its [image] is a file of [ClipImages], of type [mime].
 */
data class Clip(
    val text: String,
    val time: Long,
    val pinned: Boolean = false,
    val image: String? = null,
    val mime: String? = null,
) {
    /** What pin and delete name it by: the text, or the image's file. */
    val key: String get() = image?.let { IMAGE_KEY + it } ?: text

    companion object {
        /** An image's key starts with a character no copied text keeps. */
        const val IMAGE_KEY = "\u0000image:"
    }
}

/**
 * The clipboard history, kept only on the phone: in a file in the keyboard's own
 * credential-encrypted storage, so it can't be read before the first unlock and never leaves
 * the phone. Newest first; pinned clips stay until deleted, the others go after [MAX] newer
 * texts (or [ClipImages.MAX] newer images) or on [clear]. An image's file goes with its clip.
 */
class ClipHistory(private val file: File?, private val images: ClipImages? = null) {
    private val list = mutableListOf<Clip>()

    init {
        if (file != null && file.exists()) {
            file.readLines().mapNotNullTo(list) { decode(it) }
        }
        // An image whose file is gone is dropped; a file no clip names (a copy cut short, a
        // history cleared from the settings) is deleted.
        if (images != null) {
            val kept = images.names().toSet()
            if (list.removeAll { it.image != null && it.image !in kept }) save()
            images.keepOnly(list.mapNotNull { it.image }.toSet())
        }
    }

    val clips: List<Clip> get() = list.toList()

    /** A newly copied text: to the top (once), unless it is blank. */
    fun add(text: String, time: Long) {
        if (text.isBlank()) return
        put(Clip(text, time))
    }

    /** A newly copied image, kept by [images] as [name]: to the top (once). */
    fun addImage(name: String, mime: String, time: Long) {
        if (images == null) return
        put(Clip("", time, image = name, mime = mime))
    }

    private fun put(clip: Clip) {
        val old = list.firstOrNull { it.key == clip.key }
        list.removeAll { it.key == clip.key }
        list.add(0, clip.copy(pinned = old?.pinned ?: false))
        var texts = 0
        var pictures = 0
        drop { !it.pinned && if (it.image == null) ++texts > MAX else ++pictures > ClipImages.MAX }
        save()
    }

    /** Pins or unpins the clip of [key] ([Clip.key]: a text's own text). */
    fun pin(key: String, pinned: Boolean) {
        val i = list.indexOfFirst { it.key == key }
        if (i < 0) return
        list[i] = list[i].copy(pinned = pinned)
        save()
    }

    fun delete(key: String) {
        if (drop { it.key == key }) save()
    }

    /** Clears every clip that isn't pinned. */
    fun clear() {
        if (drop { !it.pinned }) save()
    }

    /** Removes the clips [which] picks, and their images' files. */
    private fun drop(which: (Clip) -> Boolean): Boolean {
        val gone = list.filter(which)
        if (gone.isEmpty()) return false
        list.removeAll(gone.toSet())
        for (c in gone) c.image?.let { images?.delete(it) }
        return true
    }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(list.joinToString("") { encode(it) + "\n" })
        tmp.renameTo(f)
    }

    companion object {
        const val MAX = 50

        /** A text clip's line: pinned, time, the text escaped. An image's: `img`, pinned, time, file, type. */
        private fun encode(c: Clip): String {
            val pinned = if (c.pinned) 1 else 0
            if (c.image != null) return "$IMAGE\t$pinned\t${c.time}\t${c.image}\t${c.mime}"
            return "$pinned\t${c.time}\t" +
                c.text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        }

        private const val IMAGE = "img"

        /** An image's file name, as [ClipImages] makes them: nothing that leaves the folder. */
        private val IMAGE_NAME = Regex("[0-9a-f]+\\.[a-z]+")

        private fun decode(line: String): Clip? {
            if (line.startsWith("$IMAGE\t")) {
                val p = line.split('\t')
                if (p.size != 5 || !IMAGE_NAME.matches(p[3])) return null
                val time = p[2].toLongOrNull() ?: return null
                return Clip("", time, p[1] == "1", image = p[3], mime = p[4])
            }
            val p = line.split('\t', limit = 3)
            if (p.size != 3) return null
            val time = p[1].toLongOrNull() ?: return null
            val text = StringBuilder()
            var i = 0
            val s = p[2]
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    text.append(
                        when (s[i + 1]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> s[i + 1]
                        },
                    )
                    i += 2
                } else {
                    text.append(c)
                    i++
                }
            }
            return Clip(text.toString(), time, p[0] == "1")
        }

        /**
         * Whether a copied text may go into the history: never one the app marked sensitive
         * (ClipDescription.EXTRA_IS_SENSITIVE, which password managers and banking apps set),
         * never one copied while a password field had the keyboard, never one that isn't text.
         */
        fun keep(text: CharSequence?, sensitive: Boolean, fromPasswordField: Boolean): Boolean =
            !text.isNullOrBlank() && !sensitive && !fromPasswordField
    }
}
