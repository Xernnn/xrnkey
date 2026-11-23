package com.xern.xrnkey.ja

/**
 * A reading typed in romaji, as Gboard's Japanese QWERTY takes it (wāpuro rōmaji): the letters
 * become hiragana as soon as they spell one ("ka" か, "kya" きゃ, "shi" and "si" し, "tsu" and
 * "tu" つ, "xa" and "la" ぁ, "xtsu" っ), a doubled consonant is っ ("kitte" きって), and "-" is ー.
 * The letters that spell nothing yet wait at the end, shown as they are ("かk").
 *
 * ん: "nn" and "n'" are ん, so is an n before any consonant but n and y ("shinbun" しんぶん),
 * and an n left at the end becomes ん when the reading is converted or committed ([reading]):
 * "tanin" is たにん, "tan'in" たんいん. The second n of "nn" also starts the next kana when a
 * vowel or y follows it, as the word is spelt: "minna" みんな, "konnichiha" こんにちは,
 * "konnyaku" こんにゃく. Before anything else, or at the end, it goes ("nihonn" にほん), and a
 * third n starts afresh ("konnnichiha" こんにちは too).
 *
 * A capital letter (Shift) goes in as it is, and so does every letter after it until the reading
 * ends: names in Latin letters ("Tokyo"). Anything else that isn't a letter (digits, punctuation)
 * goes in as it is.
 */
class Romaji {
    private val text = StringBuilder()

    /** The letters typed since the last kana, which spell nothing yet. */
    var pending = ""
        private set

    /** A capital was typed: letters go in as they are until the reading ends. */
    var latin = false
        private set

    /** [pending] is the second n of an "nn" whose ん is already in the kana. */
    private var soft = false

    /** What the field shows: the kana so far, then the letters still waiting. */
    val shown: String get() = text.toString() + pending

    /** The reading as a conversion or a commit takes it: a last lone n is ん (or was, after "nn"). */
    val reading: String get() = text.toString() + if (pending == "n") (if (soft) "" else "ん") else pending

    fun isEmpty(): Boolean = text.isEmpty() && pending.isEmpty()

    fun clear() {
        text.setLength(0)
        pending = ""
        latin = false
        soft = false
    }

    /** A key: a letter of the romaji, or text that goes in as it is. */
    fun type(c: Char) {
        when {
            c in 'A'..'Z' -> {
                flush()
                latin = true
                text.append(c)
            }
            latin && c in 'a'..'z' -> text.append(c)
            soft && pending == "n" && (c in 'a'..'z' || c == '\'') -> {
                // The second n of "nn": a vowel or y takes it ("minna"); anything else drops it.
                soft = false
                pending = when {
                    c in VOWELS || c == 'y' -> "n$c"
                    c == '\'' -> ""
                    else -> c.toString()
                }
                resolve()
            }
            c in 'a'..'z' || c == '-' || c == '\'' && pending == "n" -> {
                pending += c
                resolve()
            }
            else -> {
                flush()
                text.append(c)
            }
        }
    }

    /** Backspace: the last letter waiting, else the last kana. False when there was nothing. */
    fun backspace(): Boolean {
        when {
            // "nn" shown as "んn": back to the one n typed before it.
            soft && pending == "n" -> {
                soft = false
                text.setLength(text.length - 1)
            }
            pending.isNotEmpty() -> pending = pending.dropLast(1)
            text.isNotEmpty() -> {
                val n = if (text.length >= 2 && Character.isSurrogatePair(text[text.length - 2], text[text.length - 1])) 2 else 1
                text.setLength(text.length - n)
            }
            else -> return false
        }
        if (isEmpty()) latin = false
        return true
    }

    /**
     * The first `n` characters of [reading] went (a candidate took them): the rest stays, its
     * letters still waiting.
     */
    fun drop(n: Int) {
        val inText = minOf(n, text.length)
        text.delete(0, inText)
        if (n > inText) pending = pending.drop(n - inText)
        // The second n of an "nn" whose ん went with the candidate goes too.
        if (soft && text.isEmpty()) {
            pending = ""
            soft = false
        }
        if (isEmpty()) latin = false
    }

    /** The letters waiting end the kana: a lone n as ん, others as letters. */
    private fun flush() {
        if (pending.isEmpty()) return
        if (!soft) text.append(if (pending == "n") "ん" else pending)
        pending = ""
        soft = false
    }

    private fun resolve() {
        while (pending.isNotEmpty()) {
            val p = pending
            val kana = TABLE[p]
            if (kana != null && p !in PREFIXES) {
                text.append(kana)
                pending = ""
                return
            }
            if (p in PREFIXES) return  // more letters may make it a kana
            // The letters can't become one: what their start does, then the rest again.
            val known = (p.length - 1 downTo 1).firstOrNull { TABLE.containsKey(p.substring(0, it)) }
            val first = p[0]
            val second = p.getOrNull(1)
            when {
                p == "nn" -> {
                    text.append('ん')
                    pending = "n"
                    soft = true
                    return
                }
                known != null -> {
                    text.append(TABLE[p.substring(0, known)])
                    pending = p.substring(known)
                }
                first == 'n' && second != null && second !in VOWELS && second != 'y' -> {
                    text.append('ん')
                    pending = p.substring(1)
                }
                second != null && (first == second || first == 't' && second == 'c') && first in CONSONANTS -> {
                    text.append('っ')
                    pending = p.substring(1)
                }
                else -> {
                    text.append(first)
                    pending = p.substring(1)
                }
            }
        }
    }

    companion object {
        private const val VOWELS = "aiueo"
        private const val CONSONANTS = "bcdfghjklmpqrstvwxyz"

        /** Romaji to hiragana: the wāpuro table, as Mozc and Gboard have it. */
        val TABLE: Map<String, String> = buildMap {
            fun row(prefix: String, kana: String) {
                // kana: the a, i, u, e, o forms, space-separated ("-" for none).
                kana.split(' ').forEachIndexed { i, k -> if (k != "-") put(prefix + VOWELS[i], k) }
            }
            row("", "あ い う え お")
            row("k", "か き く け こ"); row("ky", "きゃ きぃ きゅ きぇ きょ")
            row("g", "が ぎ ぐ げ ご"); row("gy", "ぎゃ ぎぃ ぎゅ ぎぇ ぎょ")
            row("s", "さ し す せ そ"); row("sy", "しゃ しぃ しゅ しぇ しょ"); row("sh", "しゃ し しゅ しぇ しょ")
            row("z", "ざ じ ず ぜ ぞ"); row("zy", "じゃ じぃ じゅ じぇ じょ")
            row("j", "じゃ じ じゅ じぇ じょ"); row("jy", "じゃ じぃ じゅ じぇ じょ")
            row("t", "た ち つ て と"); row("ty", "ちゃ ちぃ ちゅ ちぇ ちょ"); row("ch", "ちゃ ち ちゅ ちぇ ちょ")
            row("cy", "ちゃ ちぃ ちゅ ちぇ ちょ"); row("ts", "つぁ つぃ つ つぇ つぉ"); row("th", "てゃ てぃ てゅ てぇ てょ")
            row("tw", "とぁ とぃ とぅ とぇ とぉ")
            row("d", "だ ぢ づ で ど"); row("dy", "ぢゃ ぢぃ ぢゅ ぢぇ ぢょ"); row("dh", "でゃ でぃ でゅ でぇ でょ")
            row("dw", "どぁ どぃ どぅ どぇ どぉ")
            row("n", "な に ぬ ね の"); row("ny", "にゃ にぃ にゅ にぇ にょ")
            row("h", "は ひ ふ へ ほ"); row("hy", "ひゃ ひぃ ひゅ ひぇ ひょ")
            row("f", "ふぁ ふぃ ふ ふぇ ふぉ"); row("fy", "ふゃ ふぃ ふゅ ふぇ ふょ")
            row("b", "ば び ぶ べ ぼ"); row("by", "びゃ びぃ びゅ びぇ びょ")
            row("p", "ぱ ぴ ぷ ぺ ぽ"); row("py", "ぴゃ ぴぃ ぴゅ ぴぇ ぴょ")
            row("m", "ま み む め も"); row("my", "みゃ みぃ みゅ みぇ みょ")
            row("y", "や い ゆ いぇ よ")
            row("r", "ら り る れ ろ"); row("ry", "りゃ りぃ りゅ りぇ りょ")
            row("w", "わ うぃ う うぇ を"); row("wh", "うぁ うぃ う うぇ うぉ")
            row("v", "ゔぁ ゔぃ ゔ ゔぇ ゔぉ"); row("vy", "ゔゃ ゔぃ ゔゅ ゔぇ ゔょ")
            row("q", "くぁ くぃ く くぇ くぉ"); row("kw", "くぁ - - - -"); row("gw", "ぐぁ - - - -")
            row("c", "か し く せ こ")
            row("x", "ぁ ぃ ぅ ぇ ぉ"); row("l", "ぁ ぃ ぅ ぇ ぉ")
            row("xy", "ゃ ぃ ゅ ぇ ょ"); row("ly", "ゃ ぃ ゅ ぇ ょ")
            for (p in listOf("x", "l")) {
                put("${p}tu", "っ"); put("${p}tsu", "っ"); put("${p}wa", "ゎ"); put("${p}ka", "ゕ"); put("${p}ke", "ゖ")
            }
            put("xn", "ん")
            put("n'", "ん")
            put("wyi", "ゐ"); put("wye", "ゑ")
            put("-", "ー")
        }

        /** Every start of a romaji spelling that is shorter than it: more letters may follow. */
        private val PREFIXES: Set<String> = buildSet {
            for (k in TABLE.keys) for (i in 1 until k.length) add(k.substring(0, i))
        }
    }
}
