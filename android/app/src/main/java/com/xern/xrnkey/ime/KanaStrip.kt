package com.xern.xrnkey.ime

import com.xern.xrnkey.ja.Candidate
import com.xern.xrnkey.ja.JapaneseEmoji
import com.xern.xrnkey.keyboard.Strip

/**
 * The strip of the Japanese keyboard, as Gboard's:
 * - while a reading is composing: what Space would give in the middle, in bold, and the next
 *   likeliest on the left and the right, predictions included ("ありが": ありがとう); the more panel
 *   holds the others, then the hiragana and katakana, then the pieces for converting piece by
 *   piece ([com.xern.xrnkey.ja.Conversion.offered]). A reading that is an emoji keyword, or
 *   converts to one ([JapaneseEmoji]: ねこ 🐱, わらい 😂), has its first emoji on the right, the
 *   likeliest third going to the panel, and the others in the panel's second row; a keyword
 *   that is only an association (本 📕) has them all there. A tap puts the emoji in place of the
 *   reading;
 * - after Space: the focused phrase's candidate the field shows in the middle, highlighted, with
 *   the one before it on the left and the next on the right; the panel holds the others. Its
 *   sides are ⇤ and ⇥, which shrink and stretch the focused phrase;
 * - with nothing composing, right after a word went in: what may follow it ("私": は, の, が), the
 *   likeliest in the middle, not bold (Space doesn't take it). With nothing to offer, the tool row.
 */
class KanaStrip {
    private var slots: List<Candidate?> = emptyList()
    private var extra: List<Candidate> = emptyList()

    /** The next words are offered after a word (the settings' "Next word"). */
    var nextWords = true

    /** The emoji for a reading (null: none), and whether they are offered (the settings' "Emoji"). */
    var emoji: JapaneseEmoji? = null
    var emojiSuggestions = true

    /** The emoji the strip offers now, as candidates (a pick of one is no word to learn). */
    private var emojiShown: Set<String> = emptySet()

    fun make(k: KanaTypist): Strip {
        emojiShown = emptySet()
        val conv = k.conversion()
        if (k.shown.isEmpty() || conv == null) {
            val next = if (nextWords && k.shown.isEmpty()) k.nextWords() else emptyList()
            if (next.isEmpty()) {
                slots = emptyList()
                extra = emptyList()
                return Strip.Tools
            }
            slots = listOf(next.getOrNull(1), next[0], next.getOrNull(2))
            extra = next.drop(3)
            return candidates(highlight = false, bold = false)
        }
        if (k.converting) {
            val c = k.cycle
            val i = k.selected
            slots = listOf(c.getOrNull(i - 1), c[i], c.getOrNull(i + 1))
            val shown = slots.mapNotNull { it?.text }.toSet()
            // The predictions are the whole reading's: offered while it is one phrase.
            val predictions = if (k.phrases.size == 1) conv.predictions else emptyList()
            extra = (c.filterIndexed { j, _ -> j !in i - 1..i + 1 } + predictions).filter { it.text !in shown }.distinctBy { it.text }
            return candidates(highlight = true, resize = true)
        }
        val all = conv.offered
        slots = listOf(all.getOrNull(1), all[0], all.getOrNull(2))
        extra = all.drop(3)
        val hit = if (emojiSuggestions) emoji?.of(conv.reading, conv.best.text) else null
        if (hit != null) {
            val pics = hit.emoji.map { Candidate(it, conv.reading.length) }
            var rest = pics
            if (hit.strong) {
                extra = listOfNotNull(slots[2]) + extra
                slots = listOf(slots[0], slots[1], pics[0])
                rest = pics.drop(1)
            }
            extra = extra.take(PANEL_ROW) + rest + extra.drop(PANEL_ROW)
            emojiShown = hit.emoji.toSet()
        }
        return candidates(highlight = false)
    }

    private fun candidates(highlight: Boolean, bold: Boolean = true, resize: Boolean = false) = Strip.Candidates(
        slots[0]?.text, slots[1]?.text, slots[2]?.text,
        more = extra.isNotEmpty(), bold = bold, extra = extra.map { it.text }.take(MOST), highlight = highlight,
        most = MOST, removable = false, resize = resize,
    )

    /** The candidate in slot 0 (left), 1 (centre) or 2 (right). */
    fun pick(slot: Int): Candidate? = slots.getOrNull(slot)

    /** Whether the strip offered `text` as an emoji (or a kaomoji) for the reading. */
    fun isEmoji(text: String): Boolean = text in emojiShown

    /** The candidate the more panel showed as `text`. */
    fun pick(text: String): Candidate? = extra.firstOrNull { it.text == text } ?: slots.firstOrNull { it?.text == text }

    companion object {
        /** The more panel holds up to this many: five rows of three, for the homophones. */
        const val MOST = 15

        /** The panel's rows are three across: an association's emoji start its second. */
        private const val PANEL_ROW = 3
    }
}
