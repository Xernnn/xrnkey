package com.xern.xrnkey.engine

/*
 * The keyboard's view of the shared core. No Android types here, so the JVM tests run it
 * against the engine built for the laptop (android/host).
 */

private fun String.utf8() = toByteArray(Charsets.UTF_8)
private fun ByteArray.text() = String(this, Charsets.UTF_8)
private fun List<String>.utf8() = Array(size) { this[it].utf8() }

/** A language model (.xklm), memory-mapped. */
class Model private constructor(internal val handle: Long) : AutoCloseable {
    /** How many words it knows. */
    val vocabulary: Int get() = Native.lmVocabSize(handle)

    override fun close() = Native.lmFree(handle)

    companion object {
        fun open(path: String): Model? = Native.lmOpenPath(path.utf8()).takeIf { it != 0L }?.let(::Model)

        /** A model inside a larger file, as an uncompressed asset is inside the APK. */
        fun open(fd: Int, offset: Long, length: Long): Model? =
            Native.lmOpenFd(fd, offset, length).takeIf { it != 0L }?.let(::Model)
    }
}

/** The models that score words in context; either may be missing. Doesn't own them. */
class Models(vi: Model?, en: Model?) : AutoCloseable {
    internal val handle = Native.modelsNew(vi?.handle ?: 0L, en?.handle ?: 0L)

    /** XK_CORRECT_OFF, _FLEXIBLE or _STRONG. */
    var correction: Int = CORRECT_OFF
        set(value) {
            field = value
            Native.modelsSetCorrection(handle, value)
        }

    /** The best reading of a run of words typed without marks, decoded together. */
    fun restoreRun(context: List<String>, typed: List<String>, next: String? = null): List<String>? =
        Native.restoreRun(handle, context.utf8(), typed.utf8(), next?.utf8())?.map { it.text() }

    /** What one word typed without marks may stand for, best first. */
    fun restoreWord(context: List<String>, typed: String, max: Int = 8): List<Candidate> {
        val types = IntArray(max)
        val scores = FloatArray(max)
        val texts = Native.restoreWord(handle, context.utf8(), typed.utf8(), max, types, scores)
        return texts.mapIndexed { i, t -> Candidate(t.text(), types[i], scores[i]) }
    }

    /**
     * The words longer than `typed` (letters without marks) that start with it, with their log10
     * probabilities in context, best first: English words ("hel": "hello", "help") and
     * Vietnamese syllables, accented ("xin": "xinh").
     */
    fun complete(context: List<String>, typed: String, max: Int = 3): List<Pair<String, Float>> {
        val scores = FloatArray(max)
        return Native.complete(handle, context.utf8(), typed.utf8(), scores).mapIndexed { i, w -> w.text() to scores[i] }
    }

    /**
     * `typed` (letters without marks) is a word as it stands, so Space keeps it: English, or a
     * Vietnamese syllable not far rarer than its best reading ("ong", "ban"; not "khong").
     */
    fun plainWord(typed: String): Boolean = Native.plainWord(handle, typed.utf8(), PLAIN_RATIO)

    /** The confident one-edit fix of a word wrong in both languages ("teh" -> "the"), or null. */
    fun fixWrongWord(typed: String, strong: Boolean = false): String? =
        Native.fixWrongWord(handle, typed.utf8(), if (strong) 0.5f else 1.0f)?.text()

    /**
     * How likely `word` is next as Vietnamese and as English: the log10 of each part of the
     * mixture the next words are ranked with ([NEVER] for a part that is nothing).
     */
    fun languages(context: List<String>, word: String): Pair<Float, Float> {
        val out = FloatArray(2)
        Native.wordLanguages(handle, context.utf8(), word.utf8(), out)
        return out[0] to out[1]
    }

    /** The likeliest next words with their log10 probabilities, likeliest first. */
    fun predictScored(context: List<String>, max: Int = 3): List<Pair<String, Float>> {
        val scores = FloatArray(max)
        return Native.predictScored(handle, context.utf8(), scores).mapIndexed { i, w -> w.text() to scores[i] }
    }

    /** The words likeliest to come next after `context` (as for [restoreRun]), likeliest first. */
    fun predict(context: List<String>, max: Int = 3): List<String> =
        Native.predict(handle, context.utf8(), max).map { it.text() }

    /**
     * The touch keyboard's letter keys, for Space's typo fixes: a key's height in key widths, and
     * where the owner's taps on each letter a..z land on average ([sweetX], [sweetY]: 26 offsets
     * in key widths across and key heights down; null: the centres).
     */
    fun setKeyboard(keyHeight: Float, sweetX: FloatArray? = null, sweetY: FloatArray? = null) =
        Native.modelsSetKeyboard(handle, keyHeight, sweetX, sweetY)

    /** The owner's personal words, which scoring leans towards (null: none). Not owned. */
    var user: PersonalWords? = null
        set(value) {
            field = value
            Native.modelsSetUser(handle, value?.handle ?: 0L)
        }

    override fun close() = Native.modelsFree(handle)

    companion object {
        /** log10 of how much rarer than its best reading a plain form may be and still be a word. */
        const val PLAIN_RATIO = -1.6f

        /** log10 of what can't happen (lmfmt::kNever). */
        const val NEVER = -99f

        const val CORRECT_OFF = 0
        const val CORRECT_FLEXIBLE = 1
        const val CORRECT_STRONG = 2
    }
}

/** XKCandidate.type: restored, as typed, or corrected; the score is log10 P(text | context). */
data class Candidate(val text: String, val type: Int, val score: Float = 0f) {
    companion object {
        const val CORRECTED = 1
        const val RESTORED = 3
        const val AS_TYPED = 4
    }
}

/** What Space's typo fix made of a word: its fix (null: it stays), and whether it is a word as typed. */
data class TypoFix(val fix: String?, val word: Boolean)

/** What a key did: text to commit, the word in progress, whether the core took the key. */
data class KeyResult(val commit: String, val preedit: String, val consumed: Boolean, val deleteBefore: Int)

/** One field's Telex (or VNI) state in the core. */
class TypingState(val mode: Int = TELEX) : AutoCloseable {
    private val handle = Native.stateNew(mode)
    private val out = IntArray(2)

    fun useModels(models: Models?) = Native.stateSetModels(handle, models?.handle ?: 0L)

    fun setOption(option: Int, value: Boolean) = Native.stateSetOption(handle, option, if (value) 1 else 0)

    /** A key: a character (its code), or one of the keysyms below. */
    fun key(keysym: Int): KeyResult = result(Native.stateKey(handle, keysym, out))

    /** End the word in progress as a boundary would (cursor moved, field left). */
    fun flush(): KeyResult = result(Native.stateFlush(handle, out))

    /** The keys of the word in progress, as typed. */
    fun keys(): String = Native.stateKeys(handle).text()

    /** What ending the word in progress would commit (the core's auto-restore applied). */
    fun previewFinal(): String = Native.statePreviewFinal(handle).text()

    fun setContext(words: List<String>) = Native.stateSetContext(handle, words.utf8())

    /**
     * Space's typo fix for the word in progress (core/typos.cpp), at `level` (Models.CORRECT_*),
     * after the words in `context`: where each of its keys was touched ([touchX], [touchY]: key
     * widths across and key heights down from the key's centre; NaN where not known). The fix
     * (null: the word stays), and whether the word as typed is a word.
     */
    fun fixTypo(context: List<String>, touchX: FloatArray, touchY: FloatArray, level: Int): TypoFix {
        val known = IntArray(1)
        val fix = Native.stateFixTypo(handle, context.utf8(), touchX, touchY, level, known)?.text()
        return TypoFix(fix, known[0] != 0)
    }

    fun reset() = Native.stateReset(handle)

    private fun result(r: Array<ByteArray>) = KeyResult(r[0].text(), r[1].text(), out[0] != 0, out[1])

    override fun close() = Native.stateFree(handle)

    companion object {
        const val TELEX = 0
        const val VNI = 1
        /** No marks: letters as typed (the English keyboard). */
        const val PLAIN = 2
        const val BACKSPACE = 0xFF08
        const val RETURN = 0xFF0D
        const val OPT_AUTO_RESTORE = 1
        const val OPT_MODERN_STYLE = 2
        const val OPT_FREE_MARKING = 3
        const val OPT_W_SHORTCUT = 4
        const val OPT_LEARN = 5
    }
}
