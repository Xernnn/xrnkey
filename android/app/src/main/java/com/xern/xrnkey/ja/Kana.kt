package com.xern.xrnkey.ja

/**
 * A kana's other forms, as the 12-key pad's ゛゜小 key steps through them (Gboard's order): voiced
 * (か が), voiced and half-voiced (は ば ぱ), small (あ ぁ, や ゃ), small then voiced (つ っ づ,
 * う ぅ ゔ). Each tap gives the next form, after the last the first again.
 */
object Kana {
    private val FORMS = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "やゃ", "ゆゅ", "よょ",
        "わゎ",
    )

    private val NEXT: Map<Char, Char> = buildMap {
        for (f in FORMS) f.forEachIndexed { i, c -> put(c, f[(i + 1) % f.length]) }
    }

    /** The form after [c] (か: が, が: か, つ: っ), or null for a kana with no other (ん). */
    fun nextForm(c: Char): Char? = NEXT[c]
}
