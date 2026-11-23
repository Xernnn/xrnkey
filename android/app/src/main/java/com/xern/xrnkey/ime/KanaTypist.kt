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
     * The part of speech the reading follows: that of a piece of it already taken from the strip
     * (0: none, the reading starts afresh).
     */
    private var left = 0

    /** What was committed lately, right before the composing text (at most [TAIL] characters). */
    private val tail = StringBuilder()
    private var wholeField = true
    private var anchor = -1

    override val shown: String get() = composing

    /** The reading as conversion takes it (a last lone n as ん). */
    val reading: String get() = romaji.reading

    /** Space converted the reading, and the field shows one of [cycle]. */
    val converting: Boolean get() = selected >= 0

    /** The candidates for the reading typed, or null with nothing composing. */
    fun conversion(): Conversion? = if (romaji.isEmpty()) null else converter.convert(romaji.reading, left)

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
            c == ' ' -> if (romaji.isEmpty()) commit(SPACE) else startConverting()
            romaji.isEmpty() && c !in 'a'..'z' && c !in 'A'..'Z' -> commit(ch.toString())
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

    /**
     * A candidate from the strip: it goes in, in place of the part of the reading it stands for;
     * the rest of the reading stays composing, as typed.
     */
    fun pick(c: Candidate) {
        val rest = romaji.reading.length - c.length
        stopConverting()
        if (rest <= 0) {
            clearReading()
            commit(c.text)
            return
        }
        commit(c.text)
        romaji.drop(c.length)
        left = c.rid
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
                if (romaji.isEmpty()) left = 0
                show(romaji.shown)
            }
            else -> {
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
                commit(romaji.reading)
                clearReading()
            }
            else -> {
                editor.enter()
                tail.append('\n')
            }
        }
    }

    /** Commits what the field shows (a last lone n as ん), as it is. */
    override fun finish() {
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

    private fun commitShown() {
        val text = composing
        clearReading()
        stopConverting()
        commit(text)
    }

    private fun clearReading() {
        romaji.clear()
        left = 0
    }

    private fun stopConverting() {
        selected = -1
        cycle = emptyList()
    }

    override fun reset(cursor: Int) {
        clearReading()
        stopConverting()
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
        commit(text)
    }

    override fun deleteWord() {
        if (composing.isNotEmpty()) {
            editor.setComposing("")
            composing = ""
            clearReading()
            stopConverting()
            return
        }
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

    private fun append(text: String) {
        tail.append(text)
        if (tail.length > TAIL) {
            val cut = tail.length - TAIL
            tail.delete(0, cut)
            if (anchor >= 0) anchor += cut
            wholeField = false
        }
    }

    // ---- the 12-key pad (flick input): its ways into the reading -------------------------------

    /**
     * A kana from the 12-key pad, tapped or flicked, or its 、。？！: it starts the reading, even
     * with nothing composing (where the QWERTY keys' 、 would go straight in), or goes on with it,
     * so a toggle can step it and Space converts it with the rest. After Space, the conversion
     * shown is committed first, as a letter commits it. A reading of punctuation alone is
     * committed before a kana, so the next sentence starts a reading of its own.
     */
    fun flick(k: String) {
        if (converting) {
            commitShown()
        } else if (!romaji.isEmpty() && romaji.reading.all { it in PAD_PUNCTUATION } && k.none { it in PAD_PUNCTUATION }) {
            commit(romaji.reading)
            clearReading()
        }
        for (c in k) romaji.type(c)
        show(romaji.shown)
    }

    /** The reading's last kana, or null (nothing composing, or romaji letters still waiting). */
    val lastKana: Char? get() = if (romaji.pending.isEmpty()) romaji.shown.lastOrNull() else null

    /**
     * The reading's last kana steps [by] along [cycle] (a key's toggle: a tap of it again, or ↺
     * back), if it is on it. After Space, the reading comes back first. False when it isn't.
     */
    fun step(cycle: String, by: Int): Boolean {
        val i = lastKana?.let { cycle.indexOf(it) } ?: -1
        if (i < 0) return false
        replaceLast(cycle[(i + by).mod(cycle.length)])
        return true
    }

    /** ゛゜小: the reading's last kana in its next form (ja.Kana). False when it has none. */
    fun mark(): Boolean {
        val next = lastKana?.let { com.xern.xrnkey.ja.Kana.nextForm(it) } ?: return false
        replaceLast(next)
        return true
    }

    private fun replaceLast(c: Char) {
        stopConverting()
        romaji.backspace()
        romaji.type(c)
        show(romaji.shown)
    }

    companion object {
        /** The 12-key pad's punctuation key's 、。？！. */
        private const val PAD_PUNCTUATION = "、。？！"
        private const val TAIL = 120

        /** Gboard's default space in Japanese: full-width. */
        const val SPACE = "　"

        /** Punctuation typed full-width in Japanese. */
        private val WIDE = mapOf('?' to '？', '!' to '！')
    }
}
