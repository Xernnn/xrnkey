package com.xern.xrnkey.ja

import java.io.File

/**
 * The owner's own Japanese words, which the dictionary lacks (a name, a place, a word of their
 * own): each written one way and read another, added and removed in the settings, kept on the
 * phone only. The converter takes them as dictionary words that win their reading, and predicts
 * them from its start. No Android types here, so the JVM tests run it as the keyboard does.
 *
 * The file is a line per word, its reading, a tab and how it is written, as Mozc's and Gboard's
 * user dictionaries export them; [import] takes those files too (their other columns ignored).
 */
class UserDictionary {
    class Entry(val reading: String, val surface: String)

    // In the order added: the settings list the latest first.
    private val entries = LinkedHashMap<String, Entry>()
    private val byReading = HashMap<String, MutableList<Entry>>()

    /** The reading lengths there are words for: where a conversion looks for them. */
    private val lengths = sortedMapOf<Int, Int>()

    /** Goes up with every change, so what was worked out from it can tell it is stale. */
    var version = 0
        private set

    val size: Int get() = entries.size

    /** Every word, the latest added first. */
    fun all(): List<Entry> = entries.values.reversed()

    /**
     * Adds a word: its reading in hiragana, katakana or romaji ("tanaka"), and how it is written.
     * False when either won't do (a reading that isn't kana, an empty or over-long word, or one
     * with a tab or a new line), or when it is there already.
     */
    fun add(reading: String, surface: String): Boolean {
        val r = readingOf(reading) ?: return false
        val s = surface.trim()
        if (s.isEmpty() || s.length > MAX_WORD || s.any { it == '\t' || it == '\n' || it == '\r' }) return false
        if (entries.size >= MOST || entries.containsKey(key(r, s))) return false
        val e = Entry(r, s)
        entries[key(r, s)] = e
        byReading.getOrPut(r) { ArrayList(1) } += e
        lengths[r.length] = (lengths[r.length] ?: 0) + 1
        version++
        return true
    }

    fun remove(reading: String, surface: String) {
        val e = entries.remove(key(reading, surface)) ?: return
        byReading[reading]?.let { list ->
            list.remove(e)
            if (list.isEmpty()) byReading.remove(reading)
        }
        val n = (lengths[reading.length] ?: 1) - 1
        if (n <= 0) lengths.remove(reading.length) else lengths[reading.length] = n
        version++
    }

    /** The words read `reading`, the latest added first. */
    fun of(reading: String): List<Entry> = byReading[reading]?.reversed().orEmpty()

    /** Each reading of a word that `text` has at `from`, shortest first, as its length and its words. */
    fun at(text: String, from: Int): List<Pair<Int, List<Entry>>> {
        if (lengths.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Int, List<Entry>>>(1)
        for (n in lengths.keys) {
            if (from + n > text.length) break
            byReading[text.substring(from, from + n)]?.let { out += n to it.reversed() }
        }
        return out
    }

    /** Up to `n` words whose reading starts with `prefix` and goes on, shortest reading first. */
    fun startingWith(prefix: String, n: Int): List<Entry> {
        if (prefix.isEmpty() || entries.isEmpty()) return emptyList()
        return entries.values.filter { it.reading.length > prefix.length && it.reading.startsWith(prefix) }
            .sortedBy { it.reading.length }.take(n)
    }

    fun clear() {
        entries.clear()
        byReading.clear()
        lengths.clear()
        version++
    }

    /** Replaces what is held with the file's (none: empty); bad lines are skipped. */
    fun load(file: File) {
        clear()
        if (file.isFile) import(file.readText(Charsets.UTF_8))
    }

    /**
     * Adds each word of a file: a line per word, its reading, a tab and how it is written, any
     * columns after those ignored (Mozc's and Gboard's exports). Lines starting with # are
     * comments. How many it added.
     */
    fun import(text: String): Int {
        var added = 0
        for (line in text.lineSequence()) {
            if (line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size >= 2 && add(f[0], f[1])) added++
        }
        return added
    }

    /** The words as [import] takes them, the oldest first. */
    fun export(): String = buildString {
        append(HEADER).append('\n')
        for (e in entries.values) append(e.reading).append('\t').append(e.surface).append('\n')
    }

    /** Saves atomically (a temporary file, then rename); false when it couldn't (the old file stays). */
    fun save(file: File): Boolean {
        val tmp = File(file.path + ".tmp")
        return try {
            tmp.writeText(export(), Charsets.UTF_8)
            if (tmp.renameTo(file)) true else tmp.delete().let { false }
        } catch (e: java.io.IOException) {
            tmp.delete()
            false
        }
    }

    companion object {
        /** Words kept at most. */
        const val MOST = 5000
        private const val MAX_WORD = 50
        private const val HEADER = "# xrnkey japanese words 1"

        private fun key(reading: String, surface: String) = reading + '\t' + surface

        /**
         * A reading as the converter takes it, hiragana, from hiragana, katakana or romaji
         * ("Tanaka", "タナカ" and "たなか" are all たなか); null when it isn't one.
         */
        fun readingOf(raw: String): String? {
            val t = raw.trim().replace(" ", "")
            if (t.isEmpty() || t.length > Dictionary.MAX_READING) return null
            val kana = if (t.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '\'' }) {
                Romaji().also { r -> for (c in t.lowercase()) r.type(c) }.reading
            } else {
                buildString { for (c in t) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
            }
            return kana.takeIf { k -> k.isNotEmpty() && k.length <= Dictionary.MAX_READING && k.all { Dictionary.kanaCode(it) != 0 } }
        }
    }
}
