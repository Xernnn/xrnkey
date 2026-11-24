package com.xern.xrnkey.ja

/**
 * A candidate for the reading typed: its text, and how many characters of the reading it stands
 * for. A candidate for only the reading's start (`length` shorter than it) leaves the rest to be
 * converted after it, piece by piece; `rid` is the part of speech it ends with, which the rest
 * (or the next word) follows (0: as a sentence's start). [segments] are the words it is made of,
 * as the owner's choices are learned (none: nothing to learn, such as Latin letters).
 */
data class Candidate(val text: String, val length: Int, val rid: Int = 0) {
    var segments: List<Converter.Segment> = emptyList()
        private set

    constructor(text: String, length: Int, rid: Int, segments: List<Converter.Segment>) : this(text, length, rid) {
        this.segments = segments
    }
}

/**
 * What a reading may be converted to ([Converter.convert]):
 * - [best]: what Space shows first, the cheapest way through the whole reading
 *   ("わたしはがくせいです": 私は学生です), where a word the owner chose costs less;
 * - [hiragana] and [katakana]: the reading as it is, in either;
 * - [words]: the words read exactly so, the owner's choices first, then the likeliest ("かみ":
 *   紙, 神, 髪 ...);
 * - [pieces]: words for its start, for converting piece by piece, the likeliest way on first;
 * - [predictions]: words whose reading starts with it and goes on, the owner's first ("ありが":
 *   ありがとう, ありがとうございます);
 * - [offered]: the strip's order: [best], then the owner's choices, then the likeliest of the
 *   rest, words, the start converted another way and predictions alike, each text once; the kana
 *   and the pieces after the first dozen;
 * - [cycle]: Space's order: the same without the predictions, which are not conversions; a start
 *   converted another way as its piece, shown with the rest after it.
 */
class Conversion(
    val reading: String,
    val left: Int,
    val best: Candidate,
    val hiragana: Candidate,
    val katakana: Candidate,
    val wide: Candidate?,
    val words: List<Candidate>,
    val pieces: List<Candidate>,
    val predictions: List<Candidate>,
    val offered: List<Candidate>,
    cycleOf: () -> List<Candidate>,
) {
    /** Worked out on the first Space only: the field shows each piece with the rest converted. */
    val cycle: List<Candidate> by lazy(cycleOf)
}

/**
 * Converts kana to kanji with the dictionary: a lattice of the words each part of the reading
 * may be, and the cheapest way through it (Viterbi), each word's cost plus the cost of its part
 * of speech after the one before (Mozc's connection costs). Text no word reads (Latin letters,
 * digits, punctuation, a kana no word has) stands for itself.
 *
 * With the owner's [history], a word chosen before costs less than the others read like it, so it
 * wins for its reading and inside longer ones; it is offered first, predicted from the start of
 * its reading, and offered again after the word it followed ([next]).
 */
class Converter(private val dict: Dictionary) {
    /**
     * A segment of a conversion: the word, the part of the reading it stands for, the parts of
     * speech it ends and starts with, and whether it is a word (false: text standing for itself).
     */
    data class Segment(val surface: String, val reading: String, val rid: Int, val lid: Int = rid, val word: Boolean = true)

    /** The cheapest way through a reading, and what it costs from the word before to the end. */
    class Path(val segments: List<Segment>, val cost: Int) {
        val text: String get() = segments.joinToString("") { it.surface }
        val rid: Int get() = segments.lastOrNull()?.rid ?: 0
    }

    private class Node(val cost: Int, val rid: Int, val back: Node?, val segment: Segment?)

    /** A whole candidate and its cost; a conversion of the start another way has its piece too. */
    private class Scored(val candidate: Candidate, val cost: Int, val prediction: Boolean = false, val piece: Candidate? = null)

    /** The owner's choices (null: none are used). */
    var history: UserHistory? = null
        set(value) {
            field = value
            value?.let(::matchDictionary)
        }

    /**
     * The owner's own words (null: none), the dictionary's words for their readings: each costs
     * [USER_MARGIN] less than the cheapest word the dictionary has read so (nothing, where it has
     * none), so it wins its reading, alone and inside longer ones.
     */
    var userWords: UserDictionary? = null

    /** Which build of the dictionary this is, as [UserHistory.stamp] keeps it. */
    private val stamp = dict.stamp

    private var cached: Conversion? = null
    private var cachedPrev: String? = null
    private var cachedVersion = -1
    private var cachedWords = -1

    /** The words read `reading`: the dictionary's, and the owner's own as words of it. */
    private fun lookup(reading: String): List<Word> {
        val found = dict.lookup(reading)
        val own = userWords?.of(reading).orEmpty()
        return if (own.isEmpty()) found else withOwn(found, own)
    }

    /** The dictionary's words for one reading, with the owner's for it as words of a noun's part of speech. */
    private fun withOwn(found: List<Word>, own: List<UserDictionary.Entry>): List<Word> {
        val noun = dict.literal(Literal.NOUN)
        val cost = maxOf(0, (found.minOfOrNull { it.cost } ?: 0) - USER_MARGIN)
        val mine = own.map { Word(it.reading, it.surface, noun, noun, cost) }
        val written = mine.mapTo(HashSet()) { it.surface }
        return mine + found.filter { it.surface !in written }
    }

    /** The words that may start at `i` of `text`: the dictionary's and the owner's, shortest reading first. */
    private fun prefixes(text: String, i: Int): List<Pair<Int, List<Word>>> {
        val found = dict.prefixes(text, i)
        val own = userWords?.at(text, i).orEmpty()
        if (own.isEmpty()) return found
        val byLength = found.toMap(java.util.TreeMap())
        for ((length, entries) in own) byLength[length] = withOwn(byLength[length].orEmpty(), entries)
        return byLength.toList()
    }

    /**
     * The cheapest conversion of `reading`, segment by segment, after a word that ends with the
     * part of speech `left` (0: at a sentence's start).
     */
    fun best(reading: String, left: Int = 0): List<Segment> = path(reading, left).segments

    /** The cheapest conversion of `reading` after `left`, and its cost to the sentence's end. */
    fun path(reading: String, left: Int = 0): Path = path(Lattice(reading), 0, left)

    /** The words each place of a reading starts, looked up once for all the ways through it. */
    private inner class Lattice(val reading: String) {
        private val at = arrayOfNulls<List<Pair<Int, List<Word>>>>(reading.length)

        fun prefixes(i: Int): List<Pair<Int, List<Word>>> = at[i] ?: prefixes(reading, i).also { at[i] = it }
    }

    /** The cheapest way through the lattice's reading from `from` to its end, after `left`. */
    private fun path(lattice: Lattice, from: Int, left: Int): Path {
        val reading = lattice.reading
        val n = reading.length
        if (from >= n) return Path(emptyList(), dict.connection(left, 0))
        // The cheapest node ending at each position for each part of speech it ends with: the
        // cost of what follows depends only on that. In the order they came, so that of two as
        // cheap the first wins, as in build_dict.py's convert() (Mozc's evaluation set: 505, not 503).
        val ends = Array(n + 1) { LinkedHashMap<Int, Node>() }
        ends[from][left] = Node(0, left, null, null)
        for (i in from until n) {
            val before = ends[i].values
            if (before.isEmpty()) continue
            val bestFor = HashMap<Int, Node>()
            fun from(lid: Int): Node = bestFor.getOrPut(lid) {
                before.minBy { it.cost + dict.connection(it.rid, lid) }
            }
            fun add(length: Int, surface: String, read: String, lid: Int, rid: Int, cost: Int, word: Boolean = true) {
                val prev = from(lid)
                val total = prev.cost + dict.connection(prev.rid, lid) + cost
                val at = ends[i + length]
                val old = at[rid]
                if (old == null || total < old.cost) at[rid] = Node(total, rid, prev, Segment(surface, read, rid, lid, word))
            }
            if (Dictionary.kanaCode(reading[i]) != 0) {
                for ((length, words) in lattice.prefixes(i)) {
                    if (history?.has(words[0].reading) == true) {
                        for ((w, cost) in costed(words)) add(length, w.surface, w.reading, w.lid, w.rid, cost)
                    } else {
                        for (w in words) add(length, w.surface, w.reading, w.lid, w.rid, w.cost)
                    }
                }
                // A kana no word starts with stands for itself, dearly.
                val noun = dict.literal(Literal.NOUN)
                val ch = reading[i].toString()
                add(1, ch, ch, noun, noun, UNKNOWN_KANA, word = false)
            } else {
                val kind = literalKind(reading[i])
                var length = 1
                if (kind == Literal.NUMBER || kind == Literal.ALPHABET) {
                    while (i + length < n && literalKind(reading[i + length]) == kind) length++
                }
                val id = dict.literal(kind)
                val text = reading.substring(i, i + length)
                add(length, text, text, id, id, 0, word = false)
            }
        }
        val out = ArrayList<Segment>()
        val last = ends[n].values.minBy { it.cost + dict.connection(it.rid, 0) }
        var node: Node? = last
        while (node?.back != null) {
            out += node.segment!!
            node = node.back
        }
        return Path(out.asReversed(), last.cost + dict.connection(last.rid, 0))
    }

    /** The cheapest conversion of `reading` after `left`, as one text. */
    fun bestText(reading: String, left: Int = 0): String = path(reading, left).text

    /**
     * The words of one reading with their costs: as the dictionary has them, but a word the owner
     * chose for it costs less than every other word read so (more, the more often it was
     * chosen), so it wins wherever the reading is that word; of two chosen, the later. A chosen
     * form the dictionary lacks for it (the reading in katakana, say) is added, as cheap.
     */
    private fun costed(words: List<Word>): List<Pair<Word, Int>> {
        val h = history
        val learned = if (h == null || words.isEmpty()) emptyList() else h.of(words[0].reading)
        if (learned.isEmpty()) return words.map { it to it.cost }
        val out = words.mapTo(ArrayList(words.size + learned.size)) { it to it.cost }
        val chosen = learned.mapTo(HashSet()) { it.surface }
        val others = words.filter { it.surface !in chosen }.minOfOrNull { it.cost } ?: words.minOf { it.cost }
        val base = maxOf(0, others - margin(learned.maxOf { it.count }))
        // The latest choice last, and cheapest.
        for ((k, e) in learned.asReversed().withIndex()) {
            val target = base - RECENT * k
            val same = words.indices.filter { words[it].surface == e.surface }
            if (same.isEmpty()) {
                out += Word(e.reading, e.surface, e.lid, e.rid, maxOf(0, target)) to target
                continue
            }
            // The word with the parts of speech it was chosen with, else its likeliest.
            val exact = same.filter { words[it].lid == e.lid && words[it].rid == e.rid }
            for (i in exact.ifEmpty { listOf(same.minBy { words[it].cost }) }) out[i] = words[i] to minOf(words[i].cost, target)
        }
        return out
    }

    /** Every candidate for `reading` after `left`, and after the word `prev` (the last one asked for is kept). */
    fun convert(reading: String, left: Int = 0, prev: String? = null): Conversion {
        val version = history?.version ?: -1
        val ownVersion = userWords?.version ?: -1
        cached?.let {
            if (it.reading == reading && it.left == left && cachedPrev == prev && cachedVersion == version && cachedWords == ownVersion) return it
        }
        val n = reading.length
        val kana = reading.all { Dictionary.kanaCode(it) != 0 }
        val h = history?.takeIf { kana }
        val lattice = Lattice(reading)
        val path = path(lattice, 0, left)
        val hiragana = kanaForm(reading, reading, n)
        val katakana = kanaForm(reading, Dictionary.katakana(reading), n)
        val wide = wide(reading).takeIf { it != reading }?.let { Candidate(it, n) }

        fun whole(text: String, w: Word) = Candidate(text, n, w.rid, listOf(Segment(w.surface, w.reading, w.rid, w.lid)))
        fun whole(e: UserHistory.Entry) = Candidate(e.surface, n, e.rid, listOf(Segment(e.surface, e.reading, e.rid, e.lid)))
        fun alone(w: Word, cost: Int) = dict.connection(left, w.lid) + cost + dict.connection(w.rid, 0)

        // What the owner chose: after the word before, for this reading, and for readings going on from it.
        val chosenAfter = if (h != null && prev != null) {
            h.after(prev, UserHistory.MOST).filter { it.reading.startsWith(reading) }.take(LEARNED).map(::whole)
        } else {
            emptyList()
        }
        val chosen = h?.of(reading)?.map(::whole).orEmpty()
        val chosenOn = h?.startingWith(reading, LEARNED)?.map(::whole).orEmpty()

        // A reading no word has keeps the word chosen for it first: the lattice can't hold it.
        val dictWords = if (kana) costed(lookup(reading)) else emptyList()
        val fromPath = Candidate(path.text, n, path.rid, path.segments)
        val best = chosen.firstOrNull()?.takeIf { dictWords.isEmpty() } ?: fromPath

        // Everything else, by what it costs as a whole, from the word before to the sentence's end.
        val scored = ArrayList<Scored>()
        scored += Scored(fromPath, path.cost)
        for ((w, cost) in dictWords) scored += Scored(whole(w.surface, w), alone(w, cost))

        // The start converted another way, the rest the cheapest way after it ("こんにちは":
        // 今日は): whole for the strip, and as its piece for Space.
        val starts = (if (n > 0) lattice.prefixes(0) else emptyList()).filter { it.first < n }
        val firstLength = path.segments.firstOrNull()?.reading?.length ?: 0
        val variants = ArrayList<Scored>()
        for ((length, ws) in starts.sortedWith(compareBy({ it.first != firstLength }, { -it.first }))) {
            val take = if (length == firstLength) PER_FIRST else 1
            for ((w, cost) in costed(ws).sortedBy { it.second }.distinctBy { it.first.surface }.take(take)) {
                if (variants.size >= VARIANTS) break
                val seg = Segment(w.surface, w.reading, w.rid, w.lid)
                val rest = path(lattice, length, w.rid)
                variants += Scored(
                    Candidate(w.surface + rest.text, n, if (rest.segments.isEmpty()) w.rid else rest.rid, listOf(seg) + rest.segments),
                    dict.connection(left, w.lid) + cost + rest.cost,
                    piece = Candidate(w.surface, length, w.rid, listOf(seg)),
                )
            }
        }
        val wordTexts = dictWords.mapTo(HashSet()) { it.first.surface }
        for (v in variants) if (v.cost <= path.cost + NEAR && v.candidate.text !in wordTexts) scored += v

        // Words the reading may become, a little dearer for what is not typed yet.
        val fromDict = if (kana) dict.predict(reading, PREDICTIONS) else emptyList()
        for (w in fromDict) {
            scored += Scored(whole(w.surface, w), alone(w, w.cost) + PREDICTION + PER_KANA * (w.reading.length - n), prediction = true)
        }
        // The owner's own words too, each as cheap as it is for its whole reading.
        if (kana) {
            for (e in userWords?.startingWith(reading, PREDICTIONS).orEmpty()) {
                val w = lookup(e.reading).first { it.surface == e.surface }
                scored += Scored(whole(w.surface, w), alone(w, w.cost) + PREDICTION + PER_KANA * (w.reading.length - n), prediction = true)
            }
        }
        // A reading that looks foreign (ー, small vowels, a kana no word has) keeps its katakana
        // right after the best conversion.
        if (kana && looksForeign(reading, path)) scored += Scored(katakana, path.cost)

        val ranked = scored.sortedBy { it.cost }
        val predictions = (chosenOn + ranked.filter { it.prediction }.map { it.candidate }).distinctBy { it.text }.take(PREDICTIONS)
        val words = (chosen + dictWords.sortedBy { (w, cost) -> alone(w, cost) }.map { (w, _) -> whole(w.surface, w) })
            .distinctBy { it.text }

        // The pieces, the likeliest way on first, then the other words for the reading's start.
        val pieces = ArrayList<Candidate>()
        variants.sortedBy { it.cost }.mapNotNullTo(pieces) { it.piece }
        for ((length, ws) in starts.sortedByDescending { it.first }) {
            ws.distinctBy { it.surface }.take(PER_START).mapTo(pieces) {
                Candidate(it.surface, length, it.rid, listOf(Segment(it.surface, it.reading, it.rid, it.lid)))
            }
        }
        val allPieces = pieces.distinctBy { it.text to it.length }.take(PIECES)

        val kanaForms = listOfNotNull(hiragana, katakana, wide)
        val main = (listOf(best) + chosenAfter + chosen + chosenOn + ranked.map { it.candidate }).distinctBy { it.text }
        val offered = (main.take(SHOWN) + kanaForms + allPieces + main.drop(SHOWN)).distinctBy { it.text }

        fun cycle(): List<Candidate> {
            val out = ArrayList<Candidate>()
            val shown = HashSet<String>()
            fun put(c: Candidate, text: String) {
                if (shown.add(text)) out += c
            }
            put(best, best.text)
            for (c in chosen) put(c, c.text)
            // The kana after the first few, however many words are read so ("か": 化, 家, 科 ...).
            val conversions = ranked.filter { !it.prediction }
            for (s in conversions.take(BEFORE_KANA)) put(s.piece ?: s.candidate, s.candidate.text)
            for (c in kanaForms) put(c, c.text)
            for (s in conversions.drop(BEFORE_KANA)) put(s.piece ?: s.candidate, s.candidate.text)
            for (p in allPieces) put(p, p.text + path(lattice, p.length, p.rid).text)
            return out
        }

        return Conversion(reading, left, best, hiragana, katakana, wide, words, allPieces, predictions, offered, ::cycle).also {
            cached = it
            cachedPrev = prev
            cachedVersion = version
            cachedWords = ownVersion
        }
    }

    /**
     * The reading in hiragana or katakana as a candidate: with the parts of speech of the word
     * written so, where the dictionary has one, else a noun's.
     */
    private fun kanaForm(reading: String, text: String, n: Int): Candidate {
        if (!reading.all { Dictionary.kanaCode(it) != 0 }) return Candidate(text, n)
        val w = dict.lookup(reading).firstOrNull { it.surface == text }
        val noun = dict.literal(Literal.NOUN)
        return Candidate(text, n, w?.rid ?: noun, listOf(Segment(text, reading, w?.rid ?: noun, w?.lid ?: noun)))
    }

    /** The reading committed as typed, as a segment (for what follows it): punctuation as itself. */
    fun asTyped(reading: String): Segment? =
        punctuation(reading) ?: kanaForm(reading, reading, reading.length).segments.singleOrNull()

    /**
     * Punctuation that ends a clause or a sentence (、 。 ？ ！), as a segment with its part of
     * speech, which the next words and the next reading follow; null for anything else.
     */
    fun punctuation(text: String): Segment? {
        if (text.isEmpty() || text.any { it !in PUNCTUATION }) return null
        val id = dict.literal(literalKind(text.last()))
        return Segment(text, text, id, id, word = false)
    }

    /**
     * What may come next after `after`, with nothing typed yet: the words the owner chose after it
     * before, the latest first, then the words its part of speech goes on with best, by the
     * connection cost plus the word's own: the short ones (particles, the copula, auxiliaries:
     * "私": は, の, が; "ありがとう": ございます; "行き": たい, ます) and common content words
     * ("を": する, 思う, 見る; "が": ある, できる, ない). After 、 or 。, the words a clause or a
     * sentence starts with ("。": この, その, また, そして).
     */
    fun next(after: Segment, n: Int = NEXT): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        history?.after(after.surface, n)?.forEach { e ->
            out.putIfAbsent(e.surface, Candidate(e.surface, 0, e.rid, listOf(Segment(e.surface, e.reading, e.rid, e.lid))))
        }
        val pool = when {
            after.rid !in 0 until dict.parts -> emptyList()
            after.word -> following(after.rid)
            after.surface.all { it in PUNCTUATION } -> starting(after.rid)
            else -> emptyList()
        }
        for (w in pool) {
            if (out.size >= n) break
            out.putIfAbsent(w.surface, Candidate(w.surface, 0, w.rid, listOf(Segment(w.surface, w.reading, w.rid, w.lid))))
        }
        return out.values.take(n)
    }

    /** Each word of a list ("reading:surface", or a reading written in kana), with each part of speech it has. */
    private fun wordsOf(list: List<String>): List<Word> = list.flatMap { e ->
        val reading = e.substringBefore(':')
        val surface = e.substringAfter(':')
        dict.lookup(reading).filter { it.surface == surface }
    }

    private val shortWords: List<Word> by lazy { wordsOf(FOLLOWING) }
    private val contentWords: List<Word> by lazy { wordsOf(CONTENT) }
    private val starterWords: List<Word> by lazy { wordsOf(STARTERS) }

    /**
     * The words that may follow each part of speech, best first: worked out once for each, the
     * first time it is asked for, so the strip costs no more on each key.
     */
    private val followingCache = HashMap<Int, List<Word>>()
    private val startingCache = HashMap<Int, List<Word>>()

    private fun following(rid: Int): List<Word> = followingCache.getOrPut(rid) {
        ranked(rid, listOf(shortWords to NEXT_LIMIT, contentWords to CONTENT_LIMIT))
    }

    private fun starting(rid: Int): List<Word> = startingCache.getOrPut(rid) {
        ranked(rid, listOf(starterWords to CONTENT_LIMIT, contentWords to CONTENT_LIMIT))
    }

    /** The words of `pools` after `rid`, each under its pool's limit, cheapest first, each text once. */
    private fun ranked(rid: Int, pools: List<Pair<List<Word>, Int>>): List<Word> {
        val scored = ArrayList<Pair<Word, Int>>()
        for ((words, limit) in pools) {
            for (w in words) {
                val cost = dict.connection(rid, w.lid) + w.cost
                if (cost <= limit) scored += w to cost
            }
        }
        return scored.sortedBy { it.second }.map { it.first }.distinctBy { it.surface }.take(NEXT)
    }

    /**
     * The history's parts of speech are another dictionary's (another build, or none said), or
     * not any of this one's (a damaged file): each such word takes those of its likeliest form in
     * this one, else a noun's.
     */
    private fun matchDictionary(h: UserHistory) {
        val other = h.stamp != stamp
        val noun = dict.literal(Literal.NOUN)
        var changed = false
        for (e in h.entries()) {
            if (!other && e.lid in 0 until dict.parts && e.rid in 0 until dict.parts) continue
            val w = dict.lookup(e.reading).filter { it.surface == e.surface }.minByOrNull { it.cost }
            e.lid = w?.lid ?: noun
            e.rid = w?.rid ?: noun
            changed = true
        }
        h.stamp = stamp
        if (changed || other) h.changed()
    }

    companion object {
        /** What a kana that no word reads costs, standing for itself: more than any word. */
        const val UNKNOWN_KANA = 12000

        /** How much cheaper than the other words read so a chosen word is: more, the more it was chosen. */
        private const val MARGIN = 1500
        private const val MARGIN_STEP = 250
        private fun margin(count: Int) = MARGIN + MARGIN_STEP * (minOf(count, 4) - 1)

        /** How much cheaper than the cheapest word the dictionary reads so an owner's own word is. */
        private const val USER_MARGIN = 3000

        /** Of two words chosen for one reading, the later is this much cheaper again. */
        private const val RECENT = 300

        /** Words of each other start for the pieces, and of the best conversion's first word for the variants. */
        private const val PER_START = 3
        private const val PER_FIRST = 3
        private const val PIECES = 12

        /** Starts converted another way, with the rest after them: at most this many. */
        private const val VARIANTS = 5

        /**
         * Such a conversion is offered when it costs at most this much more than the best:
         * 今日は for こんにちは (896 more), not か見 for かみ (2176).
         */
        private const val NEAR = 2000

        /** Conversions Space goes through before the kana. */
        private const val BEFORE_KANA = 8

        private const val PREDICTIONS = 12

        /** A prediction costs this much more than the word typed whole, and more per kana not typed. */
        private const val PREDICTION = 1000
        private const val PER_KANA = 150

        /** Chosen words offered for a reading from the history, of each kind. */
        private const val LEARNED = 4

        /** The strip's candidates before the kana and the pieces: its three, and three rows of the panel. */
        private const val SHOWN = 12

        /**
         * Next words offered, and how dear the dearest may be after the word before: a short word,
         * and a content word (after a particle they cost 1,600 to 4,500 with Mozc's dictionary;
         * after a noun, more).
         */
        const val NEXT = 15
        private const val NEXT_LIMIT = 2600
        private const val CONTENT_LIMIT = 4500

        /** What ends a clause or a sentence: after it, the words one starts with. */
        private const val PUNCTUATION = "、。，．！？!?"

        /**
         * The short words written in kana that may follow a word: particles, the copula and
         * auxiliaries, and the commonest verbs and nouns that follow others.
         */
        private val FOLLOWING = (
            "は が を に で と の も へ や から まで より って けど ので のに か ね よ な わ だけ しか など なら ても でも " +
                "です ます だ た て ない たい ました ません でした でしょう だった じゃ ん れる られる そう よう " +
                "する した して います いる ある あります ございます ございました ください なる さん たち こと もの"
            ).split(' ')

        /**
         * Common content words that follow a particle ("を": する, 見る; "が": ある, いい): nouns,
         * adjectives, verbs and adverbs, "reading:surface" (a word written in kana: its reading).
         */
        private val CONTENT = (
            "なに:何 わたし:私 きょう:今日 あした:明日 きのう:昨日 いま:今 じかん:時間 しごと:仕事 ひと:人 " +
                "ともだち:友達 いえ:家 がっこう:学校 かいしゃ:会社 でんわ:電話 れんらく:連絡 しゃしん:写真 えいが:映画 " +
                "ごはん:ご飯 おかね:お金 にほん:日本 にほんご:日本語 えいご:英語 ほんとう:本当 だいじょうぶ:大丈夫 " +
                "いっしょ:一緒 ぜんぶ:全部 みんな はなし:話 もんだい:問題 てんき:天気 すき:好き だいすき:大好き " +
                "たのしみ:楽しみ げんき:元気 " +
                "いい ない たのしい:楽しい うれしい:嬉しい おいしい:美味しい おおきい:大きい ちいさい:小さい " +
                "あたらしい:新しい はやい:早い いそがしい:忙しい むずかしい:難しい かわいい:可愛い さむい:寒い " +
                "あつい:暑い おおい:多い すくない:少ない たかい:高い やすい:安い " +
                "する ある いる なる いく:行く くる:来る みる:見る いう:言う おもう:思う たべる:食べる のむ:飲む " +
                "かう:買う つかう:使う わかる:分かる できる もつ:持つ まつ:待つ きく:聞く よむ:読む かく:書く " +
                "つくる:作る あう:会う かえる:帰る はいる:入る でる:出る おくる:送る かんがえる:考える しる:知る " +
                "よく すごく とても ちょっと まだ もう すこし:少し たくさん また ほんとうに:本当に ぜんぜん:全然 " +
                "いちばん:一番 すぐ ずっと はやく:早く もっと けっこう:結構"
            ).split(' ')

        /** What a clause or a sentence often starts with, after 、 or 。, "reading:surface" as above. */
        private val STARTERS = (
            "わたし:私 ぼく:僕 おれ:俺 あなた きょう:今日 あした:明日 きのう:昨日 いま:今 こんど:今度 でも それ " +
                "そう この その あの これ じゃあ だから なので それで そして しかし また もし まず じつは:実は " +
                "たぶん:多分 やっぱり ちなみに ところで はい いいえ うん ありがとう すみません ごめん おはよう " +
                "こんにちは よろしく おつかれさま:お疲れ様 ちょっと ほんとうに:本当に みんな なに:何 どう なんで " +
                "いつ どこ"
            ).split(' ')

        fun literalKind(c: Char): Literal = when {
            c.isDigit() -> Literal.NUMBER
            c in 'a'..'z' || c in 'A'..'Z' -> Literal.ALPHABET
            c in "、,，" -> Literal.COMMA
            c in "。.．！？!?" -> Literal.PERIOD
            c.isLetter() -> Literal.NOUN
            else -> Literal.SYMBOL
        }

        /** Latin letters and digits full-width ("Tokyo": Ｔｏｋｙｏ). */
        fun wide(s: String): String = buildString(s.length) {
            for (c in s) append(if (c in '!'..'~' && (c.isLetterOrDigit())) c + 0xFEE0 else c)
        }

        /**
         * A reading that looks like a word from another language, written in katakana: a long
         * vowel mark, a small vowel (ふぁ, てぃ), ゔ, or a kana no word reads in its conversion.
         */
        fun looksForeign(reading: String, path: Path): Boolean =
            reading.any { it == 'ー' || it in "ぁぃぅぇぉゔ" } ||
                path.segments.any { !it.word && it.surface.all { c -> Dictionary.kanaCode(c) != 0 } }
    }
}
