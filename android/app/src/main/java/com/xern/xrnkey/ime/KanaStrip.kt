package com.xern.xrnkey.ime

import com.xern.xrnkey.ja.Candidate
import com.xern.xrnkey.keyboard.Strip

/**
 * The strip of the Japanese keyboard. While a reading is composing: the best conversion in the
 * middle, the hiragana on the left and the katakana on the right; the more panel holds the words
 * read exactly so, the conversions of the reading's start (piece by piece) and the predictions,
 * words whose reading goes on from it ([com.xern.xrnkey.ja.Conversion.offered]). After Space, the
 * candidate the field shows is in the middle, highlighted, with the one before it on the left
 * and the next on the right; the panel holds the others. With nothing composing, the tool row.
 */
class KanaStrip {
    private var slots: List<Candidate?> = emptyList()
    private var extra: List<Candidate> = emptyList()

    fun make(k: KanaTypist): Strip {
        val conv = k.conversion()
        if (k.shown.isEmpty() || conv == null) {
            slots = emptyList()
            extra = emptyList()
            return Strip.Tools
        }
        if (k.converting) {
            val c = k.cycle
            val i = k.selected
            slots = listOf(c.getOrNull(i - 1), c[i], c.getOrNull(i + 1))
            val shown = slots.mapNotNull { it?.text }.toSet()
            extra = (c.filterIndexed { j, _ -> j !in i - 1..i + 1 } + conv.predictions).filter { it.text !in shown }.distinctBy { it.text }
            return candidates(highlight = true)
        }
        val all = conv.offered
        slots = listOf(all.getOrNull(1), all[0], all.getOrNull(2))
        extra = all.drop(3)
        return candidates(highlight = false)
    }

    private fun candidates(highlight: Boolean) = Strip.Candidates(
        slots[0]?.text, slots[1]?.text, slots[2]?.text,
        more = extra.isNotEmpty(), bold = true, extra = extra.map { it.text }.take(MOST), highlight = highlight,
        most = MOST, removable = false,
    )

    /** The candidate in slot 0 (left), 1 (centre) or 2 (right). */
    fun pick(slot: Int): Candidate? = slots.getOrNull(slot)

    /** The candidate the more panel showed as `text`. */
    fun pick(text: String): Candidate? = extra.firstOrNull { it.text == text } ?: slots.firstOrNull { it?.text == text }

    companion object {
        /** The more panel holds up to this many: five rows of three, for the homophones. */
        const val MOST = 15
    }
}
