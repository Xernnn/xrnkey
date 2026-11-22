package com.xern.xrnkey.engine

/** One emoji of the index: its text, its Unicode group, its name, whether it takes skin tones. */
data class Emoji(val text: String, val group: String, val name: String, val skinTones: Boolean)

/**
 * The emoji the strip offers for the words just typed: best first, whether they take the place
 * of the words ("cam" -> "🍊") or go after them ("ok 👌"), and how many words they stand for.
 */
data class EmojiKeyword(val emoji: List<String>, val replace: Boolean, val words: Int)

/**
 * The emoji index (data/emoji/emoji-index.tsv: Unicode's list with CLDR's English and
 * Vietnamese names and keywords), read by the core, with the strip's keyword table
 * (data/emoji/keywords.tsv).
 */
class EmojiIndex private constructor(private val handle: Long) : AutoCloseable {
    /** Every emoji, in Unicode's order. */
    val all: List<Emoji> by lazy {
        val skin = IntArray(1)
        List(Native.emojiCount(handle)) { i ->
            val e = Native.emojiAt(handle, i, skin)!!
            Emoji(String(e[0], Charsets.UTF_8), String(e[1], Charsets.UTF_8), String(e[2], Charsets.UTF_8), skin[0] != 0)
        }
    }

    /** Emoji whose Vietnamese or English name or keyword matches, with or without accents. */
    fun search(query: String, max: Int = 32): List<String> =
        Native.emojiSearch(handle, query.toByteArray(Charsets.UTF_8), max).map { String(it, Charsets.UTF_8) }

    /** Emoji for the words just typed (oldest first), from CLDR's keywords: the panel's row. */
    fun suggest(words: List<String>, max: Int = 8): List<String> =
        Native.emojiSuggest(handle, Array(words.size) { words[it].toByteArray(Charsets.UTF_8) }, max)
            .map { String(it, Charsets.UTF_8) }

    /**
     * The strip's emoji for the words before the cursor (oldest first, the last as it stands),
     * from the keyword table: a word only as it is written ("khóc", never "khoc"), a phrase also
     * typed without marks. Null when they are no keyword.
     */
    fun keyword(words: List<String>, max: Int = 3): EmojiKeyword? {
        val info = IntArray(2)
        val got = Native.emojiKeyword(handle, Array(words.size) { words[it].toByteArray(Charsets.UTF_8) }, max, info)
        if (got.isEmpty()) return null
        return EmojiKeyword(got.map { String(it, Charsets.UTF_8) }, info[0] != 0, info[1])
    }

    override fun close() = Native.emojiFree(handle)

    companion object {
        /** The index from its text, with the strip's keyword table when there is one. */
        fun load(text: ByteArray, keywords: ByteArray? = null): EmojiIndex? =
            Native.emojiLoad(text).takeIf { it != 0L }?.let(::EmojiIndex)?.also { index ->
                keywords?.let { Native.emojiLoadKeywords(index.handle, it) }
            }
    }
}

/** Skin tones: an emoji that takes them, in each of the five (base first). */
object SkinTones {
    val modifiers = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

    /** The base and its five toned forms: the modifier goes after the first character. */
    fun variants(base: String): List<String> {
        val first = base.offsetByCodePoints(0, 1)
        var rest = base.substring(first)
        if (rest.startsWith("️")) rest = rest.substring(1)
        return listOf(base) + modifiers.map { base.substring(0, first) + it + rest }
    }
}
