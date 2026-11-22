package com.xern.xrnkey.ime

import com.xern.xrnkey.engine.Candidate
import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.keyboard.Strip
import java.text.Normalizer
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * What the strip shows for the word in progress, as Laban Key lays it out: the best guess in
 * the middle, the best other reading on the left, and on the right the middle with the word
 * likely to follow it, an emoji that goes with the word, or the next best guess. Laban, measured
 * on the owner's phone: "hel" help | hello | held, "xin" xịn | xin lỗi | xinh, "cam" cám ơn |
 * cảm ơn | 🍊, "meet" (which Telex shows as "mêt") mệt mỏi | mệt | mệt rồi.
 *
 * The guesses, each scored log10 P(text | the words before) by the models:
 * - the readings of the word: its accented forms when it was typed without marks ("cam": cảm,
 *   cám), or the tones it may take when typed with marks but none ("mêt": mệt);
 * - a reading with the word likely to follow it ("cảm ơn"). The reading alone then scores what
 *   is left of it, "cảm" followed by anything but "ơn": a near-certain pair takes the reading's
 *   place, a weaker one comes after it;
 * - completions: longer words that start with the letters typed ("hel": hello, help, "xin":
 *   xinh).
 *
 * Space keeps what was typed unless it fixes a wrong word, so a word typed without marks takes
 * no slot of its own while there is something else to offer, unless it reads as English ("the").
 * Letters that are a common Vietnamese syllable keep the middle Vietnamese, as Laban does: "cam"
 * is "cảm ơn" there, and English completions go to the sides. When Space commits something other
 * than what the field shows ("teh" -> "the"), that is in the middle. The middle is in bold only
 * when Space commits it. A word typed with Telex marks shows as it is. A tap takes a guess. When
 * the words before were typed without marks too, the left offers the whole run accented ("tôi
 * không biết"). With no word, the next words, or the tool row.
 */
class StripMaker(private val models: Models?, private val emoji: EmojiIndex? = null) {
    /** What a slot holds: words, an emoji to put after the word in progress, or a phrase. */
    data class Pick(val text: String, val emoji: Boolean = false, val phrase: Pair<String, String>? = null)

    private var offered: List<Pick?> = emptyList()

    /** The word in progress as the strip reads it, while there is one (for an emoji pick). */
    var centre: String? = null
        private set

    var emojiSuggestions = true

    /** Candidates at all (the settings' "suggestions"); off, the strip keeps its tools. */
    var suggestions = true

    /** The next word after a Space. */
    var nextWords = true

    /** Words and phrases the owner hid (the strip's remove mark): never offered. */
    var hidden: (String) -> Boolean = { false }

    private enum class Kind { READING, PAIR, COMPLETION }

    /** A guess: its text and score, what it is, and the reading it is or extends. */
    private data class Guess(val text: String, val score: Float, val kind: Kind, val base: String?, val corrected: Boolean)

    fun make(t: Typist): Strip {
        val word = t.shown
        if (!suggestions || word.isEmpty()) {
            offered = emptyList()
            centre = null
            return if (suggestions && nextWords && models != null && t.afterWord()) next(t) else Strip.Tools
        }
        val m = models
        val commits = t.preview()  // what Space commits, when it isn't what the field shows
        val run = if (m != null) t.phrase()?.takeUnless { hidden(it.second) } else null
        val ctx = t.context()
        // A word Telex marked, read as its letters without marks: its syllables ("mêt": mệt,
        // mết, ...), the word itself among them.
        val marked = if (m != null && !t.restorable() && !t.raw && t.autoCorrect) {
            bare(word)?.let { m.restoreWord(ctx, it, 16) }
        } else {
            null
        }
        val readings = if (m != null) readings(t, m, ctx, run, marked) else emptyList()
        val guesses = if (m != null) guesses(t, m, ctx, readings, marked, commits) else emptyList()

        // The middle: what Space commits, when that is not what the field shows; for a word
        // typed without marks, as the letters read (bareMiddle); a marked word as it is.
        val middle = commits ?: if (m != null && t.restorable()) bareMiddle(m, ctx, word, readings, guesses) else word
        val bold = middle == (commits ?: word)
        val rest = guesses.filter { it.text != middle }.toMutableList()
        // The left: the run accented, or the best other reading, alone or with its next word,
        // unless a completion is far likelier; else the best guess that isn't the middle's pair.
        val leftGuess = if (run != null) null else {
            val best = rest.firstOrNull { it.base != middle }
            rest.firstOrNull { it.kind != Kind.COMPLETION && !it.corrected && it.base != middle }
                ?.takeIf { best == null || it.score >= best.score - READING_EDGE }
                ?: best
        }
        leftGuess?.let { rest.remove(it) }
        val left = run?.let { Pick(it.second, phrase = it) } ?: leftGuess?.let { Pick(it.text) }
        // The right: the middle with its next word when that comes next, an emoji for the
        // word, or the next best guess.
        val read = commits ?: readings.firstOrNull()?.text ?: word
        centre = read
        val top = rest.firstOrNull()
        val pair = top?.takeIf { it.kind == Kind.PAIR && it.base == middle }
        val right = pair?.let { Pick(it.text) }
            ?: emojiFor(t, read)?.let { Pick(it, emoji = true) }
            ?: top?.let { Pick(it.text) }
        if (right?.emoji == false) top?.let { rest.remove(it) }
        offered = listOf(left, Pick(middle), right)
        return Strip.Candidates(left?.text, middle, right?.text, more = rest.isNotEmpty(), bold = bold, extra = rest.map { it.text })
    }

    /**
     * The middle for letters typed without marks, which Space keeps, weighed by the models:
     * - letters that are a Vietnamese syllable with a common reading, likelier as Vietnamese
     *   than as English ("cam", "ban", "an"): the best Vietnamese reading or reading with its
     *   next word ("cảm ơn"), or the word itself when that is the likeliest ("em"), never a
     *   completion ("came" and "camera" go to the sides);
     * - letters that are an English word Space keeps, likelier as English ("the", "love"): the
     *   word itself, in bold;
     * - else the best guess ("hel": hello), or the word when it is as likely or all there is
     *   are slips of it.
     */
    private fun bareMiddle(m: Models, ctx: List<String>, word: String, readings: List<Candidate>, guesses: List<Guess>): String {
        val (asVietnamese, asEnglish) = m.languages(ctx, word)
        // The letters are a Vietnamese syllable when some reading of them takes marks; the word
        // as typed is then one of its readings too ("xin", "anh").
        val marked = readings.firstOrNull { it.type == Candidate.RESTORED && it.text.any { c -> c.code >= 0x80 } }
        val vietnamese = if (marked == null) Models.NEVER else maxOf(marked.score, asVietnamese)
        if (vietnamese >= COMMON && vietnamese >= asEnglish) {
            val best = guesses.firstOrNull { it.kind != Kind.COMPLETION && !it.corrected }
            // The word itself, when it is the likeliest Vietnamese and no pair of it stands in
            // its place ("em", where "êm" is rare; but "xin lỗi" for "xin").
            val ownPair = guesses.any { it.kind == Kind.PAIR && it.base == word }
            if (!ownPair && readings.any { it.text == word } && (best == null || asVietnamese > best.score)) return word
            best?.let { return it.text }
        }
        if (asEnglish > vietnamese && m.plainWord(word)) return word
        // Nothing common either way: the best guess, unless the word itself is as likely ("em"
        // after "tôi", where "emphasized" was in the middle) or all there is are slips of it.
        val best = guesses.firstOrNull() ?: return word
        val itself = readings.firstOrNull { it.text == word }
        if (itself != null && guesses.none { it.kind == Kind.PAIR && it.base == word } && itself.score >= best.score) return word
        return if (guesses.any { !it.corrected && it.base != word }) best.text else word
    }

    /**
     * The readings of the word in progress, best first: its accented forms when it was typed
     * without marks, or the tones it may take when typed with marks but no tone ("meet" shows
     * "mêt": mệt, mết). After words typed without marks, the run's reading of it comes first.
     */
    private fun readings(
        t: Typist,
        m: Models,
        ctx: List<String>,
        run: Pair<String, String>?,
        marked: List<Candidate>?,
    ): List<Candidate> {
        val word = t.shown
        val all = when {
            t.restorable() -> m.restoreWord(ctx, t.keys(), READINGS)
            marked != null && Normalizer.normalize(word, Normalizer.Form.NFD).none { it in TONES } ->
                marked.filter { it.type == Candidate.RESTORED && untoned(it.text) == word }.take(READINGS)
            else -> emptyList()
        }
        val fromRun = run?.second?.substringAfterLast(' ') ?: return all
        val i = all.indexOfFirst { it.text == fromRun }
        if (i <= 0) return all
        return listOf(all[i].copy(score = all[0].score)) + all.filterIndexed { j, _ -> j != i }
    }

    /** Everything worth a slot, best first, but the word as the field shows it. */
    private fun guesses(
        t: Typist,
        m: Models,
        ctx: List<String>,
        readings: List<Candidate>,
        marked: List<Candidate>?,
        commits: String?,
    ): List<Guess> {
        val word = t.shown
        val bases = ArrayList(readings)
        // A word typed with marks is a reading of its own ("được"): its next word may go with it.
        if (!t.restorable() && readings.none { it.text == word }) {
            val score = marked?.firstOrNull { it.text == word }?.score
            bases += Candidate(word, Candidate.RESTORED, score ?: readings.firstOrNull()?.score ?: 0f)
        }
        // What Space commits in its place ("meet" for "mêt"), scored as a word typed as it is.
        if (commits != null && ' ' !in commits && bases.none { it.text == commits }) {
            m.restoreWord(ctx, commits, READINGS).firstOrNull { it.text == commits }?.let { bases += it }
        }
        val out = ArrayList<Guess>()
        for ((i, r) in bases.withIndex()) {
            val corrected = r.type == Candidate.CORRECTED
            val paired = i < PAIRED || r.text == word || r.text == commits
            val next = if (paired) nextWord(m, ctx, r.text) else null
            if (next != null) out += Guess("${r.text} ${next.first}", r.score + next.second, Kind.PAIR, r.text, corrected)
            // Alone, it scores what is left once its likeliest pair is counted apart.
            val alone = if (next == null) r.score else r.score + log10(1 - min(10f.pow(next.second), MOST))
            out += Guess(r.text, alone, Kind.READING, r.text, corrected)
        }
        for ((c, score) in completions(t, m, ctx)) out += Guess(c, score, Kind.COMPLETION, null, false)
        return out.filter { it.text != word && !hidden(it.text) }.sortedByDescending { it.score }.distinctBy { it.text }
    }

    /**
     * Longer words that start with what was typed: from the letters, when typed without marks;
     * for a word Telex marked, from its keys as English ("meet", shown "mêt": "meeting") and
     * from its letters with the marks it has ("ngươ": "người", "ngược", never "nguồn").
     */
    private fun completions(t: Typist, m: Models, ctx: List<String>): List<Pair<String, Float>> {
        if (t.restorable()) return m.complete(ctx, t.keys(), COMPLETIONS)
        if (t.raw || !t.autoCorrect) return emptyList()
        val out = ArrayList<Pair<String, Float>>()
        val keys = t.keys()
        if (keys.all { it in 'a'..'z' || it in 'A'..'Z' }) out += m.complete(ctx, keys, COMPLETIONS).filter { (w) -> w.all { it.code < 0x80 } }
        val word = t.shown
        bare(word)?.let { b ->
            val marked = untoned(word)
            out += m.complete(ctx, b, COMPLETIONS * 4).filter { (w) -> untoned(w).startsWith(marked) }
        }
        return out.sortedByDescending { it.second }.distinctBy { it.first }.take(COMPLETIONS)
    }

    /** The word likeliest to follow `word`, with its log10 probability, when likely enough. */
    private fun nextWord(m: Models, ctx: List<String>, word: String): Pair<String, Float>? {
        if (!word.first().isLetter()) return null
        return m.predictScored(ctx + word.lowercase(), 1).firstOrNull()?.takeIf { it.second >= COMPLETION }
    }

    /** After a word and a space: the likeliest next words, the likeliest in the middle. */
    private fun next(t: Typist): Strip {
        val words = models!!.predict(t.context(), 3 + HIDDEN_SLACK).filterNot(hidden).take(3)
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
        /** log10: how likely the next word must be to go with a reading ("cảm ơn"). */
        const val COMPLETION = -1.0f

        /** Readings looked at, the best of them that get a next word, completions asked for. */
        private const val READINGS = 6
        private const val PAIRED = 3
        private const val COMPLETIONS = 3

        /** Next words asked for beyond the three shown, for those hidden among them. */
        private const val HIDDEN_SLACK = 5

        /**
         * log10 P: how likely after the words before a Vietnamese reading must be to hold the
         * middle against completions (1 in 30,000; the readings of "cam", "an", "di" are about
         * 1 in 1,500 at a sentence's start).
         */
        private const val COMMON = -4.5f

        /** log10: how much likelier a completion must be to take the left from a reading. */
        private const val READING_EDGE = 2.0f

        /** A pair never takes all of its reading: "cám" alone stays on offer, just low. */
        private const val MOST = 0.99f

        /** The tone marks, decomposed: grave, acute, tilde, hook above, dot below. */
        private const val TONES = "̣̀́̃̉"

        /** `s` without its marks or tone, when it is letters ("được": "duoc"), else null. */
        private fun bare(s: String): String? {
            val b = Normalizer.normalize(s, Normalizer.Form.NFD).replace('đ', 'd').replace('Đ', 'D')
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            return b.takeIf { it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' } }
        }

        private fun untoned(s: String): String = Normalizer.normalize(
            Normalizer.normalize(s, Normalizer.Form.NFD).filter { it !in TONES },
            Normalizer.Form.NFC,
        )
    }
}
