package com.xern.xrnkey.ime

import com.xern.xrnkey.engine.KeyResult
import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.engine.PersonalWords
import com.xern.xrnkey.engine.Shortcuts
import com.xern.xrnkey.engine.TypingState

/**
 * What the keyboard does to the text field, as InputConnection does it: the word in progress
 * is composing text, a commit replaces it.
 */
interface Editor {
    /** Show `text` as the word in progress ("" clears it). */
    fun setComposing(text: String)

    /** Put `text` in the field, in place of the word in progress if there is one. */
    fun commit(text: String)

    /** Keep the word in progress as it stands, ending it. */
    fun finishComposing()

    /** Delete before the cursor, or the selection (the Backspace key itself). */
    fun backspace()

    /** Delete `codePoints` characters right before the cursor (no word in progress). */
    fun deleteBefore(codePoints: Int)

    /** The field's Enter: a newline, or its action (send, search ...). */
    fun enter()

    /** Up to `n` characters before the cursor ("" when the field doesn't say). */
    fun textBefore(n: Int): String
}

/**
 * Turns the keyboard's keys into edits through the shared core.
 *
 * A word typed with Telex marks ("dduowcj") is composed by the core and committed as it ends.
 * A word typed without marks ("khong") is restored when Space or punctuation ends it
 * (android/docs/feature-spec.md, "Auto-correct"): its keys and those of the last two such words
 * are decoded together (look-back), so the earlier words may change too ("co gang" becomes
 * "cố gắng"), but only where the field still shows what this keyboard wrote. Backspace right
 * after puts back what was there. In password fields every key goes in as typed. No Android
 * types here, so the tests drive it with a fake editor.
 */
class Typist(
    private val state: TypingState?,
    private val editor: Editor,
    private val models: Models? = null,
) {
    /** Password and similar fields: no Telex, no composing. */
    var raw = false

    /** Restore words typed without marks when they end (off in URL and email fields). */
    var autoCorrect = true

    /** Two spaces after a word give ". " (only where auto-correct is on). */
    var doubleSpace = true

    /**
     * What Space and punctuation do to a word typed without marks. [TONES_OFF] keeps it as it
     * was typed, as Laban Key does: the accented reading is in the strip for a tap.
     * [TONES_WHEN_NEEDED] adds the tones only to a word that isn't one as it stands ("khong",
     * "duoc"; never "ong", "ban", or an English word). [TONES_ALWAYS] restores every word,
     * and may rewrite the two before it (look-back).
     */
    var tonesOnSpace = TONES_OFF

    /**
     * Auto-correct of words wrong in both languages ("teh" -> "the"): 0 off, 1 flexible
     * (a confident fix only), 2 strong (a likelier fix will do).
     */
    var correctWrong = 1

    /** The words of this sentence committed as typed without marks since the last pause. */
    private val plain = ArrayList<String>()

    /**
     * Where the words typed are learned (null: nowhere). Only settled words are: a word is
     * learned once look-back can no longer change it, with the word before it. Never in
     * password, address or incognito fields ([learnHere]), never from pasted text.
     */
    var personal: PersonalWords? = null

    /** This field allows learning (off for incognito fields). */
    var learnHere = true

    /** Abbreviations that expand when their word ends (null: none). */
    var shortcuts: Shortcuts? = null

    /** Committed text whose words are not learned yet, and the word before it. */
    private val pending = StringBuilder()
    private var pendingPrev: String? = "<s>"

    private var composing = ""

    /** What was committed lately, for the words before the next one. */
    private val tail = StringBuilder()

    /** Whether [tail] reaches back to the field's start (nothing before it was cut off). */
    private var wholeField = true

    /** Where in the field [tail] starts, as a UTF-16 index (-1: not known). */
    private var anchor = -1

    /** A word restoration may still change: its keys, what the field shows, what followed. */
    private data class RunWord(val typed: String, val shown: String, val sep: String)

    /** The words restored since the last pause, oldest first; look-back may rewrite them. */
    private val run = ArrayList<RunWord>()

    /**
     * What the last restoration changed, for Backspace right after it: `after` goes, `before`
     * comes back. After a Space, `reopen` holds the keys of the word Space changed: Backspace
     * takes the change and the space back and opens the word again, as typed.
     */
    private data class Undo(val before: String, val after: String, val reopen: String? = null)
    private var undo: Undo? = null

    /**
     * The keys of a word Backspace opened again after Space changed it: Space keeps it as typed,
     * until the word changes.
     */
    private var refused: String? = null

    /** The word in progress is one Backspace opened again: Space and Enter keep it as typed. */
    private fun keptAsTyped(): Boolean = refused != null && refused == keys()

    /** The last key was a Space that ended a word (for the double-Space full stop). */
    private var spaceAfterWord = false

    /**
     * Where the cursor is after this typist's own edits, as a UTF-16 index (-1: not known). A
     * selection update elsewhere means the field changed under it, and [reset] reads it again.
     */
    fun cursor(): Int = if (anchor < 0) -1 else anchor + tail.length + composing.length

    /** The editor says where the cursor is, and this typist didn't know: it is there now. */
    fun anchorAt(cursor: Int) {
        if (anchor < 0 && cursor >= 0) anchor = maxOf(0, cursor - tail.length - composing.length)
    }

    /** The word in progress, as shown. */
    val shown: String get() = composing

    /** The keys of the word in progress, as typed ("dduowcj" while it shows "được"). */
    fun keys(): String = state?.keys() ?: composing

    /** The word in progress was typed without marks, so restoration has it. */
    fun restorable(): Boolean =
        models != null && autoCorrect && !raw && composing.isNotEmpty() && keys() == composing &&
            composing.all { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * The words before the word in progress, oldest first, as accent restoration takes them:
     * "<s>" when they start a sentence, at most the last three, leaving out the last `skip`.
     */
    fun context(skip: Int = 0): List<String> {
        val text = tail.toString()
        val cut = text.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        val words = WORD.findAll(text.substring(cut + 1)).map { it.value }.toList().dropLast(skip)
        // The sentence's start is known: a full stop or new line, or the field's own start.
        val start = (cut >= 0 || wholeField) && words.size <= 3
        return (if (start) listOf("<s>") else emptyList()) + words.takeLast(3)
    }

    /**
     * Right after a word and a space (or punctuation and a space), with no word in progress:
     * where the strip offers the next word.
     */
    fun afterWord(): Boolean {
        if (composing.isNotEmpty() || raw || !autoCorrect) return false
        val n = tail.length
        return n >= 2 && tail[n - 1] == ' ' && tail[n - 2] != ' ' && !tail[n - 2].isWhitespace()
    }

    /** Nothing typed yet in the sentence: the field's start, or after ". ", "! ", "? " or a new line. */
    fun atSentenceStart(): Boolean {
        if (composing.isNotEmpty() || raw) return false
        val t = tail.toString()
        if (t.isBlank()) return wholeField
        if (t.last() == '\n') return true
        val trimmed = t.trimEnd()
        return t.length > trimmed.length && trimmed.last() in ".!?"
    }

    /** The expansion of the word in progress, if it is an abbreviation. */
    fun expansion(): String? =
        if (composing.isEmpty() || raw || !autoCorrect) null else shortcuts?.expand(keys())

    /**
     * What Space would commit for the word in progress, when it isn't what the field shows: an
     * abbreviation's expansion, a restored or fixed word, or what the core makes of a word typed
     * with marks when it ends ("meet" shows "mêt" and ends "meet").
     */
    fun preview(): String? {
        if (keptAsTyped()) return null
        expansion()?.let { return it }
        if (!restorable()) {
            if (raw || composing.isEmpty()) return null
            return state?.previewFinal()?.takeIf { it.isNotEmpty() && it != composing }
        }
        val typed = keys()
        return when {
            tonesOnSpace == TONES_ALWAYS -> reading()
            tonesOnSpace == TONES_WHEN_NEEDED && !models!!.plainWord(typed) -> reading(lookBack = false)
            else -> wrongWordFix(typed)
        }
    }

    /** The best accented reading of the word in progress, in context (and with look-back). */
    fun reading(lookBack: Boolean = true): String? {
        if (!restorable()) return null
        val prior = if (lookBack) run.takeLast(2) else emptyList()
        return models!!.restoreRun(context(prior.size), prior.map { it.typed } + keys())?.last()
    }

    /**
     * The words typed without marks before the word in progress, and with it, accented as one
     * run: ("toi khong biet", "tôi không biết"). Null when there is no such run, or it reads
     * the same.
     */
    fun phrase(): Pair<String, String>? {
        if (plain.isEmpty() || !restorable()) return null
        val typed = plain + keys()
        val restored = models!!.restoreRun(context(plain.size), typed) ?: return null
        if (restored == typed) return null
        return (plain.joinToString(" ") + " " + composing) to restored.joinToString(" ")
    }

    /** The phrase in place of the run it was read from, and a space. Backspace puts the run back. */
    fun pickPhrase(phrase: Pair<String, String>) {
        val (old, new) = phrase
        commit(composing, learnable = false)
        composing = ""
        state?.reset()
        if (replaceBefore(old, new)) {
            commit(" ")
            undo = Undo("$old ", "$new ")
        }
        plain.clear()
        run.clear()
        spaceAfterWord = true
        state?.setContext(context())
    }

    private fun wrongWordFix(typed: String): String? {
        if (correctWrong <= 0 || !autoCorrect) return null
        val fix = models?.fixWrongWord(typed, strong = correctWrong >= 2) ?: return null
        return if (typed.firstOrNull()?.isUpperCase() == true) fix.replaceFirstChar { it.uppercase() } else fix
    }

    /** Typed text: each character is a key. */
    fun text(t: String) {
        val s = state
        // A letter's Vietnamese form (held on its key) goes in as the keys that make it, so the
        // word goes on taking marks: "vi" + "ê" + "tj" is "việt".
        val keys = if (s != null && !raw && t.length == 1) VietKeys.of(t[0], s.mode) ?: t else t
        for (c in keys) key(c)
    }

    private fun key(c: Char) {
        val wasSpaceAfterWord = spaceAfterWord
        spaceAfterWord = false
        undo = null
        if (!isSeparator(c)) refused = null  // the word changes: Space may change it again
        val s = state
        if (raw || s == null) {
            commit(c.toString())
            return
        }
        if (c == ' ' && composing.isEmpty() && wasSpaceAfterWord && doubleSpace && autoCorrect) {
            replaceBefore(" ", ". ")
            run.clear()
            s.setContext(context())
            return
        }
        if (isSeparator(c) && !keptAsTyped() && expansion() != null) {
            val shown = composing
            val typed = keys()
            val e = expansion()!!
            commit(e + c)
            composing = ""
            s.reset()
            run.clear()
            undo = if (c == ' ') Undo("", e + c, reopen = typed) else Undo(shown + c, e + c)
            spaceAfterWord = c == ' '
            s.setContext(context())
            return
        }
        if (isSeparator(c) && restorable()) {
            val typed = keys()
            when {
                keptAsTyped() -> endAsTyped(c, null)
                tonesOnSpace == TONES_ALWAYS -> endRestored(c, lookBack = true)
                tonesOnSpace == TONES_WHEN_NEEDED && !models!!.plainWord(typed) -> endRestored(c, lookBack = false)
                else -> endAsTyped(c, wrongWordFix(typed))
            }
            spaceAfterWord = c == ' '
            return
        }
        val hadWord = composing.isNotEmpty()
        // A number ends like a word: two spaces after "1" give "1. ", as Laban Key does.
        val afterNumber = !hadWord && tail.isNotEmpty() && tail.last().isDigit()
        val r = s.key(c.code)
        apply(r) { commit(c.toString()) }
        // A word the core finished (typed with marks), or a key that is not a letter, ends the
        // run; the letters of the next word do not.
        if (r.commit.isNotEmpty() || !r.consumed || !c.isLetter()) {
            run.clear()
            plain.clear()
        }
        spaceAfterWord = c == ' ' && (hadWord || afterNumber)
    }

    /**
     * Space or punctuation ends a word typed without marks and kept: as typed, or as the fix
     * of a word wrong in both languages (Backspace right after puts back what was typed).
     */
    private fun endAsTyped(c: Char, fix: String?) {
        val shown = composing
        commit((fix ?: shown) + c)
        composing = ""
        state?.reset()
        run.clear()
        if (fix != null) {
            undo = if (c == ' ') Undo("", fix + c, reopen = shown) else Undo(shown + c, fix + c)
            plain.clear()
        } else {
            plain += shown
        }
        if (c != ' ') plain.clear()
        state?.setContext(context())
    }

    /** Space or punctuation ends a word typed without marks: restore it, with look-back. */
    private fun endRestored(c: Char, lookBack: Boolean = true) {
        plain.clear()
        val typed = keys()
        val prior = if (lookBack) run.takeLast(2) else emptyList()
        val next = when (c) {
            ',', ';', ':' -> "<p>"
            '.', '!', '?' -> "</s>"
            else -> null
        }
        val result = models!!.restoreRun(context(prior.size), prior.map { it.typed } + typed, next)
        val word = result?.last() ?: typed
        val sep = c.toString()
        // The word in progress first, as restored.
        commit(word + sep)
        composing = ""
        state?.reset()
        // Then the earlier words of the run, where look-back changed them.
        val oldPrior = prior.joinToString("") { it.shown + it.sep }
        val newPrior = prior.mapIndexed { i, w -> (result?.get(i) ?: w.shown) + w.sep }.joinToString("")
        var priorNow = oldPrior
        if (result != null && newPrior != oldPrior && replaceBefore(oldPrior + word + sep, newPrior + word + sep)) {
            priorNow = newPrior
            prior.forEachIndexed { i, w -> run[run.size - prior.size + i] = w.copy(shown = result[i]) }
        }
        val before = oldPrior + typed + sep
        val after = priorNow + word + sep
        undo = when {
            before == after -> null
            c == ' ' -> Undo(oldPrior, after, reopen = typed)
            else -> Undo(before, after)
        }
        run += RunWord(typed, word, sep)
        while (run.size > 2) run.removeAt(0)
        if (c != ' ' || !lookBack) run.clear()  // a pause or a sentence end: what came before stays
        state?.setContext(context())
    }

    fun backspace() {
        spaceAfterWord = false
        val u = undo
        undo = null
        refused = null
        val s = state
        if (raw || s == null) {
            editor.backspace()
            return
        }
        if (composing.isEmpty()) plain.clear()
        // Right after a restoration: put back what was there. After a Space, the space goes too
        // and the word is open again as it was typed ("teh␣" Backspace is "teh"), as Laban Key
        // does; the next Space keeps it.
        if (u != null && composing.isEmpty() && replaceBefore(u.after, u.before)) {
            run.clear()
            s.setContext(context())
            u.reopen?.let { keys ->
                for (k in keys) apply(s.key(k.code))
                refused = keys
            }
            return
        }
        apply(s.key(TypingState.BACKSPACE)) {
            editor.backspace()
            // Backspace takes a character: an emoji is two UTF-16 units.
            val n = tail.length
            val units = if (n >= 2 && Character.isSurrogatePair(tail[n - 2], tail[n - 1])) 2 else 1
            when {
                n > 0 -> tail.setLength(n - units)
                !wholeField && anchor > 0 -> anchor -= 1  // a character before what the tail holds
            }
            if (pending.isNotEmpty()) pending.setLength(maxOf(0, pending.length - units))
            run.clear()
        }
    }

    /**
     * A swipe left from Backspace: the word in progress goes, or else the word before the cursor
     * with the spaces after it ("xin chao ban" and "xin chao ban " both leave "xin chao "). A run
     * of punctuation or emoji goes as one word; a new line goes alone. One word a call. What the
     * field shows is read for it, and the typist's own copy follows, so the words before the
     * cursor stay its context.
     */
    fun deleteWord() {
        spaceAfterWord = false
        undo = null
        plain.clear()
        run.clear()
        val s = state
        if (composing.isNotEmpty()) {
            editor.setComposing("")
            composing = ""
            s?.reset()
            s?.setContext(context())
            return
        }
        val before = editor.textBefore(TAIL)
        val n = wordBefore(before)
        if (n == 0) return
        val gone = before.substring(before.length - n)
        editor.deleteBefore(gone.codePointCount(0, gone.length))
        val inTail = minOf(n, tail.length)
        tail.setLength(tail.length - inTail)
        if (n > inTail && anchor > 0) anchor = maxOf(0, anchor - (n - inTail))
        pending.setLength(maxOf(0, pending.length - n))
        s?.setContext(context())
    }

    /** Enter ends the word, then reaches the field: one press does both. */
    fun enter() {
        spaceAfterWord = false
        undo = null
        val s = state
        if (!raw && s != null) {
            if (restorable()) {
                val typed = keys()
                val restore = tonesOnSpace == TONES_ALWAYS ||
                    tonesOnSpace == TONES_WHEN_NEEDED && !models!!.plainWord(typed)
                val r = when {
                    keptAsTyped() -> composing
                    restore -> models!!.restoreRun(context(), listOf(typed), "</s>")?.last() ?: composing
                    else -> wrongWordFix(typed) ?: composing
                }
                commit(r)
                composing = ""
                s.reset()
            } else {
                apply(s.key(TypingState.RETURN))
            }
        }
        run.clear()
        plain.clear()
        refused = null
        editor.enter()
        tail.append('\n')
        pending.append('\n')
        learnSettled(all = true)
    }

    /**
     * The cursor moved, or the field is being left: end the word as a boundary would (the core
     * may restore it, "tém" to "terms") and keep it in the field.
     */
    fun finish() {
        val s = state ?: return
        val r = s.flush()
        when {
            r.commit.isNotEmpty() && r.commit != composing -> commit(r.commit)
            composing.isNotEmpty() -> {
                editor.finishComposing()
                tail.append(composing)
                pending.append(composing)
            }
        }
        composing = ""
        s.reset()
        run.clear()
        undo = null
        refused = null
        spaceAfterWord = false
        learnSettled(all = true)
    }

    /**
     * A new field, or the field changed in a way this typist's edits don't explain (cleared after
     * Send, select-all and delete, the cursor moved): nothing is in progress, and what is before
     * the cursor is the field's text, read once. `cursor` is where the editor says the cursor
     * is (-1: not known).
     */
    fun reset(cursor: Int = -1) {
        plain.clear()
        learnSettled(all = true)
        state?.reset()
        composing = ""
        run.clear()
        undo = null
        refused = null
        spaceAfterWord = false
        tail.clear()
        val before = editor.textBefore(TAIL)
        tail.append(before)
        wholeField = before.length < TAIL
        anchor = when {
            cursor >= 0 -> maxOf(0, cursor - before.length)
            wholeField -> 0
            else -> -1
        }
        pending.clear()
        pendingPrev = context().lastOrNull()
        state?.setContext(context())
    }

    /** Text from a panel (an emoji, a clip): the word in progress ends as it is, then the text. */
    fun insert(text: String) {
        plain.clear()
        if (composing.isNotEmpty()) finish()
        undo = null
        spaceAfterWord = false
        run.clear()
        learnSettled(all = true)
        commit(text, learnable = false)
        pendingPrev = "<p>"
        state?.setContext(context())
    }

    /** A candidate from the strip, in place of the word in progress, and a space. */
    fun pick(word: String) {
        plain.clear()
        commit("$word ")
        composing = ""
        state?.reset()
        run.clear()
        undo = null
        spaceAfterWord = true
        state?.setContext(context())
    }

    /** What was committed lately, right before the word in progress (at most 120 characters). */
    fun before(): String = tail.toString()

    /**
     * An emoji from the strip in place of what it stands for, with no space after it: the word in
     * progress ("cam" -> "🍊"), and `typed` right before it where the field still shows that (the
     * "cà " of "cà phê", or an emoticon typed with symbols, ":)" -> "🙂").
     */
    fun replaceWith(text: String, typed: String = "") {
        plain.clear()
        run.clear()
        undo = null
        spaceAfterWord = false
        if (composing.isNotEmpty()) {
            editor.setComposing("")
            composing = ""
            state?.reset()
        }
        if (typed.isEmpty() || !replaceBefore(typed, text)) commit(text, learnable = false)
        learnSettled(all = true)
        pendingPrev = "<p>"
        state?.setContext(context())
    }

    private fun commit(text: String, learnable: Boolean = true) {
        refused = null
        editor.commit(text)
        tail.append(text)
        if (tail.length > TAIL) {
            val cut = tail.length - TAIL
            tail.delete(0, cut)
            if (anchor >= 0) anchor += cut
            wholeField = false
        }
        if (learnable) {
            pending.append(text)
            learnSettled(all = false)
        }
    }

    /**
     * Learns the words of [pending] that look-back and Backspace can no longer change (all of
     * them at a new line, or when the field is left or the cursor moves), each with the word before
     * it: "<s>" at a sentence's start, "<p>" after other punctuation.
     */
    private fun learnSettled(all: Boolean) {
        val p = personal
        if (p == null || raw || !autoCorrect || !learnHere) {
            pending.clear()
            return
        }
        val text = pending.toString()
        val words = WORD.findAll(text).toList()
        val n = words.size - if (all) 0 else SETTLE
        if (n <= 0) return
        var prev = pendingPrev
        var at = 0
        for (i in 0 until n) {
            val m = words[i]
            val gap = text.substring(at, m.range.first)
            prev = when {
                gap.any { it in SENTENCE_END } -> "<s>"
                gap.any { it != ' ' } -> "<p>"
                else -> prev
            }
            p.learn(prev, m.value)
            prev = m.value
            at = m.range.last + 1
        }
        pending.delete(0, at)
        pendingPrev = prev
    }

    /**
     * Replace `old`, right before the cursor, with `new`, if the field still shows exactly
     * `old` there; otherwise leave the field alone. True when it was replaced.
     */
    private fun replaceBefore(old: String, new: String): Boolean {
        if (editor.textBefore(old.length) != old) return false
        editor.deleteBefore(old.codePointCount(0, old.length))
        if (tail.endsWith(old)) tail.setLength(tail.length - old.length)
        if (pending.endsWith(old)) pending.setLength(pending.length - old.length)
        if (new.isNotEmpty()) commit(new)
        return true
    }

    private inline fun apply(r: KeyResult, passThrough: () -> Unit = {}) {
        if (r.commit.isNotEmpty()) {
            commit(r.commit)
            composing = ""
        }
        if (r.preedit != composing) {
            editor.setComposing(r.preedit)
            composing = r.preedit
        }
        if (!r.consumed) passThrough()
    }

    companion object {
        /** How much committed text is kept: enough for three words and where the sentence began. */
        private const val TAIL = 120
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        private fun isSeparator(c: Char) = c == ' ' || c in ",.;:!?"

        /** Marks, joiners, variation selectors and skin tones: part of the character before them. */
        private fun joins(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt(), Character.MODIFIER_SYMBOL.toInt() -> true
            else -> false
        }

        private fun wordChar(cp: Int) = Character.isLetterOrDigit(cp)

        /**
         * How many UTF-16 units at the end of `t` a word deletion takes: the spaces at the end,
         * then the word (letters and digits) or the run of other characters before them, never
         * past a new line; a new line right at the end alone.
         */
        internal fun wordBefore(t: String): Int {
            var i = t.length
            while (i > 0 && (t[i - 1] == ' ' || t[i - 1] == '\t')) i--
            if (i == 0) return t.length
            if (t[i - 1] == '\n') return if (i < t.length) t.length - i else 1
            // The run's kind is the kind of its last character that isn't a mark on another.
            var j = i
            var base = t.codePointBefore(j)
            while (joins(base) && j - Character.charCount(base) > 0) {
                j -= Character.charCount(base)
                base = t.codePointBefore(j)
            }
            val word = wordChar(base)
            while (i > 0) {
                val cp = t.codePointBefore(i)
                if (cp == ' '.code || cp == '\t'.code || cp == '\n'.code) break
                if (!joins(cp) && wordChar(cp) != word) break
                i -= Character.charCount(cp)
            }
            return t.length - i
        }

        private const val SENTENCE_END = ".!?\n"

        const val TONES_OFF = 0
        const val TONES_WHEN_NEEDED = 1
        const val TONES_ALWAYS = 2

        /** Words kept back from learning: the look-back window (two) and the newest. */
        private const val SETTLE = 3
    }
}
