package com.xern.xrnkey.sync

import java.math.BigDecimal
import java.math.MathContext

/**
 * The personal words' file as text, in core/learn.cpp's format: a "word<TAB>count" line per word, a
 * "word word<TAB>count" line per pair (the word before, then the word; "<s>" before a sentence's
 * first word, "<p>" after a pause, "<num>" after a number), '#' lines ignored. The hub app's sync changes it through
 * [patch] without the engine, which has no call that sets a count; the engine reads the result as
 * it reads its own (bad lines skipped). No Android types here, so it is tested on the JVM.
 */
object WordsTable {
    const val HEADER = "# xrnkey: words and word pairs learned on this device"

    /** A patch line that deletes its key: "key<TAB>-". */
    const val DELETE = "-"

    private const val MAX_TOKEN = 64 // bytes, as the engine learns them
    private const val MAX_COUNT = 1e9
    private val MARKS = setOf("<s>", "<p>", "<num>")

    /** What a patch did: keys set, keys deleted that were there, lines it couldn't read. */
    data class Applied(val set: Int, val deleted: Int, val skipped: Int)

    /** Every key in the text with its count, as the engine would read them (the last of a repeat). */
    fun parse(text: String): LinkedHashMap<String, Double> {
        val out = LinkedHashMap<String, Double>()
        text.lineSequence().forEach { line -> entry(line)?.let { (k, c) -> if (c != null) out[k] = c } }
        return out
    }

    /**
     * [text] with [patch] applied: each "key<TAB>count" line sets that key, each "key<TAB>-" line
     * deletes it. Lines that aren't one of those, or name a key the engine wouldn't learn, are
     * skipped. The result is written as the engine writes it, words first, each part sorted.
     */
    fun patch(text: String, patch: String): Pair<String, Applied> {
        val table = parse(text)
        var set = 0
        var deleted = 0
        var skipped = 0
        patch.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            val (key, count) = entry(line, deletes = true) ?: run {
                skipped++
                return@forEach
            }
            if (count == null) {
                if (table.remove(key) != null) deleted++
            } else {
                table[key] = count
                set++
            }
        }
        return format(table) to Applied(set, deleted, skipped)
    }

    fun format(table: Map<String, Double>): String = buildString {
        append(HEADER).append('\n')
        val (words, pairs) = table.keys.partition { ' ' !in it }
        for (key in words.sorted() + pairs.sorted()) append(key).append('\t').append(number(table.getValue(key))).append('\n')
    }

    /** A count as the engine prints one (%.6g): 3, 2.7, 0.531441. */
    fun number(c: Double): String =
        BigDecimal(c).round(MathContext(6)).stripTrailingZeros().toPlainString()

    /** A key the engine learns: a word, or the word before and a word. */
    fun learnable(key: String): Boolean {
        if (key.isEmpty() || key.any { it < ' ' || it == '\u007f' }) return false
        val parts = key.split(' ')
        return when (parts.size) {
            1 -> token(parts[0])
            2 -> (parts[0] in MARKS || token(parts[0])) && token(parts[1])
            else -> false
        }
    }

    private fun token(t: String) = t.isNotEmpty() && !t.startsWith("<") && t.toByteArray(Charsets.UTF_8).size <= MAX_TOKEN

    /** (key, count) from a line; a null count is a deletion, read only where [deletes]. */
    private fun entry(raw: String, deletes: Boolean = false): Pair<String, Double?>? {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty() || line.startsWith("#")) return null
        val tab = line.lastIndexOf('\t')
        if (tab <= 0) return null
        val key = line.substring(0, tab)
        if (!learnable(key)) return null
        val value = line.substring(tab + 1)
        if (deletes && value == DELETE) return key to null
        val count = value.toDoubleOrNull() ?: return null
        if (!(count > 0.0 && count <= MAX_COUNT)) return null
        return key to count
    }
}
