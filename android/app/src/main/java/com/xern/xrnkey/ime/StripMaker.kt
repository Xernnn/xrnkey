package com.xern.xrnkey.ime

import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.keyboard.Strip

/**
 * What the strip shows for the word in progress, as Laban Key lays it out: an alternative on
 * the left, the best accented reading in the middle, and on the right the word with the word
 * likeliest to follow it ("ông bà", "không có") or an emoji that goes with it. The middle is in
 * bold only when Space will commit it; by default Space keeps what was typed, and a tap takes
 * the reading. When the words before were typed without marks too, the left offers the whole
 * run accented ("tôi không biết"). A word typed with Telex marks shows as it is. With no word,
 * the next words, or the tool row.
 */
class StripMaker(private val models: Models?, private val emoji: EmojiIndex? = null) {
    /** What a slot holds: words, an emoji to put after the word in progress, or a phrase. */
    data class Pick(val text: String, val emoji: Boolean = false, val phrase: Pair<String, String>? = null)

    private var offered: List<Pick?> = emptyList()

    /** The middle slot's word, while one is in progress (for an emoji pick). */
    var centre: String? = null
        private set

    var emojiSuggestions = true

    /** Candidates at all (the settings' "suggestions"); off, the strip keeps its tools. */
    var suggestions = true

    /** The next word after a Space. */
    var nextWords = true

    fun make(t: Typist): Strip {
        val word = t.shown
        if (!suggestions || word.isEmpty()) {
            offered = emptyList()
            centre = null
            return if (suggestions && nextWords && models != null && t.afterWord()) next(t) else Strip.Tools
        }
        val commits = t.preview()  // what Space commits, when it isn't what was typed
        val phrase = if (models != null) t.phrase() else null
        // After words typed without marks, the word reads as the end of their run: the run
        // itself, typed bare, would read on bare ("toi khong" + "biet" as "biet").
        val reading = phrase?.second?.substringAfterLast(' ')
            ?: if (models != null && t.restorable()) t.reading(lookBack = t.tonesOnSpace == Typist.TONES_ALWAYS) else null
        val c = commits ?: reading ?: word
        centre = c
        val bold = c == (commits ?: word)
        val others = if (models != null && t.restorable()) {
            models.restoreWord(t.context(), t.keys(), 6).map { it.text }.filter { it != c && it != word }
        } else {
            emptyList()
        }
        val left = phrase?.let { Pick(it.second, phrase = it) } ?: others.getOrNull(0)?.let { Pick(it) }
        val right = completion(t, c)?.let { Pick(it) } ?: emojiFor(t, c)?.let { Pick(it, emoji = true) }
            ?: others.getOrNull(if (phrase == null) 1 else 0)?.let { Pick(it) }
        offered = listOf(left, Pick(c), right)
        return Strip.Candidates(left?.text, c, right?.text, more = others.size > 2, bold = bold)
    }

    /** The word with the word likeliest to follow it, when that one is likely enough. */
    private fun completion(t: Typist, word: String): String? {
        val m = models ?: return null
        if (!word.first().isLetter()) return null
        val (next, score) = m.predictScored(t.context() + word.lowercase(), 1).firstOrNull() ?: return null
        return if (score >= COMPLETION) "$word $next" else null
    }

    /** After a word and a space: the likeliest next words, the likeliest in the middle. */
    private fun next(t: Typist): Strip {
        val words = models!!.predict(t.context(), 3)
        if (words.isEmpty()) return Strip.Tools
        offered = listOf(words.getOrNull(1), words[0], words.getOrNull(2)).map { w -> w?.let { Pick(it) } }
        return Strip.Candidates(words.getOrNull(1), words[0], words.getOrNull(2), bold = false)
    }

    private fun emojiFor(t: Typist, word: String): String? {
        val index = emoji ?: return null
        if (!emojiSuggestions) return null
        val words = t.context().filter { it != "<s>" } + word
        return index.suggest(words, 1).firstOrNull()
    }

    /** The candidate in slot 0 (left), 1 (centre) or 2 (right). */
    fun pick(slot: Int): Pick? = offered.getOrNull(slot)

    companion object {
        /** log10: how likely the next word must be to complete the word in the right slot. */
        const val COMPLETION = -1.0f
    }
}
