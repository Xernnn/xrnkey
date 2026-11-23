package com.xern.xrnkey

import com.xern.xrnkey.ime.KanaStrip
import com.xern.xrnkey.ime.KanaTypist
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Japanese keyboard's keys, with the made-up test dictionary: the reading composes as
 * hiragana, Space converts and goes through the candidates, Enter commits what the field shows.
 */
class KanaTypistTest {
    private val editor = FakeEditor()
    private val typist = KanaTypist(editor, Converter(TestModels.japanese())).also { it.reset() }
    private val strip = KanaStrip()

    private fun type(keys: String) = keys.forEach { typist.text(it.toString()) }

    @Test
    fun lettersComposeAsHiragana() {
        type("kak")
        assertEquals("かk", editor.composing)
        type("i")
        assertEquals("かき", editor.composing)
        assertEquals("", editor.committed.toString())
        assertEquals(2, typist.cursor())
    }

    /**
     * Space converts: the field shows the best conversion and the strip highlights it; each Space
     * after it shows the next candidate, round to the first again. Enter commits what is shown,
     * with no new line.
     */
    @Test
    fun spaceGoesThroughTheCandidatesAndEnterCommits() {
        type("kami")
        type(" ")
        assertTrue(typist.converting)
        assertEquals("紙", editor.composing)
        val first = strip.make(typist) as Strip.Candidates
        assertEquals(Strip.Candidates(null, "紙", "神", more = true, extra = listOf("髪", "上", "かみ", "カミ", "蚊"), highlight = true, most = KanaStrip.MOST, removable = false), first)
        type(" ")
        assertEquals("神", editor.composing)
        val second = strip.make(typist) as Strip.Candidates
        assertEquals(listOf("紙", "神", "髪"), listOf(second.left, second.centre, second.right))
        assertEquals(listOf("上", "かみ", "カミ", "蚊"), second.extra)
        repeat(4) { type(" ") }
        assertEquals("カミ", editor.composing)
        repeat(2) { type(" ") }
        assertEquals("紙", editor.composing)
        type(" ")
        typist.enter()
        assertEquals("神", editor.committed.toString())
        assertEquals("", editor.composing)
        assertFalse(editor.log.contains("enter"))
        assertFalse(typist.converting)
    }

    /** Enter while composing commits the reading as it stands (a last n as ん); with nothing, the field's Enter. */
    @Test
    fun enterCommitsTheReadingOrReachesTheField() {
        type("nihon")
        assertEquals("にほn", editor.composing)
        typist.enter()
        assertEquals("にほん", editor.committed.toString())
        assertFalse(editor.log.contains("enter"))
        typist.enter()
        assertEquals("にほん\n", editor.committed.toString())
    }

    @Test
    fun spaceWithNothingComposingIsAFullWidthSpace() {
        type(" ")
        assertEquals("　", editor.committed.toString())
        type("kami ")
        typist.enter()
        type(" ")
        assertEquals("　紙　", editor.committed.toString())
    }

    /** A letter after Space commits the conversion and starts a new reading. */
    @Test
    fun aLetterAfterAConversionCommitsIt() {
        type("kami a")
        assertEquals("紙", editor.committed.toString())
        assertEquals("あ", editor.composing)
        assertFalse(typist.converting)
    }

    /**
     * The strip while composing, as Gboard's: what Space gives in the middle, the next likeliest
     * on the left and the right, the others in the panel, then the kana and the pieces.
     * Predictions take a slot when they are likelier than the words read so.
     */
    @Test
    fun theStripOffersTheLikeliestThenKana() {
        type("kami")
        val s = strip.make(typist) as Strip.Candidates
        assertEquals(listOf("神", "紙", "髪"), listOf(s.left, s.centre, s.right))
        assertEquals(listOf("上", "かみ", "カミ", "蚊"), s.extra)
        assertFalse(s.highlight)
        assertTrue(s.bold)
        typist.reset()
        type("niho")
        val p = strip.make(typist) as Strip.Candidates
        assertEquals(listOf("日本", "にほ", "日本語"), listOf(p.left, p.centre, p.right))
        assertTrue(p.extra.toString(), p.extra.containsAll(listOf("日本人", "ニホ")))
        typist.reset()
        assertEquals(Strip.Tools, strip.make(typist))
    }

    /**
     * A candidate for the reading's start commits it; the rest stays composing, as typed, and
     * converts as following it ("は学生です" after 私, not "葉学生です").
     */
    @Test
    fun aTapOnAPieceCommitsItAndTheRestStays() {
        type("watashihagakuseidesu")
        strip.make(typist)
        val piece = strip.pick("私")!!
        assertEquals(3, piece.length)
        typist.pick(piece)
        assertEquals("私", editor.committed.toString())
        assertEquals("はがくせいです", editor.composing)
        val s = strip.make(typist) as Strip.Candidates
        assertEquals("は学生です", s.centre)
        type(" ")
        assertEquals("は学生です", editor.composing)
        typist.enter()
        assertEquals("私は学生です", editor.committed.toString())
        // A slot's candidate for the whole reading commits it all.
        type("kami")
        strip.make(typist)
        typist.pick(strip.pick(1)!!)
        assertEquals("私は学生です紙", editor.committed.toString())
        assertEquals("", editor.composing)
    }

    /**
     * While Space shows a piece, the field shows it with the rest converted after it; a tap on the
     * piece takes the piece alone.
     */
    @Test
    fun aPieceAfterSpaceShowsTheRestConverted() {
        type("watashihagakuseidesu ")
        assertEquals("私は学生です", editor.composing)
        repeat(3) { type(" ") }
        assertEquals("渡し", typist.cycle[typist.selected].text)
        assertEquals("渡しは学生です", editor.composing)
        typist.pick(typist.cycle[typist.selected])
        assertEquals("渡し", editor.committed.toString())
        assertEquals("はがくせいです", editor.composing)
    }

    /** Backspace takes the last letter or kana; after Space, it goes back to the reading. */
    @Test
    fun backspaceTakesALetterAKanaOrTheConversion() {
        editor.commit("あ")
        typist.reset()
        type("kamik")
        typist.backspace()
        assertEquals("かみ", editor.composing)
        type(" ")
        assertEquals("紙", editor.composing)
        typist.backspace()
        assertEquals("かみ", editor.composing)
        assertFalse(typist.converting)
        typist.backspace()
        typist.backspace()
        assertEquals("", editor.composing)
        assertEquals("あ", editor.committed.toString())
        typist.backspace()
        assertEquals("", editor.committed.toString())
    }

    /**
     * 、 and 。 come from their keys, ? and ! are typed full-width: inside a reading they join
     * it, and convert with it; with nothing composing they go straight in. Digits stay ASCII.
     */
    @Test
    fun punctuationIsJapanese() {
        type("kami、")
        assertEquals("かみ、", editor.composing)
        type(" ")
        assertEquals("紙、", editor.composing)
        typist.enter()
        type("。?!")
        assertEquals("紙、。？！", editor.committed.toString())
        type("3")
        assertEquals("紙、。？！3", editor.committed.toString())
        type("kami3")
        assertEquals("かみ3", editor.composing)
        type("-")
        assertEquals("かみ3ー", editor.composing)
        typist.finish()
        type("-")
        assertEquals("紙、。？！3かみ3ー-", editor.committed.toString())
    }

    /** Shifted letters go in as Latin letters, and the letters after them too (a name). */
    @Test
    fun shiftedLettersStayLatin() {
        type("Tokyo")
        assertEquals("Tokyo", editor.composing)
        type(" ")
        assertEquals("Tokyo", editor.composing)
        type(" ")
        assertEquals("Ｔｏｋｙｏ", editor.composing)
        typist.enter()
        assertEquals("Ｔｏｋｙｏ", editor.committed.toString())
    }

    /** The cursor moving, or the field being left, commits what is shown (a last n as ん). */
    @Test
    fun finishCommitsWhatIsShown() {
        type("nihon")
        typist.finish()
        assertEquals("にほん", editor.committed.toString())
        type("kami ")
        typist.finish()
        assertEquals("にほん紙", editor.committed.toString())
        assertEquals("", editor.composing)
        assertEquals(4, typist.cursor())
    }

    /** A swipe from Backspace takes the reading in progress, else the word before the cursor. */
    @Test
    fun aSwipedDeleteTakesTheReadingOrTheWord() {
        editor.commit("今日は 雨")
        typist.reset()
        type("kami")
        typist.deleteWord()
        assertEquals("", editor.composing)
        assertEquals("今日は 雨", editor.committed.toString())
        typist.deleteWord()
        assertEquals("今日は ", editor.committed.toString())
    }

    /** Text from a panel ends the reading as it is, then goes in. */
    @Test
    fun anEmojiEndsTheReading() {
        type("kami")
        typist.insert("😊")
        assertEquals("かみ😊", editor.committed.toString())
    }
}
