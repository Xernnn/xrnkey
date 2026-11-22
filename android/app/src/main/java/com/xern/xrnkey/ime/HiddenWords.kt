package com.xern.xrnkey.ime

import java.io.File
import java.util.Locale

/**
 * Suggestions the owner hid with the strip's remove mark: words and phrases the strip and the
 * next words never offer again, whichever model they came from. One a line, in lower case, kept
 * with the personal words; only read to filter, never learned from. The settings list them, to
 * show one again.
 */
class HiddenWords(private val file: File) {
    private val texts = LinkedHashSet<String>()

    init {
        read()
    }

    private fun read() {
        texts.clear()
        if (file.isFile) file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { texts += it }
    }

    val all: List<String> get() = texts.toList()

    operator fun contains(text: String): Boolean = texts.isNotEmpty() && key(text) in texts

    /** Read again first: the settings may have shown one again, or cleared them all, since. */
    fun hide(text: String) {
        read()
        if (key(text).isNotEmpty() && texts.add(key(text))) save()
    }

    fun show(text: String) {
        read()
        if (texts.remove(key(text))) save()
    }

    /** Written whole and moved into place, so a half-written list is never read. */
    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(texts.joinToString("") { "$it\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun key(text: String) = text.trim().lowercase(Locale.ROOT)
}
