package com.xern.xrnkey.ime

import com.xern.xrnkey.ja.Candidate
import com.xern.xrnkey.ja.Conversion
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.Romaji

/**
 * The Japanese keyboard's keys, as Gboard's Japanese QWERTY has them (android/docs/feature-spec.md,
 * "The Japanese keyboard"). Letters spell a reading in romaji ([Romaji]), shown as composing
 * hiragana. Space converts it: the field shows the best conversion, and each Space after it the
 * next candidate. Enter commits what the field shows; so does the next letter, which starts a new
 * reading. Backspace takes the last letter or kana, or, after Space, goes back to the reading.
 * With nothing composing, Space types a full-width space and Enter reaches the field.
 *
 * 、 and 。 come from their keys; ? and ! are typed full-width. Inside a reading they join it, as
 * digits and other symbols do; with nothing composing they go straight in. No Android types here,
 * so the tests drive it with a fake editor.
 *
 * What the owner chooses, by Space or by a tap, is learned in the converter's history where the
 * field allows it ([learnHere]), each word with the one before it. Right after a word goes in, the
 * strip offers what may follow it ([nextWords]); a tap on one puts it in, with no space.
 */
class KanaTypist(private val editor: Editor, private val converter: Converter) : Input {
    private val romaji = Romaji()
    private var composing = ""

    /** After Space: the candidates it goes through, and the one the field shows (else -1). */
    var cycle: List<Candidate> = emptyList()
        private set
    var selected = -1
        private set

    /**
     * The word right before the reading (or the cursor) that it follows: the last one put in by
     * Space, Enter or a tap, while nothing else went in after it (null: none, at the field's
     * start, after a space or punctuation). The reading converts after its part of speech ("は"
     * after 私 is the particle), and the next words follow it.
     */
    var context: Converter.Segment? = null
        private set

    /** This field allows learning (off in incognito fields and this app's own). */
    var learnHere = true

    /** What was committed lately, right before the composing text (at most [TAIL] characters). */
    private val tail = StringBuilder()
    private var wholeField = true
    private var anchor = -1

    override val shown: String get() = composing

    /** The reading as conversion takes it (a last lone n as ん). */
    val reading: String get() = romaji.reading

    /** Space converted the reading, and the field shows one of [cycle]. */
    val converting: Boolean get() = selected >= 0

    /** The candidates for the reading typed, after [context], or null with nothing composing. */
    fun conversion(): Conversion? =
        if (romaji.isEmpty()) null else converter.convert(romaji.reading, context?.rid ?: 0, context?.takeIf { it.word }?.surface)

    /** With nothing composing, right after a word: what may follow it, likeliest first (else none). */
    fun nextWords(): List<Candidate> {
        val c = context
        if (composing.isNotEmpty() || !romaji.isEmpty() || c == null) return emptyList()
        return converter.next(c)
    }

    override fun cursor(): Int = if (anchor < 0) -1 else anchor + tail.length + composing.length

    override fun anchorAt(cursor: Int) {
        if (anchor < 0 && cursor >= 0) anchor = maxOf(0, cursor - tail.length - composing.length)
    }

    override fun text(t: String) {
        for (c in t) key(c)
    }

    private fun key(c: Char) {
        if (converting) {
            if (c == ' ') {
                select((selected + 1) % cycle.size)
                return
            }
            commitShown()
        }
        val ch = WIDE[c] ?: c
        when {
            c == ' ' -> if (romaji.isEmpty()) commitOther(SPACE) else startConverting()
            romaji.isEmpty() && c !in 'a'..'z' && c !in 'A'..'Z' -> commitOther(ch.toString())
            else -> {
                romaji.type(ch)
                show(romaji.shown)
            }
        }
    }

    private fun startConverting() {
        cycle = conversion()!!.cycle
        select(0)
    }

    private fun select(i: Int) {
        selected = i
        show(shownFor(cycle[i]))
    }

    /** What the field shows for a candidate: it, and the rest of the reading converted after it. */
    private fun shownFor(c: Candidate): String {
        val r = romaji.reading
        return if (c.length >= r.length) c.text else c.text + converter.bestText(r.substring(c.length), c.rid)
    }

    /** The words of what the field shows for a candidate: its own, then those of the rest after it. */
    private fun segmentsFor(c: Candidate): List<Converter.Segment> {
        val r = romaji.reading
        return if (c.length >= r.length) c.segments else c.segments + converter.best(r.substring(c.length), c.rid)
    }

    /**
     * A candidate from the strip: it goes in, in place of the part of the reading it stands for;
     * the rest of the reading stays composing, as typed. With nothing composing, a next word: it
     * goes in after the word before, with no space.
     */
    fun pick(c: Candidate) {
        val rest = romaji.reading.length - c.length
        stopConverting()
        learn(c.segments)
        commit(c.text)
        context = c.segments.lastOrNull()
        if (rest <= 0) {
            clearReading()
            return
        }
        romaji.drop(c.length)
        show(romaji.shown)
    }

    override fun backspace() {
        when {
            converting -> {
                stopConverting()
                show(romaji.shown)
            }
            !romaji.isEmpty() -> {
                romaji.backspace()
                show(romaji.shown)
            }
            else -> {
                context = null
                val n = tail.length
                val last = Typist.lastCharacter(tail)
                editor.backspace(last)
                when {
                    n > 0 -> tail.setLength(n - maxOf(1, last.length))
                    !wholeField && anchor > 0 -> anchor -= 1
                }
            }
        }
    }

    override fun enter() {
        when {
            converting -> commitShown()
            !romaji.isEmpty() -> {
                // The reading as typed: not a choice to learn, but a word the next ones follow.
                val r = romaji.reading
                commit(r)
                clearReading()
                context = converter.asTyped(r)
            }
            else -> {
                context = null
                editor.enter()
                tail.append('\n')
            }
        }
    }

    /** Commits what the field shows (a last lone n as ん), as it is. */
    override fun finish() {
        context = null
        if (composing.isEmpty()) return
        val text = if (converting) composing else romaji.reading
        if (text == composing) {
            editor.finishComposing()
            append(composing)
            composing = ""
        } else {
            commit(text)
        }
        clearReading()
        stopConverting()
    }

    /** Commits what Space shows, as chosen: learned, and the word the next ones follow. */
    private fun commitShown() {
        val text = composing
        val chosen = segmentsFor(cycle[selected])
        learn(chosen)
        clearReading()
        stopConverting()
        commit(text)
        context = chosen.lastOrNull()
    }

    /**
     * The owner chose these words, after [context]: learned where this field allows it (the
     * converter has a history only while learning is on).
     */
    private fun learn(chosen: List<Converter.Segment>) {
        val h = converter.history ?: return
        if (!learnHere || chosen.isEmpty()) return
        h.learn(context?.takeIf { it.word }?.surface, chosen)
    }

    private fun clearReading() {
        romaji.clear()
    }

    private fun stopConverting() {
        selected = -1
        cycle = emptyList()
    }

    override fun reset(cursor: Int) {
        clearReading()
        stopConverting()
        context = null
        composing = ""
        tail.clear()
        val before = editor.textBefore(TAIL)
        tail.append(before)
        wholeField = before.length < TAIL
        anchor = when {
            cursor >= 0 -> maxOf(0, cursor - before.length)
            wholeField -> 0
            else -> -1
        }
    }

    /** No word opens again: a reading is typed, not found in the field. */
    override fun reopenBefore(after: String): Boolean = false

    override fun insert(text: String) {
        finish()
        commitOther(text)
    }

    override fun deleteWord() {
        if (composing.isNotEmpty()) {
            editor.setComposing("")
            composing = ""
            clearReading()
            stopConverting()
            return
        }
        context = null
        val before = editor.textBefore(TAIL)
        val n = Typist.wordBefore(before)
        if (n == 0) return
        val gone = before.substring(before.length - n)
        editor.deleteBefore(gone.codePointCount(0, gone.length))
        val inTail = minOf(n, tail.length)
        tail.setLength(tail.length - inTail)
        if (n > inTail && anchor > 0) anchor = maxOf(0, anchor - (n - inTail))
    }

    override fun atSentenceStart(): Boolean = false

    private fun show(text: String) {
        if (text == composing) return
        editor.setComposing(text)
        composing = text
    }

    private fun commit(text: String) {
        editor.commit(text)
        composing = ""
        append(text)
    }

    /** Text that is no word (a space, punctuation, an emoji): nothing follows a word across it. */
    private fun commitOther(text: String) {
        commit(text)
        context = null
    }

    private fun append(text: String) {
        tail.append(text)
        if (tail.length > TAIL) {
            val cut = tail.length - TAIL
            tail.delete(0, cut)
            if (anchor >= 0) anchor += cut
            wholeField = false
        }
    }

    companion object {
        private const val TAIL = 120

        /** Gboard's default space in Japanese: full-width. */
        const val SPACE = "　"

        /** Punctuation typed full-width in Japanese. */
        private val WIDE = mapOf('?' to '？', '!' to '！')
    }
}
