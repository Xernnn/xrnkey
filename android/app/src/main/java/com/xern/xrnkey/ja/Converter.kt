package com.xern.xrnkey.ja

/**
 * A candidate for the reading typed: its text, and how many characters of the reading it stands
 * for. A candidate for only the reading's start (`length` shorter than it) leaves the rest to be
 * converted after it, piece by piece; `rid` is the part of speech it ends with, which the rest
 * follows (0: as a sentence's start).
 */
data class Candidate(val text: String, val length: Int, val rid: Int = 0)

/**
 * What a reading may be converted to ([Converter.convert]):
 * - [best]: the cheapest way through the whole reading ("わたしはがくせいです": 私は学生です);
 * - [hiragana] and [katakana]: the reading as it is, in either;
 * - [words]: the words read exactly so ("かみ": 紙, 神, 髪 ...);
 * - [pieces]: words for its start: the first word of [best] and the others read like it, then
 *   the words of its other starts, longest first, for converting piece by piece;
 * - [predictions]: words whose reading starts with it and goes on ("にほ": 日本, 日本語).
 */
class Conversion(
    val reading: String,
    val left: Int,
    val best: Candidate,
    val hiragana: Candidate,
    val katakana: Candidate,
    val wide: Candidate?,
    val words: List<Candidate>,
    val pieces: List<Candidate>,
    val predictions: List<Candidate>,
) {
    /**
     * As the strip offers them: the best conversion, the hiragana, the katakana, then the words,
     * the pieces and the predictions, each once.
     */
    val offered: List<Candidate> by lazy {
        (listOf(best, hiragana, katakana) + listOfNotNull(wide) + words + pieces + predictions).distinctBy { it.text }
    }

    /**
     * As Space goes through them: the best conversion, the other words read so, the hiragana
     * and katakana, then the pieces. Predictions are not conversions.
     */
    val cycle: List<Candidate> by lazy {
        (listOf(best) + words + listOf(hiragana, katakana) + listOfNotNull(wide) + pieces).distinctBy { it.text }
    }
}

/**
 * Converts kana to kanji with the dictionary: a lattice of the words each part of the reading
 * may be, and the cheapest way through it (Viterbi), each word's cost plus the cost of its part
 * of speech after the one before (Mozc's connection costs). Text no word reads (Latin letters,
 * digits, punctuation, a kana no word has) stands for itself.
 */
class Converter(private val dict: Dictionary) {
    /** A segment of a conversion: the word, the part of the reading it stands for, how it ends. */
    data class Segment(val surface: String, val reading: String, val rid: Int)

    private class Node(val cost: Int, val rid: Int, val back: Node?, val surface: String, val start: Int, val end: Int)

    private var cached: Conversion? = null

    /**
     * The cheapest conversion of `reading`, segment by segment, after a word that ends with the
     * part of speech `left` (0: at a sentence's start).
     */
    fun best(reading: String, left: Int = 0): List<Segment> {
        if (reading.isEmpty()) return emptyList()
        val n = reading.length
        // The cheapest node ending at each position for each part of speech it ends with: the
        // cost of what follows depends only on that. In the order they came, so that of two as
        // cheap the first wins, as in build_dict.py's convert() (Mozc's evaluation set: 505, not 503).
        val ends = Array(n + 1) { LinkedHashMap<Int, Node>() }
        ends[0][left] = Node(0, left, null, "", 0, 0)
        for (i in 0 until n) {
            val before = ends[i].values
            if (before.isEmpty()) continue
            val bestFor = HashMap<Int, Node>()
            fun from(lid: Int): Node = bestFor.getOrPut(lid) {
                before.minBy { it.cost + dict.connection(it.rid, lid) }
            }
            fun add(length: Int, surface: String, lid: Int, rid: Int, cost: Int) {
                val prev = from(lid)
                val total = prev.cost + dict.connection(prev.rid, lid) + cost
                val at = ends[i + length]
                val old = at[rid]
                if (old == null || total < old.cost) at[rid] = Node(total, rid, prev, surface, i, i + length)
            }
            if (Dictionary.kanaCode(reading[i]) != 0) {
                for ((length, words) in dict.prefixes(reading, i)) {
                    for (w in words) add(length, w.surface, w.lid, w.rid, w.cost)
                }
                // A kana no word starts with stands for itself, dearly.
                val noun = dict.literal(Literal.NOUN)
                add(1, reading[i].toString(), noun, noun, UNKNOWN_KANA)
            } else {
                val kind = literalKind(reading[i])
                var length = 1
                if (kind == Literal.NUMBER || kind == Literal.ALPHABET) {
                    while (i + length < n && literalKind(reading[i + length]) == kind) length++
                }
                val id = dict.literal(kind)
                add(length, reading.substring(i, i + length), id, id, 0)
            }
        }
        val out = ArrayList<Segment>()
        var node: Node? = ends[n].values.minBy { it.cost + dict.connection(it.rid, 0) }
        while (node?.back != null) {
            out += Segment(node.surface, reading.substring(node.start, node.end), node.rid)
            node = node.back
        }
        return out.asReversed()
    }

    /** The cheapest conversion of `reading` after `left`, as one text. */
    fun bestText(reading: String, left: Int = 0): String = best(reading, left).joinToString("") { it.surface }

    /** Every candidate for `reading` after `left` (the last one asked for is kept). */
    fun convert(reading: String, left: Int = 0): Conversion {
        cached?.let { if (it.reading == reading && it.left == left) return it }
        val n = reading.length
        val segments = best(reading, left)
        val kana = reading.all { Dictionary.kanaCode(it) != 0 }
        val words = if (kana) dict.lookup(reading).map { it.surface }.distinct().map { Candidate(it, n) } else emptyList()
        val pieces = ArrayList<Candidate>()
        if (segments.size > 1) {
            val first = segments[0]
            pieces += Candidate(first.surface, first.reading.length, first.rid)
            dict.lookup(first.reading).mapTo(pieces) { Candidate(it.surface, first.reading.length, it.rid) }
        }
        // The other starts that are words, longest first, a few words each.
        val starts = dict.prefixes(reading, 0).filter { it.first < n && it.first != segments.firstOrNull()?.reading?.length }
        for ((length, ws) in starts.sortedByDescending { it.first }) {
            ws.distinctBy { it.surface }.take(PER_START).mapTo(pieces) { Candidate(it.surface, length, it.rid) }
        }
        val predictions = if (kana) {
            dict.predict(reading, PREDICTIONS).map { it.surface }.distinct().map { Candidate(it, n) }
        } else {
            emptyList()
        }
        val wide = wide(reading).takeIf { it != reading }?.let { Candidate(it, n) }
        return Conversion(
            reading,
            left,
            Candidate(segments.joinToString("") { it.surface }, n),
            Candidate(reading, n),
            Candidate(Dictionary.katakana(reading), n),
            wide,
            words,
            pieces.distinctBy { it.text to it.length }.take(PIECES),
            predictions,
        ).also { cached = it }
    }

    companion object {
        /** What a kana that no word reads costs, standing for itself: more than any word. */
        const val UNKNOWN_KANA = 12000

        private const val PER_START = 3
        private const val PIECES = 12
        private const val PREDICTIONS = 12

        fun literalKind(c: Char): Literal = when {
            c.isDigit() -> Literal.NUMBER
            c in 'a'..'z' || c in 'A'..'Z' -> Literal.ALPHABET
            c in "、,，" -> Literal.COMMA
            c in "。.．！？!?" -> Literal.PERIOD
            c.isLetter() -> Literal.NOUN
            else -> Literal.SYMBOL
        }

        /** Latin letters and digits full-width ("Tokyo": Ｔｏｋｙｏ). */
        fun wide(s: String): String = buildString(s.length) {
            for (c in s) append(if (c in '!'..'~' && (c.isLetterOrDigit())) c + 0xFEE0 else c)
        }
    }
}
