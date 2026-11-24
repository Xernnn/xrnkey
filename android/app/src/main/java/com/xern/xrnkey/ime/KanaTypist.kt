package com.xern.xrnkey.ime

import com.xern.xrnkey.ja.Candidate
import com.xern.xrnkey.ja.Conversion
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.Romaji

/**
 * The Japanese keyboard's keys, as Gboard's Japanese QWERTY has them (android/docs/feature-spec.md,
 * "The Japanese keyboard"). Letters spell a reading in romaji ([Romaji]), shown as composing
 * hiragana. Space converts it: the field shows the best conversion, split into phrases
 * ([phrases]: a word and what goes with it, as Mozc's segments), the first in focus. Each Space
 * after it shows the focused phrase's next candidate; a tap on one takes it and moves the focus
 * on; [moveFocus] moves it, [resize] stretches or shrinks the focused phrase. Enter commits what
 * the field shows; so does the next letter, which starts a new reading. Backspace takes the last
 * letter or kana, or, after Space, goes back to the reading. With nothing composing, Space types a
 * full-width space and Enter reaches the field.
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
    private var composingFocus: IntRange? = null

    /**
     * A phrase of the reading Space converted (a bunsetsu: a word and the particles and endings
     * that go with it): where it is in the reading, what it may be (the conversion's own first,
     * then the phrase's reading converted alone, in Space's order, worked out when it is first
     * focused), and which of them the field shows.
     */
    class Phrase internal constructor(val from: Int, val to: Int, first: Candidate) {
        internal var options: List<Candidate> = listOf(first)
        internal var full = false
        var chosen = 0
            internal set
        val shown: Candidate get() = options[chosen]
    }

    /** After Space: the phrases the field shows (else none), and the one in focus. */
    var phrases: List<Phrase> = emptyList()
        private set
    var focus = 0
        private set

    /** After Space: the focused phrase's candidates, in Space's order (else none). */
    val cycle: List<Candidate> get() = if (converting) options(focus) else emptyList()

    /** After Space: which of [cycle] the field shows (else -1). */
    val selected: Int get() = if (converting) phrases[focus].chosen else -1

    /**
     * The word right before the reading (or the cursor) that it follows: the last one put in by
     * Space, Enter or a tap, or the 、 or 。 after it, while nothing else went in after it (null:
     * none, at the field's start, after a space). The reading converts after its part of speech
     * ("は" after 私 is the particle), and the next words follow it.
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

    /** Space converted the reading, and the field shows its [phrases]. */
    val converting: Boolean get() = phrases.isNotEmpty()

    /** The candidates for the reading typed, after [context], or null with nothing composing. */
    fun conversion(): Conversion? =
        if (romaji.isEmpty()) null else converter.convert(romaji.reading, context?.rid ?: 0, context?.takeIf { it.word }?.surface)

    /** With nothing composing, right after a word or 、 。: what may follow it, likeliest first (else none). */
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
            romaji.isEmpty() && c !in 'a'..'z' && c !in 'A'..'Z' -> {
                commitOther(ch.toString())
                // After 、 and 。: the next words are what a clause or a sentence starts with.
                context = converter.punctuation(ch.toString())
            }
            else -> {
                romaji.type(ch)
                show(romaji.shown)
            }
        }
    }

    /** Space: the best conversion, in phrases, the first in focus. */
    private fun startConverting() {
        val best = conversion()!!.best
        var at = 0
        phrases = converter.phrases(best.segments).map { words ->
            val n = words.sumOf { it.reading.length }
            Phrase(at, at + n, Candidate(words.joinToString("") { it.surface }, n, words.last().rid, words)).also { at += n }
        }
        // Text no word stands for, as one candidate (a wide form, say): one phrase of it.
        if (phrases.isEmpty() || at != romaji.reading.length) phrases = listOf(Phrase(0, romaji.reading.length, best))
        focus = 0
        showPhrases()
    }

    /** The focused phrase shows its candidate `i`. */
    private fun select(i: Int) {
        phrases[focus].chosen = i
        showPhrases()
    }

    /** The word before phrase `i`: the context, or the last word the phrase before shows. */
    private fun before(i: Int): Converter.Segment? = if (i == 0) context else phrases[i - 1].shown.segments.lastOrNull()

    /** Phrase `i`'s candidates: its own, then those of its reading converted after the phrase before. */
    private fun options(i: Int): List<Candidate> {
        val p = phrases[i]
        if (p.full) return p.options
        val r = romaji.reading.substring(p.from, p.to)
        val b = before(i)
        val conv = converter.convert(r, b?.rid ?: 0, b?.takeIf { it.word }?.surface)
        p.options = (listOf(p.options[0]) + conv.cycle.map { whole(it, r) }).distinctBy { it.text }
        p.full = true
        return p.options
    }

    /** A candidate for a phrase's reading `r` as the whole phrase: it, and the rest of `r` converted after it. */
    private fun whole(c: Candidate, r: String): Candidate {
        if (c.length >= r.length) return c
        val rest = converter.path(r.substring(c.length), c.rid)
        return Candidate(c.text + rest.text, r.length, if (rest.segments.isEmpty()) c.rid else rest.rid, c.segments + rest.segments)
    }

    /** A phrase of the reading from `from` to `to`, converted after `before`. */
    private fun phraseOf(from: Int, to: Int, before: Converter.Segment?): Phrase {
        val words = converter.best(romaji.reading.substring(from, to), before?.rid ?: 0)
        return Phrase(from, to, Candidate(words.joinToString("") { it.surface }, to - from, words.lastOrNull()?.rid ?: 0, words))
    }

    /** The field shows each phrase's candidate, the focused one marked. */
    private fun showPhrases() {
        val texts = phrases.map { it.shown.text }
        val start = texts.take(focus).sumOf { it.length }
        show(texts.joinToString(""), start until start + texts[focus].length)
    }

    /** After Space: the focus moves `by` phrases (◀ ▶, the space bar's slide), no further than the ends. */
    fun moveFocus(by: Int) {
        if (!converting) return
        val f = (focus + by).coerceIn(0, phrases.lastIndex)
        if (f == focus) return
        focus = f
        showPhrases()
    }

    /**
     * After Space: the focused phrase stretches (`by` > 0) or shrinks by `by` kana, as Mozc's
     * Shift+arrows do. The kana it gives up start the next phrase (or a new one at the end), the
     * kana it takes come off the next; both are converted again, the focused one after the phrase
     * before and the next after it, and the phrases further on stay. False when it can't (a phrase
     * of one kana shrinking, the last one stretching).
     */
    fun resize(by: Int): Boolean {
        if (!converting || by == 0) return false
        val p = phrases[focus]
        val to = p.to + by
        val next = phrases.getOrNull(focus + 1)
        if (to <= p.from || to > romaji.reading.length || (by > 0 && next == null)) return false
        val end = next?.to ?: romaji.reading.length
        val mine = phraseOf(p.from, to, before(focus))
        val rest = if (to < end) phraseOf(to, end, mine.shown.segments.lastOrNull()) else null
        phrases = phrases.subList(0, focus) + listOfNotNull(mine, rest) + phrases.subList(minOf(phrases.size, focus + 2), phrases.size)
        showPhrases()
        return true
    }

    /**
     * A candidate from the strip: after Space, the focused phrase's, which it shows, the focus
     * moving on (on the last phrase, everything is committed). While composing, it goes in, in
     * place of the part of the reading it stands for; the rest of the reading stays composing, as
     * typed. With nothing composing, a next word: it goes in after the word before, with no space.
     */
    fun pick(c: Candidate) {
        if (converting) {
            val o = options(focus)
            val i = o.indexOfFirst { it === c }.takeIf { it >= 0 } ?: o.indexOfFirst { it.text == c.text }
            if (i >= 0) {
                phrases[focus].chosen = i
                if (focus == phrases.lastIndex) commitShown() else moveFocus(1)
                return
            }
            // Not the focused phrase's: a prediction for the whole reading (one phrase), else stale.
            if (phrases.size > 1) return
        }
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
            composingFocus = null
        } else {
            commit(text)
        }
        clearReading()
        stopConverting()
    }

    /** Commits what Space shows, as chosen: each phrase's words learned, and the word the next ones follow. */
    private fun commitShown() {
        val text = composing
        val chosen = phrases.flatMap { it.shown.segments }
        val last = phrases.last().shown.segments.lastOrNull()
        learn(chosen)
        clearReading()
        stopConverting()
        commit(text)
        context = last
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
        phrases = emptyList()
        focus = 0
    }

    override fun reset(cursor: Int) {
        clearReading()
        stopConverting()
        context = null
        composing = ""
        composingFocus = null
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
            composingFocus = null
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

    /** The field's composing text is `text`, with the part `focus` marked (after Space, the focused phrase). */
    private fun show(text: String, focus: IntRange? = null) {
        if (text == composing && focus == composingFocus) return
        if (focus == null) editor.setComposing(text) else editor.setComposing(text, focus)
        composing = text
        composingFocus = focus
    }

    private fun commit(text: String) {
        editor.commit(text)
        composing = ""
        composingFocus = null
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
