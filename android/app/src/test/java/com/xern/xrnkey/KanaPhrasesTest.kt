package com.xern.xrnkey

import com.xern.xrnkey.ime.KanaStrip
import com.xern.xrnkey.ime.KanaTypist
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.UserHistory
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Space converts a reading into phrases (bunsetsu), as Mozc and Gboard do: each changed alone,
 * the focused one marked in the field, moved between, stretched and shrunk. With the made-up test
 * dictionary, and Mozc's where it is built.
 */
class KanaPhrasesTest {
    private val editor = FakeEditor()
    private val converter = Converter(TestModels.japanese())
    private val typist = KanaTypist(editor, converter).also { it.reset() }
    private val strip = KanaStrip()

    private fun type(keys: String) = keys.forEach { typist.text(it.toString()) }

    private val texts get() = typist.phrases.map { it.shown.text }
    private val readings get() = typist.phrases.map { typist.reading.substring(it.from, it.to) }

    /**
     * A particle, the copula or an ending goes with the word before it; a noun that takes する
     * keeps it; a prefix takes the noun after it. The field marks the focused phrase, the first.
     */
    @Test
    fun spaceSplitsTheReadingIntoPhrases() {
        type("watashihagakuseidesu ")
        assertEquals(listOf("私は", "学生です"), texts)
        assertEquals(listOf("わたしは", "がくせいです"), readings)
        assertEquals("私は学生です", editor.composing)
        assertEquals("私は", editor.focused)
        typist.enter()
        type("nihongowobenkyoushimasu ")
        assertEquals(listOf("日本語を", "勉強します"), texts)
        typist.enter()
        type("ochadesu ")
        assertEquals(listOf("お茶です"), texts)
        typist.enter()
        type("kami ")
        assertEquals(listOf("紙"), texts)
        assertEquals("紙", editor.focused)
    }

    /**
     * Space goes through the focused phrase's candidates alone, the others staying; ◀ ▶ (and the
     * space bar's slide) move the focus, no further than the ends; the strip shows the focused
     * phrase's, with ⇤ and ⇥ at its sides.
     */
    @Test
    fun spaceChangesTheFocusedPhraseAlone() {
        type("kamiwoyomu ")
        assertEquals(listOf("紙を", "読む"), texts)
        val s = strip.make(typist) as Strip.Candidates
        assertEquals("紙を", s.centre)
        assertTrue(s.highlight && s.resize)
        assertEquals("神を", s.right)
        type(" ")
        assertEquals(listOf("神を", "読む"), texts)
        assertEquals("神を読む", editor.composing)
        typist.moveFocus(1)
        assertEquals("読む", editor.focused)
        assertEquals("読む", (strip.make(typist) as Strip.Candidates).centre)
        type(" ")
        assertNotEquals("読む", texts[1])
        assertEquals("神を", texts[0])
        type(" ".repeat(typist.cycle.size - 1))
        assertEquals(listOf("神を", "読む"), texts)
        typist.moveFocus(5)
        assertEquals(1, typist.focus)
        typist.moveFocus(-5)
        assertEquals(0, typist.focus)
        assertEquals("神を", editor.focused)
    }

    /**
     * A tap takes the focused phrase's candidate and moves the focus on; on the last phrase it
     * commits everything. Each phrase's choice is learned, as the words and the pairs.
     */
    @Test
    fun aTapChoosesAPhraseAndTheLastCommits() {
        val h = UserHistory()
        converter.history = h
        type("kamiwoyomu ")
        strip.make(typist)
        typist.pick(strip.pick(2)!!)                  // 神を
        assertEquals(1, typist.focus)
        assertEquals("神を読む", editor.composing)
        assertEquals("", editor.committed.toString())
        strip.make(typist)
        typist.pick(strip.pick(1)!!)                  // 読む, the last: all goes in
        assertEquals("神を読む", editor.committed.toString())
        assertEquals("", editor.composing)
        assertFalse(typist.converting)
        assertEquals(listOf("神"), h.of("かみ").map { it.surface })
        assertEquals(listOf("を"), h.after("神", 5).map { it.surface })
        assertEquals(listOf("読む"), h.after("を", 5).map { it.surface })
        assertEquals("読む", typist.context?.surface)
        // Next time, かみ is 神, and the sentence converts so.
        type("kamiwoyomu ")
        assertEquals(listOf("神を", "読む"), texts)
    }

    /** Enter commits every phrase as shown; Backspace brings the reading back, unconverted. */
    @Test
    fun enterCommitsAllAndBackspaceGoesBack() {
        type("kamiwoyomu  ")
        typist.backspace()
        assertFalse(typist.converting)
        assertEquals("かみをよむ", editor.composing)
        assertEquals(null, editor.focus)
        type(" ")
        typist.moveFocus(1)
        typist.enter()
        assertEquals("紙を読む", editor.committed.toString())
        assertFalse(editor.log.contains("enter"))
    }

    /**
     * ⇤ shrinks the focused phrase by a kana, which starts the next phrase; ⇥ stretches it, taking
     * a kana from the next. Both are converted again; the phrases before stay. The last phrase
     * can't stretch, a phrase of one kana can't shrink; the last one shrunk leaves a new phrase.
     */
    @Test
    fun phrasesStretchAndShrink() {
        type("watashihagakuseidesu ")
        assertTrue(typist.resize(-1))
        assertEquals(listOf("わたし", "はがくせいです"), readings)
        assertEquals(listOf("私", "は学生です"), texts)
        assertEquals("私", editor.focused)
        assertTrue(typist.resize(1))
        assertEquals(listOf("わたしは", "がくせいです"), readings)
        assertEquals(listOf("私は", "学生です"), texts)
        typist.moveFocus(1)
        assertFalse(typist.resize(1))
        assertTrue(typist.resize(-2))
        assertEquals(listOf("わたしは", "がくせい", "です"), readings)
        assertEquals("学生", editor.focused)
        assertEquals("私は学生です", editor.composing)
        typist.moveFocus(-1)
        repeat(3) { assertTrue(typist.resize(-1)) }
        assertEquals("わ", readings.first())
        assertFalse(typist.resize(-1))
        typist.enter()
        assertFalse(typist.converting)
        assertTrue(editor.committed.toString(), editor.committed.toString().endsWith("学生です"))
    }

    /**
     * A dictionary built before the kinds (0.9.0's: nothing after the literals) still opens and
     * converts; a word written as its hiragana reading, and punctuation, go with the word before.
     */
    @Test
    fun aDictionaryWithoutKindsSplitsRoughly() {
        val d = TestModels.japanese()
        assertTrue(d.hasKinds)
        assertEquals(com.xern.xrnkey.ja.Kind.ATTACH, d.kind(converter.best("わたしは").last().lid))
        val bytes = TestModels.testJapanese.readBytes()
        val old = bytes.copyOf(bytes.size - d.parts)
        java.nio.ByteBuffer.wrap(old).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(8, old.size)
        val c = Converter(com.xern.xrnkey.ja.Dictionary.of(old))
        assertFalse(com.xern.xrnkey.ja.Dictionary.of(old).hasKinds)
        fun split(reading: String) = c.phrases(c.best(reading)).map { p -> p.joinToString("") { it.surface } }
        assertEquals(listOf("私は", "学生です"), split("わたしはがくせいです"))
        assertEquals(listOf("紙、"), split("かみ、"))
        assertEquals("私は学生です", c.bestText("わたしはがくせいです"))
    }

    /** With Mozc's dictionary: everyday sentences split as Mozc splits them. */
    @Test
    fun theRealDictionarySplitsAsMozcDoes() {
        assumeTrue(TestModels.realJapaneseAvailable)
        val c = Converter(TestModels.realJapanese())
        fun split(reading: String) = c.phrases(c.best(reading)).map { p -> p.joinToString("") { it.surface } }
        assertEquals(listOf("私は", "学生です"), split("わたしはがくせいです"))
        assertEquals(listOf("今日は", "いい天気ですね"), split("きょうはいいてんきですね"))
        assertEquals(listOf("電車が", "遅れています"), split("でんしゃがおくれています"))
        assertEquals(listOf("明日", "学校に", "行きます"), split("あしたがっこうにいきます"))
        assertEquals(listOf("仕事が", "終わったら", "電話します"), split("しごとがおわったらでんわします"))
        assertEquals(listOf("3時に"), split("3じに"))
    }
}
