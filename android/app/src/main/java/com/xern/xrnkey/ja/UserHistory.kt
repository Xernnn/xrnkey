package com.xern.xrnkey.ja

import java.io.File
import java.util.TreeMap

/**
 * What the owner chose on the Japanese keyboard, kept on the phone only: each word they took for a
 * reading (by Space or by a tap), and each pair of a word and the one they took after it. Words
 * and pairs count how often they were chosen and remember when; the converter ranks a chosen
 * word first for its reading, makes it cheaper inside longer readings, predicts it from the
 * start of its reading, and offers it again after the word it followed.
 *
 * Bounded: at most [most] words and as many pairs, the longest unused going first. The parts of
 * speech are the dictionary's ([stamp] says which build); [Converter] looks them up again when the
 * dictionary is another. No Android types here, so the JVM tests run it as the keyboard does.
 */
class UserHistory(private val most: Int = MOST) {
    /** A chosen word: how it is read and written, its parts of speech, how often, and when last. */
    class Entry(val reading: String, val surface: String, lid: Int, rid: Int, count: Int, last: Long) {
        var lid = lid
            internal set
        var rid = rid
            internal set
        var count = count
            internal set
        var last = last
            internal set
    }

    // Oldest first: a word chosen again moves to the end, and the first goes when there are too many.
    private val words = LinkedHashMap<String, Entry>()
    private val byReading = TreeMap<String, MutableList<Entry>>()
    private val pairs = LinkedHashMap<String, Entry>()
    private val byPrev = HashMap<String, MutableList<Entry>>()
    private var tick = 0L

    /** The dictionary build the parts of speech are from ("" when not known). */
    var stamp = ""

    /** Changes since the last load or save. */
    var unsaved = 0
        private set

    /** Goes up with every change, so what was worked out from it can tell it is stale. */
    var version = 0
        private set

    /** How many words it holds. */
    val size: Int get() = words.size

    /**
     * The segments of one commit were chosen, in order, right after `prev` (null: after nothing
     * that counts, such as the start of the field or punctuation). Each is learned, and so is each
     * pair of a segment and the one before it.
     */
    fun learn(prev: String?, chosen: List<Converter.Segment>) {
        var before = prev
        for (s in chosen) {
            if (!learnable(s)) {
                before = null
                continue
            }
            val e = touch(words, key(s.reading, s.surface), s)
            index(byReading, s.reading, e)
            if (before != null) index(byPrev, before, touch(pairs, before + SEP + key(s.reading, s.surface), s))
            before = s.surface
        }
        trim()
    }

    private fun touch(map: LinkedHashMap<String, Entry>, key: String, s: Converter.Segment): Entry {
        val e = map.remove(key)?.also {
            it.count++
            it.lid = s.lid
            it.rid = s.rid
        } ?: Entry(s.reading, s.surface, s.lid, s.rid, 1, 0)
        e.last = ++tick
        map[key] = e
        unsaved++
        version++
        return e
    }

    private fun index(map: MutableMap<String, MutableList<Entry>>, key: String, e: Entry) {
        val list = map.getOrPut(key) { ArrayList(2) }
        if (list.none { it === e }) list += e
    }

    private fun trim() {
        while (words.size > most) {
            val (k, e) = words.entries.first()
            words.remove(k)
            unindex(byReading, e.reading, e)
        }
        while (pairs.size > most) {
            val k = pairs.keys.first()
            val e = pairs.remove(k)!!
            unindex(byPrev, k.substringBefore(SEP), e)
        }
    }

    private fun unindex(map: MutableMap<String, MutableList<Entry>>, key: String, e: Entry) {
        val list = map[key] ?: return
        list.removeAll { it === e }
        if (list.isEmpty()) map.remove(key)
    }

    /** The words chosen for `reading`, the latest first. */
    fun of(reading: String): List<Entry> = byReading[reading]?.sortedByDescending { it.last }.orEmpty()

    /** Whether any word was chosen for `reading`. */
    fun has(reading: String): Boolean = byReading.containsKey(reading)

    /** Up to `n` chosen words whose reading starts with `prefix` and goes on, the latest first. */
    fun startingWith(prefix: String, n: Int): List<Entry> {
        if (prefix.isEmpty() || n <= 0) return emptyList()
        val found = ArrayList<Entry>()
        for ((reading, list) in byReading.subMap(prefix, false, prefix + '￿', false)) {
            if (reading.length > prefix.length) found += list
        }
        return found.sortedByDescending { it.last }.take(n)
    }

    /** Up to `n` words chosen right after `prev`, the latest first. */
    fun after(prev: String, n: Int): List<Entry> = byPrev[prev]?.sortedByDescending { it.last }?.take(n).orEmpty()

    /** Every word, the latest first (for the settings' list). */
    fun all(): List<Entry> = words.values.reversed()

    /** Forgets a word, and every pair it is in. */
    fun forget(reading: String, surface: String) {
        val e = words.remove(key(reading, surface)) ?: return
        unindex(byReading, reading, e)
        val gone = pairs.entries.filter { (k, p) ->
            (p.reading == reading && p.surface == surface) || k.substringBefore(SEP) == surface
        }
        for ((k, p) in gone) {
            pairs.remove(k)
            unindex(byPrev, k.substringBefore(SEP), p)
        }
        unsaved++
        version++
    }

    fun clear() {
        words.clear()
        byReading.clear()
        pairs.clear()
        byPrev.clear()
        unsaved++
        version++
    }

    /** Every word and pair, for [Converter] to give them parts of speech from another dictionary. */
    internal fun entries(): Sequence<Entry> = words.values.asSequence() + pairs.values.asSequence()

    /** The parts of speech changed (another dictionary): what was worked out from them is stale. */
    internal fun changed() {
        version++
    }

    /**
     * Replaces what is held with the file's (none: empty). Bad lines are skipped; the file keeps
     * the oldest first, so the order is when each was last chosen.
     */
    fun load(file: File) {
        clear()
        unsaved = 0
        stamp = ""
        if (!file.isFile) return
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.startsWith(HEADER)) {
                stamp = line.removePrefix(HEADER).trim()
                return@forEachLine
            }
            val f = line.split('\t')
            val n = f.size
            if (n < 6) return@forEachLine
            val lid = f[n - 3].toIntOrNull() ?: return@forEachLine
            val rid = f[n - 2].toIntOrNull() ?: return@forEachLine
            val count = f[n - 1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEachLine
            when {
                f[0] == "w" && n == 6 && f[1].isNotEmpty() && f[2].isNotEmpty() -> {
                    val e = Entry(f[1], f[2], lid, rid, count, ++tick)
                    words.remove(key(e.reading, e.surface))
                    words[key(e.reading, e.surface)] = e
                    index(byReading, e.reading, e)
                }
                f[0] == "p" && n == 7 && f[1].isNotEmpty() && f[2].isNotEmpty() && f[3].isNotEmpty() -> {
                    val e = Entry(f[2], f[3], lid, rid, count, ++tick)
                    pairs.remove(f[1] + SEP + key(e.reading, e.surface))
                    pairs[f[1] + SEP + key(e.reading, e.surface)] = e
                    index(byPrev, f[1], e)
                }
            }
        }
        trim()
        unsaved = 0
        version++
    }

    /** Saves atomically (a temporary file, then rename); false when it couldn't (the old file stays). */
    fun save(file: File): Boolean {
        val tmp = File(file.path + ".tmp")
        return try {
            tmp.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("$HEADER $stamp\n")
                for (e in words.values) out.write("w\t${e.reading}\t${e.surface}\t${e.lid}\t${e.rid}\t${e.count}\n")
                for ((k, e) in pairs) out.write("p\t${k.substringBefore(SEP)}\t${e.reading}\t${e.surface}\t${e.lid}\t${e.rid}\t${e.count}\n")
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            unsaved = 0
            true
        } catch (e: java.io.IOException) {
            tmp.delete()
            false
        }
    }

    companion object {
        /** Words kept, and as many pairs: a few thousand. */
        const val MOST = 3000

        private const val HEADER = "# xrnkey japanese history 1"
        private const val SEP = '\u0001'

        private fun key(reading: String, surface: String) = reading + '\t' + surface

        /**
         * A word is learned only as a choice among words read alike: kana read as a word, not Latin
         * letters, digits or punctuation, nor a kana no word has standing for itself. No tabs or
         * new lines, which the file can't hold.
         */
        private fun learnable(s: Converter.Segment): Boolean =
            s.word && s.reading.isNotEmpty() && s.surface.isNotEmpty() &&
                s.reading.all { Dictionary.kanaCode(it) != 0 } && s.surface.none { it == '\t' || it == '\n' || it == '\r' }
    }
}
