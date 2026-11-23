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
 * the middle, the best other reading on the left, and on the right an emoji when the word is
 * one of the emoji keywords (data/emoji/keywords.tsv), else the middle with the word likely to
 * follow it, or the next best guess. Laban, measured on the owner's phone: "hel" help | hello |
 * held, "xin" xịn | xin lỗi | xinh, "cam" cám ơn | cảm ơn | 🍊, "meet" (which Telex shows as
 * "mêt") mệt mỏi | mệt | mệt rồi.
 *
 * The guesses, each scored log10 P(text | the words before) by the models:
 * - the readings of the word: its accented forms when it was typed without marks ("cam": cảm,
 *   cám), or the tones it may take when typed with marks but none ("mêt": mệt);
 * - a reading with the word likely to follow it ("cảm ơn"). The reading alone then scores what
 *   is left of it, "cảm" followed by anything but "ơn": a pair takes the reading's place only
 *   when it is clearly likelier, as Laban keeps the reading in the middle and the pair at the
 *   side more often than the odds alone would ("toàn" | "toàn bộ"); a weaker one comes after;
 * - completions: longer words that start with the letters typed ("hel": hello, help, "xin":
 *   xinh).
 *
 * Space keeps what was typed unless it fixes a wrong word, so the word as the field shows it
 * takes no slot of its own while there is something else to offer: the middle is the best of the
 * rest, as on Laban ("the": theo | thế | they; "ok": okay; "em": em bé), and a word finished with
 * its Telex marks gives way to what may follow it ("banj": bạn bè | bạn gái | bạn ấy). Letters
 * that are a common Vietnamese syllable keep the middle Vietnamese, as Laban does: "cam" is
 * "cảm ơn" there, and English completions go to the sides. When Space commits something other
 * than what the field shows ("teh" -> "the"), that is in the middle. The middle is in bold only
 * when Space commits it. A tap takes a guess. When the words before were typed without marks
 * too, the left offers the whole run accented ("tôi không biết"). With one word to offer, a
 * second emoji takes the left; with none, the emoji fill the strip from the right, five across
 * it when there are four or more ("haha"). With no word in progress, the next words, or the tool
 * row.
 */
class StripMaker(private val models: Models?, private val emoji: EmojiIndex? = null) {
    /**
     * What a slot holds: words, a phrase, or an emoji. An emoji goes after [word] (the word in
     * progress, committed so, and a space: "ok 👌"), or at the cursor when there is no word in
     * progress; or, when [replaces] is set, in place of the word in progress and of that text
     * before it ("cam" -> "🍊", "cà phê" -> "☕", ":)" -> "🙂").
     */
    data class Pick(
        val text: String,
        val emoji: Boolean = false,
        val phrase: Pair<String, String>? = null,
        val word: String? = null,
        val replaces: String? = null,
    )

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

    /**
     * A guess: its text and score, what it is, and the reading it is or extends; for a pair, how
     * likely its next word is after the reading.
     */
    private data class Guess(
        val text: String,
        val score: Float,
        val kind: Kind,
        val base: String?,
        val corrected: Boolean,
        val follows: Float = 0f,
    )

    fun make(t: Typist): Strip {
        val word = t.shown
        if (!suggestions || word.isEmpty()) {
            offered = emptyList()
            centre = null
            if (!suggestions) return Strip.Tools
            val strip = if (nextWords && models != null && t.afterWord()) next(t) else Strip.Tools
            // An emoji for the words just ended ("chúc mừng sinh nhật ") or an emoticon (":)").
            val hit = emojiFor(t, null)
            val picks = hit.picks
            if (picks.isEmpty()) return strip
            val words = offered.take(2)
            // A wish or a thanks of several words just ended ("chúc mừng sinh nhật ", "cảm ơn bạn
            // nhiều ") is answered with emoji: they fill the strip, as on Laban Key. After a
            // shorter keyword ("ok ", "cảm ơn ") the next words keep the left and the centre.
            if (words.none { it != null } || hit.words >= WHOLE) return emojiOnly(picks)
            offered = words + picks[0]
            // The next words keep what their slots show (a phrase, its typed part grey).
            val next = strip as Strip.Candidates
            return Strip.Candidates(next.left, next.centre, picks[0].text, bold = false, grey = next.grey.take(2))
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
        // A word the cursor was put inside, with a word after it: what it may be, never a word
        // to put between it and the next.
        val guesses = if (m == null) emptyList() else guesses(t, m, ctx, readings, marked, commits)
            .filterNot { t.followed && it.kind == Kind.PAIR }
        val picks = emojiFor(t, commits).picks
        // A word finished with its marks, which Space commits as it stands ("banj": bạn): the
        // strip offers what comes after it, as Laban Key does, not the word again.
        if (m != null && commits == null) {
            finished(t, word, marked)?.let {
                centre = word
                val follow = if (t.followed) emptyList() else continuations(m, ctx, word, it)
                return alternatives(word, follow, guesses, picks, null, vietnamese = false)
            }
        }

        // The middle: what Space commits, when that is not what the field shows; for a word
        // typed without marks, as the letters read (bareMiddle); a marked word as it is.
        val middle = commits ?: if (m != null && t.restorable()) bareMiddle(m, ctx, word, readings, guesses) else word
        centre = commits ?: readings.firstOrNull()?.text ?: word
        // That is the word as it stands, which Space commits anyway ("em", "ok", "love", "ngươ"):
        // the middle goes to what else it may be, as on Laban Key.
        if (m != null && commits == null && middle == word) {
            // Letters that are a Vietnamese syllable, common or likelier so than as English, keep
            // the middle Vietnamese ("an" after "meet": anh, not "and"; "the": theo, thế; "em"
            // after "tôi": not "emphasized").
            val (asVietnamese, asEnglish) = m.languages(ctx, word)
            val syllable = if (t.restorable()) vietnamese(readings, asVietnamese) else Models.NEVER
            val vietnamese = syllable > Models.NEVER && (syllable >= COMMON || syllable >= asEnglish)
            // A Vietnamese word as typed ("em") goes on as Laban Key's do, with the words that go
            // with it ("em gái", "em bé"; not "em có", "em không", which follow anything).
            val itself = readings.firstOrNull { it.text == word }?.score
            if (itself != null && itself >= FINISHED && syllable >= COMMON && syllable >= asEnglish && m.plainWord(word)) {
                val follow = if (t.followed) emptyList() else {
                    continuations(m, ctx, word, itself).filter { goesWith(m, word, it.text.substringAfter(' ')) }
                }
                return alternatives(word, follow, guesses, picks, run, vietnamese)
            }
            // An English word ("love", "ok", "thanks") goes on only with a word that nearly always
            // follows it ("thanks to", "good luck"): the English model learnt pairs from written
            // text, where "love island" and "wow factor" are the likeliest.
            val own = if (!t.restorable()) guesses else guesses.filterNot { it.kind == Kind.PAIR && it.base == word && it.follows < SURE }
            return alternatives(word, emptyList(), own, picks, run, vietnamese)
        }
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
        // With no other word, a second emoji takes the left ("sorry": 🙏 | sorry | 😔).
        val left = run?.let { Pick(it.second, phrase = it) } ?: leftGuess?.let { Pick(it.text) } ?: picks.getOrNull(1)
        // The right: an emoji when the word is one of their keywords, else the next best guess.
        val top = rest.firstOrNull()
        val right = picks.firstOrNull() ?: top?.let { Pick(it.text) }
        if (right?.emoji == false) top?.let { rest.remove(it) }
        offered = listOf(left, Pick(middle), right)
        return Strip.Candidates(
            offered[0]?.text, middle, offered[2]?.text, more = rest.isNotEmpty(), bold = middle == (commits ?: word),
            extra = rest.map { it.text }, grey = greys(word, offered.map { it?.text }),
        )
    }

    /**
     * The score of the word in progress when it is a word finished with its marks, which Space
     * commits as it stands: a word the models know ("bạn", "không"), not letters on the way to
     * one ("ngươ"), nor a word typed without marks (its readings wait in the strip).
     */
    private fun finished(t: Typist, word: String, marked: List<Candidate>?): Float? {
        if (t.restorable() || word.all { it.code < 0x80 }) return null
        return marked?.firstOrNull { it.text == word }?.score?.takeIf { it >= FINISHED }
    }

    /**
     * The strip when Space commits the word as it stands and the middle would only repeat it: a
     * word finished with its marks ("banj": bạn), letters that are a word as typed ("em", "ok",
     * "the"), or letters on their way to one. The slots offer what else there is, the best in the
     * middle, as Laban Key does: what may follow the word ([follow], phrases that start with it:
     * "bạn" -> bạn bè, bạn gái, bạn ấy), its other readings and tones, and the longer words it
     * may still become ("khôn" -> không, "ok" -> okay), ranked by how likely the whole text is in
     * context. For letters that are a Vietnamese syllable ([vietnamese]) the middle stays
     * Vietnamese ("em": not "email"). The left is the run accented, when there is one. With one
     * word, a second emoji takes the left; with none, the emoji fill the strip. The word itself
     * only when there is nothing else, in bold: Space commits it.
     */
    private fun alternatives(
        word: String,
        follow: List<Guess>,
        guesses: List<Guess>,
        picks: List<Pick>,
        run: Pair<String, String>?,
        vietnamese: Boolean,
    ): Strip {
        // The guesses pair the word with the word likeliest after it with the sentence's start in
        // full; `follow` has that pair, ranked as the others are.
        val own = if (follow.isEmpty()) guesses else guesses.filterNot { it.kind == Kind.PAIR && it.base == word }
        val pool = (follow + own).filter { it.text != word && it.text != run?.second }
            .sortedByDescending { it.score }.distinctBy { it.text }.toMutableList()
        val best = pool.firstOrNull { !vietnamese || it.kind != Kind.COMPLETION || it.text.any { c -> c.code >= 0x80 } }
            ?: pool.firstOrNull()
        best?.let { pool.remove(it) }
        val left = run?.let { Pick(it.second, phrase = it) } ?: pool.removeFirstOrNull()?.let { Pick(it.text) }
        offered = when {
            best != null -> listOf(
                left ?: picks.getOrNull(1),
                Pick(best.text),
                picks.firstOrNull() ?: pool.removeFirstOrNull()?.let { Pick(it.text) },
            )
            // Nothing to offer but the word itself: the emoji fill the strip from the right.
            left == null && picks.size > 1 -> return emojiOnly(picks)
            else -> listOf(left, Pick(word), picks.firstOrNull())
        }
        val middle = offered[1]!!.text
        return Strip.Candidates(
            offered[0]?.text, middle, offered[2]?.text, more = pool.isNotEmpty(), bold = middle == word,
            extra = pool.map { it.text }, grey = greys(word, offered.map { it?.text }),
        )
    }

    /**
     * What may follow `word` (its own score `score`), as phrases that start with it, scored as
     * the whole text: "bạn" -> bạn bè, bạn gái, bạn ấy.
     */
    private fun continuations(m: Models, ctx: List<String>, word: String, score: Float): List<Guess> =
        following(m, ctx + word.lowercase(), CONTINUATIONS)
            .filter { (w, p) -> p >= FOLLOWS && isWord(w) && !hidden("$word $w") }
            .map { (w, p) -> Guess("$word $w", score + p, Kind.PAIR, word, false, p) }

    /** Emoji in every slot, the first on the right: three slots ("hx": 😭 | 😿 | 😢), or a row of five. */
    private fun emojiOnly(picks: List<Pick>): Strip {
        if (picks.size >= ROW_FROM) {
            offered = List(Strip.Candidates.ROW) { picks.getOrNull(Strip.Candidates.ROW - 1 - it) }
            return Strip.Candidates(null, null, null, bold = false, row = offered.map { it?.text })
        }
        offered = listOf(picks.getOrNull(2), picks.getOrNull(1), picks[0])
        return Strip.Candidates(offered[0]?.text, offered[1]?.text, offered[2]?.text, bold = false)
    }

    /**
     * The words likeliest to follow `ctx`, with their log10 probabilities, likeliest first: as
     * [Models.predictScored] ranks them, but at a sentence's start ("<s>" first) a word's chance
     * there counts for [START_WEIGHT] of it, the rest its chance after the same words anywhere.
     * The models learnt how sentences start from written text, where "Làm thế nào", "Ngày nay"
     * and "Bạn có thể" open them, and in full that continued the first word typed that way
     * ("làm thế", "ngày nay", "nhà máy") before the phrases it makes anywhere ("làm việc",
     * "ngày càng", "nhà nước").
     */
    private fun following(m: Models, ctx: List<String>, max: Int): List<Pair<String, Float>> {
        val here = m.predictScored(ctx, max)
        if (ctx.size < 2 || ctx[0] != "<s>") return here
        val anywhere = ctx.drop(1)
        val there = m.predictScored(anywhere, max)
        // A word one list leaves out is scored there as the models score it (without the owner's
        // own words, which lift a word after the same word before in both lists alike).
        fun chance(list: List<Pair<String, Float>>, c: List<String>, w: String): Double =
            10.0.pow(list.firstOrNull { it.first == w }?.second?.toDouble() ?: both(m.languages(c, w)))
        return (here + there).map { it.first }.distinct().map { w ->
            w to log10(START_WEIGHT * chance(here, ctx, w) + (1 - START_WEIGHT) * chance(there, anywhere, w)).toFloat()
        }.sortedByDescending { it.second }.take(max)
    }

    /** For each slot, how much of it is the word in the field: "bạn" of "bạn bè" while "bạn" is typed. */
    private fun greys(word: String, slots: List<String?>): List<Int> =
        slots.map { if (it != null && it.length > word.length + 1 && it.startsWith("$word ")) word.length else 0 }

    /**
     * The middle for letters typed without marks, which Space keeps, weighed by the models:
     * - letters that are a Vietnamese syllable with a common reading, likelier as Vietnamese
     *   than as English ("cam", "ban", "an"): the best Vietnamese reading or reading with its
     *   next word ("cảm ơn"), a longer Vietnamese word when that is likelier ("vi": việc, "ngu":
     *   người, as on Laban), or the word itself when that is the likeliest ("em"), never an
     *   English completion ("came" and "camera" go to the sides);
     * - letters that are an English word Space keeps, likelier as English ("the", "love"): the
     *   word itself;
     * - else the best guess ("hel": hello), or the word when it is as likely or all there is
     *   are slips of it.
     * The word itself is what Space commits anyway: its slot goes to the best of the rest
     * ([alternatives]).
     */
    private fun bareMiddle(m: Models, ctx: List<String>, word: String, readings: List<Candidate>, guesses: List<Guess>): String {
        val (asVietnamese, asEnglish) = m.languages(ctx, word)
        val vietnamese = vietnamese(readings, asVietnamese)
        if (vietnamese >= COMMON && vietnamese >= asEnglish) {
            val best = guesses.firstOrNull { (it.kind != Kind.COMPLETION || it.text.any { c -> c.code >= 0x80 }) && !it.corrected }
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
     * How likely letters are as Vietnamese: when some reading of them takes marks they are a
     * syllable, and the word as typed is then one of its readings too ("xin", "anh").
     */
    private fun vietnamese(readings: List<Candidate>, asVietnamese: Float): Float {
        val marked = readings.firstOrNull { it.type == Candidate.RESTORED && it.text.any { c -> c.code >= 0x80 } }
        return if (marked == null) Models.NEVER else maxOf(marked.score, asVietnamese)
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
            // A word the cursor was put inside, with marks: its other readings, to fix it with.
            marked != null && t.followed -> marked.filter { it.type == Candidate.RESTORED }.take(READINGS)
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
            if (next != null) out += Guess("${r.text} ${next.first}", r.score + next.second - PAIR_MARGIN, Kind.PAIR, r.text, corrected, next.second)
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
     * from its letters with the marks it has ("ngươ": "người", "ngược", never "nguồn"), and with
     * its tone once it has one ("gì": "giờ", never "giá"; "nó": never "now").
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
            val tone = toneOf(word)
            out += m.complete(ctx, b, COMPLETIONS * 4).filter { (w) -> untoned(w).startsWith(marked) && (tone == null || toneOf(w) == tone) }
        }
        return out.sortedByDescending { it.second }.distinctBy { it.first }.take(COMPLETIONS)
    }

    /** The word likeliest to follow `word`, with its log10 probability, when likely enough. */
    private fun nextWord(m: Models, ctx: List<String>, word: String): Pair<String, Float>? {
        if (!word.first().isLetter()) return null
        return m.predictScored(ctx + word.lowercase(), 1).firstOrNull()?.takeIf { it.second >= COMPLETION && isWord(it.first) }
    }

    /**
     * After a word and a space: the likeliest next words in context (the two words before, as
     * [readContext] reads them), the likeliest in the middle. One that goes with the word before
     * as a phrase shows with it, that word in grey, as Laban Key mixes them: "một" -> một số,
     * một mình, ..., but "tôi" -> về, đi. A tap adds the next word. A word hidden with the remove
     * mark, alone or as its phrase, is left out.
     */
    private fun next(t: Typist): Strip {
        val m = models!!
        val ctx = readContext(m, t)
        // A word typed without marks makes a phrase only when it reads as it is typed: after
        // "trời đẹp qua" ("quá"), "qua đời" is not what was meant, and the field keeps "qua".
        val last = t.wordBefore()?.takeIf { w -> w.any { it.isLetter() } && w.equals(ctx.lastOrNull(), ignoreCase = true) }
        // Each next word with what its slot shows: the word, or the phrase it makes.
        val words = m.predict(ctx, 3 + HIDDEN_SLACK).asSequence().filterNot(hidden)
            .map { w -> w to if (last != null && goesWith(m, last, w)) "$last $w" else w }
            .filterNot { hidden(it.second) }.take(3).toList()
        if (words.isEmpty()) return Strip.Tools
        val order = listOf(words.getOrNull(1), words[0], words.getOrNull(2))
        offered = order.map { w -> w?.let { Pick(it.first) } }
        val shown = order.map { it?.second }
        return Strip.Candidates(shown[0], shown[1], shown[2], bold = false, grey = last?.let { greys(it, shown) } ?: emptyList())
    }

    /**
     * The words before the cursor as the models read them ([Typist.context]), with the words at
     * the end of the sentence typed without marks read together as they most likely stand
     * ("chuc mung sinh nhat": "chúc mừng sinh nhật"; "i love you" stays). As letters, the
     * Vietnamese model has rarely seen them, and what came next was English ("and", "tag"). A
     * sentence of one such word that is a word as it stands stays so: with nothing around it,
     * "bao" is as likely "bao" (bao giờ) as "báo"; "khoc" is "khóc".
     */
    private fun readContext(m: Models, t: Typist): List<String> {
        val ctx = t.context()
        val bare = ctx.takeLastWhile { it != "<s>" && plain(it) }.size
        if (bare == 0) return ctx
        val text = t.before()
        val sentence = text.substring(text.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' } + 1)
        val run = WORD.findAll(sentence).map { it.value }.toList().takeLastWhile(::plain).takeLast(RUN)
        if (run.size < bare || (run.size == 1 && t.context(1).all { it == "<s>" } && m.plainWord(run[0]))) return ctx
        val read = m.restoreRun(t.context(run.size), run)?.takeIf { it.size == run.size } ?: return ctx
        return ctx.dropLast(bare) + read.takeLast(bare)
    }

    /**
     * `next` goes with `last` as a phrase ("một số", "người dùng"): Vietnamese, likely right
     * after it (1 in 100 or more), and much likelier there than after a comma, so a word that
     * follows anything ("có", "đã") stays on its own.
     */
    private fun goesWith(m: Models, last: String, next: String): Boolean {
        val after = m.languages(listOf(last.lowercase()), next)
        if (after.first < after.second) return false
        val p = both(after)
        return p >= GOES_WITH && p - both(m.languages(listOf("<p>"), next)) >= GOES_WITH_LIFT
    }

    /** The emoji for what was just typed, best first, and how many words their keyword has. */
    private class Hit(val picks: List<Pick>, val words: Int)

    /**
     * The emoji for what was just typed, best first, as Laban Key offers them: only when it is
     * one of the keywords of data/emoji/keywords.tsv as it stands (the word in progress as the
     * field shows it, typed or marked, or as Space ends it: "sory" ends "sorry"), or ends a
     * phrase that is ("chúc mừng sinh nhật", also typed without marks). With no word in
     * progress, what was typed last: the words just ended by a space, whose emoji go at the
     * cursor, or an emoticon (":)").
     */
    private fun emojiFor(t: Typist, commits: String?): Hit =
        keywordEmoji(t, commits).let { h -> Hit(h.picks.filterNot { hidden(it.text) }, h.words) }  // an emoji the owner hid stays hidden

    private fun keywordEmoji(t: Typist, commits: String?): Hit {
        val none = Hit(emptyList(), 0)
        val index = emoji ?: return none
        if (!emojiSuggestions || t.raw) return none
        val line = t.before().substringAfterLast('\n')
        val word = t.shown
        if (word.isNotEmpty()) {
            // The words before the word in progress, when a space parts them from it.
            val earlier = if (line.isEmpty() || line.last() == ' ') words(line).takeLast(PHRASE - 1) else emptyList()
            for (w in listOfNotNull(word, commits?.takeIf { it != word && ' ' !in it })) {
                val hit = index.keyword(earlier + w, MOST_EMOJI) ?: continue
                val typed = earlier.takeLast(hit.words - 1).joinToString("") { "$it " }
                return Hit(hit.emoji.map {
                    if (hit.replace) Pick(it, emoji = true, replaces = typed) else Pick(it, emoji = true, word = w)
                }, hit.words)
            }
            return none
        }
        val ended = words(line).takeLast(PHRASE)
        if (ended.isEmpty()) return none
        val hit = index.keyword(ended, MOST_EMOJI) ?: return none
        // After a space the words stay; an emoticon typed just now gives way.
        val replaces = ended.takeLast(hit.words).joinToString(" ").takeIf { hit.replace && !line.last().isWhitespace() }
        return Hit(hit.emoji.map { Pick(it, emoji = true, replaces = replaces) }, hit.words)
    }

    /** The strip's emoji for what was just typed, for the emoji panel's row. */
    fun emoji(t: Typist): List<String> = emojiFor(t, if (t.shown.isEmpty()) null else t.preview()).picks.map { it.text }

    /** An emoji the strip offered, put in the field as its keyword says. */
    fun putEmoji(t: Typist, pick: Pick) {
        val replaces = pick.replaces
        val word = pick.word
        when {
            replaces != null -> t.replaceWith(pick.text, replaces)
            word != null && t.shown.isNotEmpty() -> {
                t.pick(word)
                // A pick inside the text adds no space where one follows: the emoji gets its own.
                t.insert(if (t.before().lastOrNull()?.isWhitespace() == false) " ${pick.text}" else pick.text)
            }
            t.shown.isEmpty() && t.before().lastOrNull()?.isWhitespace() == false -> t.insert(" ${pick.text}")
            else -> t.insert(pick.text)
        }
    }

    /** The candidate in slot 0 (left), 1 (centre) or 2 (right), or in a row's place 0 to 4. */
    fun pick(slot: Int): Pick? = offered.getOrNull(slot)

    companion object {
        /** log10: how likely the next word must be to go with a reading ("cảm ơn"): 1 in 20. */
        const val COMPLETION = -1.3f

        /**
         * log10: how much likelier than its reading alone a reading with its next word must be to
         * rank above it (a quarter): "cảm ơn" still does, "toàn bộ" no longer takes "toàn"'s place.
         */
        private const val PAIR_MARGIN = 0.1f

        /** log10: how likely a word must be to count as finished ("khôn" is; "ngươ" is no word). */
        private const val FINISHED = -6.0f

        /** Words asked for after a finished word, and how likely each must be to follow it. */
        private const val CONTINUATIONS = 10
        private const val FOLLOWS = -3.0f

        /**
         * How much a sentence's start counts in what follows a word finished there ([following]).
         * Chosen against Laban Key's continuations on half of the study's words and checked on
         * the other half: 0.05 to 0.15 did about as well, the start in full (1) or not at all (0)
         * worse. The next words after a Space keep the start in full: there it helped after
         * Vietnamese words but not after English ones.
         */
        private const val START_WEIGHT = 0.075

        /**
         * log10: how likely a next word must be after the word before to show with it, and how
         * much likelier there than after a comma (measured against Laban's after-Space strips).
         */
        private const val GOES_WITH = -2.0
        private const val GOES_WITH_LIFT = 1.0

        /** Readings looked at, the best of them that get a next word, completions asked for. */
        private const val READINGS = 6

        /** The most words an emoji keyword has ("chúc mừng sinh nhật"). */
        private const val PHRASE = 4

        /** The most emoji a keyword offers: a row's places. */
        private const val MOST_EMOJI = Strip.Candidates.ROW

        /** How many emoji fill the strip as a row of five, not three slots. */
        private const val ROW_FROM = 4

        /**
         * The words a keyword must have to fill the strip with emoji after its Space: a wish or a
         * thanks said whole ("chúc mừng sinh nhật", "cảm ơn bạn nhiều", "anh yêu em").
         */
        private const val WHOLE = 3

        /** The most words typed without marks read together for what follows them (the engine's). */
        private const val RUN = 8

        private val WORD = Regex("[\\p{L}\\p{N}]+")

        /**
         * A next word worth a phrase: a word of letters, not a letter alone that the models keep
         * from "'s" or an initial ("night s"), unless it has marks ("ở").
         */
        private fun isWord(w: String): Boolean = w.first().isLetter() && (w.length > 1 || w[0].code >= 0x80)

        /** Letters without marks, a-z and A-Z: as a word typed bare is. */
        private fun plain(w: String): Boolean = w.isNotEmpty() && w.all { it in 'a'..'z' || it in 'A'..'Z' }

        private fun words(line: String): List<String> = line.split(' ').filter { it.isNotEmpty() }

        /** log10 of a word's chance as Vietnamese and as English together ([Models.languages]). */
        private fun both(p: Pair<Float, Float>): Double = log10(10.0.pow(p.first.toDouble()) + 10.0.pow(p.second.toDouble()))

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

        /** log10: how likely an English word's next word must be to show with it (1 in 6). */
        private const val SURE = -0.8f

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

        /** The tone mark of `s`, decomposed, or null for none ("gì": the grave). */
        private fun toneOf(s: String): Char? = Normalizer.normalize(s, Normalizer.Form.NFD).firstOrNull { it in TONES }

        private fun untoned(s: String): String = Normalizer.normalize(
            Normalizer.normalize(s, Normalizer.Form.NFD).filter { it !in TONES },
            Normalizer.Form.NFC,
        )
    }
}
