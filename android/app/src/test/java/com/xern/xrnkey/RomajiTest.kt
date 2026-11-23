package com.xern.xrnkey

import com.xern.xrnkey.ja.Romaji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Romaji to hiragana, as Gboard's Japanese QWERTY spells it (the wāpuro table). */
class RomajiTest {
    private fun typed(keys: String): Romaji = Romaji().also { r -> keys.forEach { r.type(it) } }

    /** What a conversion or a commit takes: a lone n left at the end is ん. */
    private fun reading(keys: String) = typed(keys).reading

    @Test
    fun theTableSpellsEachKana() {
        val cases = mapOf(
            "aiueo" to "あいうえお", "kakikukeko" to "かきくけこ", "gagigugego" to "がぎぐげご",
            "sashisuseso" to "さしすせそ", "si" to "し", "zaji" to "ざじ", "zi" to "じ",
            "tachitsuteto" to "たちつてと", "ti" to "ち", "tu" to "つ", "dadidudedo" to "だぢづでど",
            "naninuneno" to "なにぬねの", "hahifuheho" to "はひふへほ", "hu" to "ふ",
            "mamimumemo" to "まみむめも", "yayuyo" to "やゆよ", "rarirurero" to "らりるれろ",
            "wawo" to "わを", "babibubebo" to "ばびぶべぼ", "papipupepo" to "ぱぴぷぺぽ",
            "kyakyukyo" to "きゃきゅきょ", "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
            "cha" to "ちゃ", "chu" to "ちゅ", "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
            "nya" to "にゃ", "ryo" to "りょ", "fa" to "ふぁ", "thi" to "てぃ", "dhi" to "でぃ",
            "va" to "ゔぁ", "vu" to "ゔ", "kwa" to "くぁ", "ye" to "いぇ",
        )
        for ((keys, kana) in cases) assertEquals(keys, kana, reading(keys))
    }

    @Test
    fun smallKanaComeWithXOrL() {
        val cases = mapOf(
            "xa" to "ぁ", "li" to "ぃ", "xu" to "ぅ", "le" to "ぇ", "xo" to "ぉ",
            "xya" to "ゃ", "lyu" to "ゅ", "xyo" to "ょ", "xtu" to "っ", "ltu" to "っ",
            "xtsu" to "っ", "ltsu" to "っ", "xwa" to "ゎ", "xka" to "ゕ", "xke" to "ゖ",
        )
        for ((keys, kana) in cases) assertEquals(keys, kana, reading(keys))
    }

    /** A doubled consonant is っ, before the kana it starts ("kitte", "matcha", "zasshi"). */
    @Test
    fun aDoubledConsonantIsASmallTsu() {
        assertEquals("きって", reading("kitte"))
        assertEquals("がっこう", reading("gakkou"))
        assertEquals("ざっし", reading("zasshi"))
        assertEquals("まっちゃ", reading("matcha"))
        assertEquals("いっぱい", reading("ippai"))
    }

    /**
     * ん: "nn" and "n'", an n before a consonant other than n and y, and a lone n at the end of
     * the reading. "tanin" is たにん (他人), "tan'in" たんいん: the apostrophe parts n from the
     * vowel. The second n of "nn" starts the next kana when a vowel or y follows, as the word is
     * spelt ("minna" みんな, "konnichiha" こんにちは); else it goes ("nihonn" にほん), and three n
     * work too. It used to be ん whatever followed, so "minna" was みんあ.
     */
    @Test
    fun nBecomesNByTheRules() {
        assertEquals("しんぶん", reading("shinbun"))
        assertEquals("たにん", reading("tanin"))
        assertEquals("たんいん", reading("tan'in"))
        assertEquals("こんにちは", reading("konnichiha"))
        assertEquals("こんにちは", reading("konnnichiha"))
        assertEquals("みんな", reading("minna"))
        assertEquals("こんにゃく", reading("konnyaku"))
        assertEquals("こんやく", reading("kon'yaku"))
        assertEquals("しんねん", reading("shinnen"))
        assertEquals("にほん", reading("nihonn"))
        assertEquals("ほんだ", reading("honnda"))
        assertEquals("たんい", reading("tann'i"))
        // While the second n waits, it shows; Backspace takes it back to the one n.
        assertEquals("みんn", typed("minn").shown)
        assertEquals("みn", typed("minn").also { it.backspace() }.shown)
        assertEquals("ほんや", reading("hon'ya"))
        assertEquals("にゃ", reading("nya"))
        assertEquals("せんせい", reading("sensei"))
        assertEquals("ん", reading("nn"))
        assertEquals("ん", reading("xn"))
        // While it waits, the n shows as it is typed.
        assertEquals("にほn", typed("nihon").shown)
        assertEquals("にほん", typed("nihon").reading)
    }

    @Test
    fun longVowelsAndOtherReadings() {
        assertEquals("きょう", reading("kyou"))
        assertEquals("らーめん", reading("ra-men"))
        assertEquals("こーひー", reading("ko-hi-"))
        assertEquals("ありがとう", reading("arigatou"))
        assertEquals("わたしはがくせいです", reading("watashihagakuseidesu"))
    }

    /** Letters that spell nothing yet wait at the end; letters that can't spell anything stay. */
    @Test
    fun lettersWaitAtTheEnd() {
        val r = typed("kak")
        assertEquals("かk", r.shown)
        assertEquals("k", r.pending)
        assertEquals("かky", typed("kaky").shown)
        assertEquals("かっきゃ", typed("kakkya").shown)
        // A letter no kana starts with that stays: q then k.
        assertEquals("qか", typed("qka").shown)
    }

    /** Backspace takes the last letter waiting, then the kana before it. */
    @Test
    fun backspaceTakesALetterOrAKana() {
        val r = typed("kyok")
        assertEquals("きょk", r.shown)
        assertTrue(r.backspace())
        assertEquals("きょ", r.shown)
        assertTrue(r.backspace())
        assertEquals("き", r.shown)
        assertTrue(r.backspace())
        assertEquals("", r.shown)
        assertFalse(r.backspace())
        assertTrue(r.isEmpty())
    }

    /**
     * A capital goes in as it is, and so do the letters after it, until the reading ends: a name
     * in Latin letters ("Tokyo"). Digits and punctuation go in as they are.
     */
    @Test
    fun capitalsAndWhatIsntALetterGoInAsTheyAre() {
        assertEquals("Tokyo", typed("Tokyo").reading)
        assertEquals("かいしゃのIBM", typed("kaishanoIBM").reading)
        assertEquals("3じ", typed("3ji").reading)
        assertEquals("かみ、", typed("kami、").reading)
        // A lone n before them is ん.
        assertEquals("ほん。", typed("hon。").reading)
        val r = typed("Tokyo")
        r.clear()
        "kami".forEach { r.type(it) }
        assertEquals("かみ", r.reading)
    }

    /** A candidate took the reading's start: the rest stays, its letters still waiting. */
    @Test
    fun dropLeavesTheRestOfTheReading() {
        val r = typed("watashihak")
        r.drop(3)
        assertEquals("はk", r.shown)
        val s = typed("nihon")
        s.drop(2)
        assertEquals("n", s.shown)
        assertEquals("ん", s.reading)
        s.drop(1)
        assertTrue(s.isEmpty())
    }
}
