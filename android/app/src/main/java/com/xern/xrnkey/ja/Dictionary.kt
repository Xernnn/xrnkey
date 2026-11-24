package com.xern.xrnkey.ja

import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * A word of the dictionary: how it is read (hiragana), how it is written, its part of speech
 * (Mozc's left and right ids, renumbered by the build) and its cost (lower is likelier).
 */
class Word(val reading: String, val surface: String, val lid: Int, val rid: Int, val cost: Int)

/**
 * The Japanese dictionary (ja.xkdic, built by data/ja/build_dict.py, which describes the format):
 * readings in kana order, each with its words, and the cost of each part of speech after another.
 * It is read in place from a mapped buffer, as the models are, so only the pages in use take RAM.
 * No Android types here, so the JVM tests run it as the keyboard does.
 */
class Dictionary private constructor(private val b: ByteBuffer) {
    private val readings: Int
    private val blockCount: Int
    private val blocks: Int
    private val index: Int
    private val chars: CharArray
    private val posLeft: IntArray
    private val posRight: IntArray

    /** How many parts of speech the connection costs cover; 0 starts and ends a sentence. */
    val parts: Int
    private val defaults: Int
    private val rowStart: Int
    private val ranks: Int
    private val masks: Int
    private val values: Int
    private val columns: Int
    private val predicted: Int
    private val predictions: Int

    /** The part of speech of text no word stands for, by [Literal]. */
    private val literals: IntArray

    /** How a word of each part of speech joins a phrase ([Kind]), or null: a build without them. */
    private val kinds: ByteArray?

    init {
        b.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { for (i in 0 until 4) it[i] = b.get(i) }
        require(String(magic, Charsets.US_ASCII) == "XKJA" && b.getInt(4) == VERSION && b.getInt(8) == b.capacity()) {
            "not a version $VERSION Japanese dictionary"
        }
        readings = b.getInt(12)
        blockCount = b.getInt(16)
        blocks = b.getInt(20)
        index = b.getInt(24)
        val charCount = b.getInt(28)
        val charsAt = b.getInt(32)
        chars = CharArray(charCount) { b.getShort(charsAt + 2 * it).toInt().toChar() }
        val posCount = b.getInt(36)
        val posAt = b.getInt(40)
        posLeft = IntArray(posCount) { b.getShort(posAt + 4 * it).toInt() and 0xFFFF }
        posRight = IntArray(posCount) { b.getShort(posAt + 4 * it + 2).toInt() and 0xFFFF }
        parts = b.getInt(44)
        defaults = b.getInt(48)
        columns = (parts + 63) / 64
        rowStart = align(defaults + parts, 4)
        ranks = rowStart + 4 * parts
        masks = align(ranks + 2 * parts * columns, 8)
        values = masks + 8 * parts * columns
        predicted = b.getInt(52)
        predictions = b.getInt(56)
        val literalsAt = b.getInt(60)
        literals = IntArray(Literal.entries.size) { b.getShort(literalsAt + 2 * it).toInt() and 0xFFFF }
        // After the literals, where the build wrote them: a byte per part of speech.
        val kindsAt = literalsAt + 2 * Literal.entries.size
        kinds = if (kindsAt + parts <= b.capacity()) ByteArray(parts) { b.get(kindsAt + it) } else null
    }

    /** Whether this build says how each part of speech joins a phrase ([kind]). */
    val hasKinds: Boolean get() = kinds != null

    /** How a word whose part of speech is `id` joins a phrase (a word, when the build doesn't say). */
    fun kind(id: Int): Kind = kinds?.getOrNull(id)?.toInt()?.let { Kind.entries.getOrNull(it) } ?: Kind.WORD

    /** The cost of a word whose part of speech starts `right` after one that ends `left`. */
    fun connection(left: Int, right: Int): Int {
        val i = left * columns + (right ushr 6)
        val mask = b.getLong(masks + 8 * i)
        val bit = right and 63
        if ((mask ushr bit) and 1L == 0L) return (b.get(defaults + left).toInt() and 0xFF) * STEP
        val before = b.getShort(ranks + 2 * i).toInt() and 0xFFFF
        val j = b.getInt(rowStart + 4 * left) + before + java.lang.Long.bitCount(mask and ((1L shl bit) - 1))
        return (b.get(values + j).toInt() and 0xFF) * STEP
    }

    /**
     * Which build this is, near enough: its readings, parts of speech and size. The owner's
     * history keeps it with the parts of speech it learned, which another build numbers otherwise.
     */
    val stamp: String get() = "$readings.$parts.${b.capacity()}"

    /** The part of speech for text of this kind. */
    fun literal(kind: Literal): Int = literals[kind.ordinal]

    /** What a word costs alone: as a whole sentence, from its start to its end. */
    fun alone(w: Word): Int = connection(0, w.lid) + w.cost + connection(w.rid, 0)

    /** The words read `reading`, cheapest alone first. */
    fun lookup(reading: String): List<Word> {
        val code = code(reading, 0, reading.length) ?: return emptyList()
        val c = Cursor()
        if (!seek(code, c) || !c.equals(code)) return emptyList()
        return words(c)
    }

    /**
     * Each reading that `text` starts with from `from` (kana only, up to `max` of them), as its
     * length and its words: the words a conversion may start there with.
     */
    fun prefixes(text: String, from: Int, max: Int = MAX_READING): List<Pair<Int, List<Word>>> {
        val out = ArrayList<Pair<Int, List<Word>>>()
        var end = from
        while (end < text.length && end - from < max && kanaCode(text[end]) != 0) end++
        if (end == from) return out
        val code = code(text, from, end)!!
        val c = Cursor()
        for (n in 1..code.size) {
            val part = code.copyOf(n)
            // No reading starts so: none of the longer ones is a reading either.
            if (!seek(part, c) || !c.startsWith(part)) break
            if (c.length == n) out += n to words(c)
        }
        return out
    }

    /**
     * The words whose reading starts with `prefix` and is longer, cheapest alone first: what the
     * reading typed so far may become ("にほ": 日本, 日本語).
     */
    fun predict(prefix: String, n: Int): List<Word> {
        val code = code(prefix, 0, prefix.length) ?: return emptyList()
        stored(code)?.let { return it.take(n) }
        val found = ArrayList<Pair<Int, Word>>()
        val c = Cursor()
        if (!seek(code, c)) return emptyList()
        while (c.startsWith(code)) {
            if (c.length > code.size) {
                for (w in words(c).take(PER_READING)) found += alone(w) * 64 + c.length to w
            }
            if (!c.next()) break
        }
        return found.sortedBy { it.first }.map { it.second }.take(n)
    }

    /** The predictions stored for a prefix with many readings, or null. */
    private fun stored(code: ByteArray): List<Word>? {
        var lo = 0
        var hi = predicted
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareAt(predictions + 4 * predicted + b.getInt(predictions + 4 * mid), code) < 0) lo = mid + 1 else hi = mid
        }
        if (lo >= predicted) return null
        var at = predictions + 4 * predicted + b.getInt(predictions + 4 * lo)
        if (compareAt(at, code) != 0) return null
        at += 1 + (b.get(at).toInt() and 0xFF)
        val count = b.get(at).toInt() and 0xFF
        at++
        val c = Cursor()
        return List(count) {
            val reading = b.getInt(at)
            val word = b.get(at + 4).toInt() and 0xFF
            at += 5
            c.at(reading)
            words(c)[word]
        }
    }

    /** A stored prefix (its length, then its codes) at `at` against `code`. */
    private fun compareAt(at: Int, code: ByteArray): Int {
        val n = b.get(at).toInt() and 0xFF
        for (i in 0 until minOf(n, code.size)) {
            val d = (b.get(at + 1 + i).toInt() and 0xFF) - code[i]
            if (d != 0) return d
        }
        return n - code.size
    }

    /** Puts `c` on the first reading not before `code`; false when there is none. */
    private fun seek(code: ByteArray, c: Cursor): Boolean {
        var lo = 0
        var hi = blockCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            c.block(mid)
            if (c.compareTo(code) <= 0) lo = mid + 1 else hi = mid
        }
        c.block(maxOf(0, lo - 1))
        while (c.compareTo(code) < 0) if (!c.next()) return false
        return true
    }

    private fun words(c: Cursor): List<Word> {
        val reading = c.reading()
        val out = ArrayList<Word>()
        var at = c.words
        val sb = StringBuilder()
        while (at < c.end) {
            var pos = 0
            var shift = 0
            while (true) {
                val x = b.get(at++).toInt() and 0xFF
                pos = pos or ((x and 0x7F) shl shift)
                if (x < 0x80) break
                shift += 7
            }
            val cost = (b.get(at++).toInt() and 0xFF) * STEP
            val h = b.get(at++).toInt() and 0xFF
            val surface = when (h) {
                0 -> reading
                1 -> katakana(reading)
                else -> {
                    var keep: Int
                    var n: Int
                    if (h == 255) {
                        val (k, a1) = varint(at)
                        val (m, a2) = varint(a1)
                        keep = k
                        n = m
                        at = a2
                    } else {
                        keep = (h - 2) / 30
                        n = (h - 2) % 30 + 1
                    }
                    sb.setLength(0)
                    repeat(n) {
                        var ch = b.get(at++).toInt() and 0xFF
                        if (ch >= 0x80) ch = ((ch and 0x7F) shl 8) or (b.get(at++).toInt() and 0xFF)
                        sb.append(chars[ch])
                    }
                    if (keep > 0) sb.append(reading, reading.length - keep, reading.length)
                    sb.toString()
                }
            }
            out += Word(reading, surface, posLeft[pos], posRight[pos], cost)
        }
        return out
    }

    private fun varint(from: Int): Pair<Int, Int> {
        var at = from
        var n = 0
        var shift = 0
        while (true) {
            val x = b.get(at++).toInt() and 0xFF
            n = n or ((x and 0x7F) shl shift)
            if (x < 0x80) return n to at
            shift += 7
        }
    }

    /**
     * A place among the readings: which one, its kana codes, and where its words are. Moves
     * forward within the blocks, decoding each reading from the one before.
     */
    private inner class Cursor {
        var reading = -1
        val code = ByteArray(256)
        var length = 0
        var words = 0
        var end = 0
        private var next = 0
        private var blockEnd = 0

        fun block(i: Int) {
            next = blocks + b.getInt(index + 4 * i)
            blockEnd = blocks + b.getInt(index + 4 * (i + 1))
            reading = i * BLOCK - 1
            length = 0
            read()
        }

        fun at(i: Int) {
            block(i / BLOCK)
            while (reading < i) next()
        }

        /** The next reading; false past the last. */
        fun next(): Boolean {
            if (next >= blockEnd) {
                // The block after this reading's (the last block may hold fewer than BLOCK).
                val nextBlock = reading / BLOCK + 1
                if (nextBlock >= blockCount) return false
                block(nextBlock)
                return true
            }
            read()
            return true
        }

        private fun read() {
            var at = next
            val h = b.get(at++).toInt() and 0xFF
            val shared: Int
            val rest: Int
            if (h == 0xFF) {
                val (s, a1) = varint(at)
                val (r, a2) = varint(a1)
                shared = s
                rest = r
                at = a2
            } else {
                shared = h ushr 4
                rest = h and 15
            }
            for (i in 0 until rest) code[shared + i] = b.get(at + i)
            length = shared + rest
            at += rest
            var size = 0
            var shift = 0
            while (true) {
                val x = b.get(at++).toInt() and 0xFF
                size = size or ((x and 0x7F) shl shift)
                if (x < 0x80) break
                shift += 7
            }
            words = at
            end = at + size
            next = end
            reading++
        }

        fun compareTo(other: ByteArray): Int {
            for (i in 0 until minOf(length, other.size)) {
                val d = (code[i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return length - other.size
        }

        fun equals(other: ByteArray): Boolean = length == other.size && compareTo(other) == 0

        fun startsWith(prefix: ByteArray): Boolean {
            if (length < prefix.size) return false
            for (i in prefix.indices) if (code[i] != prefix[i]) return false
            return true
        }

        fun reading(): String = String(CharArray(length) { kanaOf(code[it].toInt() and 0xFF) })
    }

    companion object {
        const val VERSION = 1
        private const val BLOCK = 16
        private const val STEP = 64

        /** The longest reading a word has, near enough: conversions look no further. */
        const val MAX_READING = 24

        /** Words of each reading a prediction looks at (the rest are its rarer forms). */
        private const val PER_READING = 4

        private fun align(n: Int, a: Int) = (n + a - 1) / a * a

        /** A kana's code in the dictionary (ぁ..ゖ are 1..86, ー 87), 0 for anything else. */
        fun kanaCode(ch: Char): Int = when (ch) {
            in 'ぁ'..'ゖ' -> ch.code - 0x3040
            'ー' -> 87
            else -> 0
        }

        private fun kanaOf(code: Int): Char = if (code == 87) 'ー' else (code + 0x3040).toChar()

        private fun code(s: String, from: Int, to: Int): ByteArray? {
            val out = ByteArray(to - from)
            for (i in from until to) {
                val c = kanaCode(s[i])
                if (c == 0) return null
                out[i - from] = c.toByte()
            }
            return out
        }

        /** Hiragana as katakana; everything else as it is. */
        fun katakana(s: String): String = buildString(s.length) {
            for (ch in s) append(if (ch in 'ぁ'..'ゖ') ch + 0x60 else ch)
        }

        /** A dictionary in a byte buffer (a file read whole, in the tests). */
        fun of(bytes: ByteArray): Dictionary = Dictionary(ByteBuffer.wrap(bytes))

        /** A dictionary file, mapped. */
        fun open(file: File): Dictionary = RandomAccessFile(file, "r").use { f ->
            Dictionary(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()))
        }

        /**
         * A dictionary inside a larger file, as an uncompressed asset is inside the APK: mapped
         * where it lies. The mapping stays valid once the descriptor is closed.
         */
        fun open(fd: FileDescriptor, offset: Long, length: Long): Dictionary =
            // Not closed: the descriptor is its owner's (the asset's) to close.
            Dictionary(FileInputStream(fd).channel.map(FileChannel.MapMode.READ_ONLY, offset, length))
    }
}

/** What text no word stands for is, for its part of speech in a conversion. */
enum class Literal { NOUN, NUMBER, ALPHABET, COMMA, PERIOD, SYMBOL }

/**
 * How a word of a part of speech joins a phrase (a bunsetsu: a word and what goes with it, as a
 * conversion is split to be changed piece by piece), as build_dict.py's KINDS: a [WORD] starts
 * one; [ATTACH] (particles, auxiliaries, suffixes, dependent words, punctuation) goes with the
 * word before; a [PREFIX] takes the word after; [SURU] (a form of する) goes with a [SAHEN] noun
 * before it (勉強 + します), else starts one.
 */
enum class Kind { WORD, ATTACH, PREFIX, SURU, SAHEN }
