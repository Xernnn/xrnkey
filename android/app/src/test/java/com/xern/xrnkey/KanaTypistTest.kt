package com.xern.xrnkey

import com.xern.xrnkey.ime.KanaStrip
import com.xern.xrnkey.ime.KanaTypist
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.UserHistory
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Japanese keyboard's keys, with the made-up test dictionary: the reading composes as
 * hiragana, Space converts and goes through the candidates, Enter commits what the field shows.
 */
class KanaTypistTest {
    private val editor = FakeEditor()
    private val converter = Converter(TestModels.japanese())
    private val typist = KanaTypist(editor, converter).also { it.reset() }
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
        // A piece shows with the rest of the reading converted after it, as the field would (蚊身).
        assertEquals(Strip.Candidates(null, "紙", "神", more = true, extra = listOf("髪", "上", "かみ", "カミ", "蚊身"), highlight = true, most = KanaStrip.MOST, removable = false, resize = true), first)
        type(" ")
        assertEquals("神", editor.composing)
        val second = strip.make(typist) as Strip.Candidates
        assertEquals(listOf("紙", "神", "髪"), listOf(second.left, second.centre, second.right))
        assertEquals(listOf("上", "かみ", "カミ", "蚊身"), second.extra)
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
     * A conversion chosen by Space, or a candidate by a tap, is learned: next time it comes first,
     * for Space and in the strip.
     */
    @Test
    fun whatIsChosenComesFirstNextTime() {
        converter.history = UserHistory()
        type("kami  ")
        assertEquals("神", editor.composing)
        typist.enter()
        type("kami")
        assertEquals("神", (strip.make(typist) as Strip.Candidates).centre)
        type(" ")
        assertEquals("神", editor.composing)
        typist.enter()
        // A tap on the right slot (髪).
        type("kami")
        strip.make(typist)
        typist.pick(strip.pick(2)!!)
        assertEquals("神神髪", editor.committed.toString())
        typist.reset()
        type("kami ")
        assertEquals("髪", editor.composing)
        assertEquals(listOf("髪", "神"), converter.history!!.of("かみ").map { it.surface })
    }

    /** Nothing is learned where the field asks for none (incognito), nor with learning off. */
    @Test
    fun nothingIsLearnedInIncognitoFieldsOrWithLearningOff() {
        val h = UserHistory()
        converter.history = h
        typist.learnHere = false
        type("kami  ")
        typist.enter()
        type("kami")
        strip.make(typist)
        typist.pick(strip.pick(2)!!)
        assertEquals(0, h.size)
        assertEquals(0, h.unsaved)
        typist.learnHere = true
        converter.history = null
        type("kami  ")
        typist.enter()
        assertEquals(0, h.size)
    }

    /**
     * Right after a word goes in, the strip offers what may follow it, not bold (Space doesn't take
     * it); a tap puts it in with no space, and the strip goes on from it, after a particle too
     * (content words). After 、 or 。 it offers what a sentence starts with. A space or Backspace
     * ends it.
     */
    @Test
    fun theNextWordsFollowACommit() {
        type("watashi ")
        typist.enter()
        assertEquals("私", editor.committed.toString())
        val s = strip.make(typist) as Strip.Candidates
        assertEquals("は", s.centre)
        assertFalse(s.bold)
        assertTrue(s.more)
        assertTrue(listOf(s.left, s.right).containsAll(listOf("が", "を")))
        typist.pick(strip.pick(1)!!)
        assertEquals("私は", editor.committed.toString())
        assertEquals("", editor.composing)
        val afterWa = strip.make(typist) as Strip.Candidates
        assertTrue(afterWa.toString(), listOf(afterWa.left, afterWa.centre, afterWa.right).none { it in listOf("は", "が", "を", "です") })
        type(" ")
        assertEquals(Strip.Tools, strip.make(typist))
        type("ikimasu")
        typist.enter()
        type("、")
        assertEquals("私", (strip.make(typist) as Strip.Candidates).centre)
        assertEquals(Converter.Segment("、", "、", typist.context!!.rid, typist.context!!.lid, word = false), typist.context)
        typist.pick(strip.pick(1)!!)
        assertEquals("私は　いきます、私", editor.committed.toString())
        type("。")
        assertEquals("私", (strip.make(typist) as Strip.Candidates).centre)
        type("ikimasu")
        typist.enter()
        typist.backspace()
        assertEquals(Strip.Tools, strip.make(typist))
        // Off in the settings: the tool row.
        type("watashi ")
        typist.enter()
        strip.nextWords = false
        assertEquals(Strip.Tools, strip.make(typist))
    }

    /** The word after one picked from the strip is learned with it, and offered first after it. */
    @Test
    fun aNextWordPickedIsLearnedAfterItsWord() {
        converter.history = UserHistory()
        type("watashi ")
        typist.enter()
        strip.make(typist)
        typist.pick(strip.pick("の")!!)
        assertEquals("私の", editor.committed.toString())
        typist.reset()
        type("watashi ")
        typist.enter()
        assertEquals("の", (strip.make(typist) as Strip.Candidates).centre)
    }

    /**
     * A reading converts after the word before it: は after 私 is the particle; alone, at the
     * field's start, it is 葉. A space between them, and it is alone again.
     */
    @Test
    fun aReadingFollowsTheWordBefore() {
        type("ha ")
        assertEquals("葉", editor.composing)
        typist.enter()
        typist.reset()
        type("watashi ")
        typist.enter()
        assertEquals("私", typist.context?.surface)
        type("ha ")
        assertEquals("は", editor.composing)
        typist.enter()
        type(" ")
        assertNull(typist.context)
        type("ha ")
        assertEquals("葉", editor.composing)
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
     * While Space shows a piece of a phrase, the field shows it with the rest of the phrase
     * converted after it (渡し葉); a tap on it takes it for the phrase.
     */
    @Test
    fun aPieceAfterSpaceShowsTheRestOfItsPhrase() {
        type("watashihagakuseidesu ")
        assertEquals("私は学生です", editor.composing)
        val i = typist.cycle.indexOfFirst { it.text.startsWith("渡し") }
        repeat(i) { type(" ") }
        val shown = typist.cycle[typist.selected].text
        assertTrue(shown, shown.startsWith("渡し") && shown.length == 3)
        assertEquals(shown + "学生です", editor.composing)
        typist.pick(typist.cycle[typist.selected])
        assertEquals("", editor.committed.toString())
        assertEquals(shown + "学生です", editor.composing)
        assertEquals(1, typist.focus)
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

    /**
     * Fast enough for every key: the strip, made after each key from Mozc's dictionary (where it is
     * built) with a history full of the owner's choices, takes well under a few milliseconds.
     */
    @Test
    fun theStripIsMadeQuicklyWithAFullHistory() {
        val dict = if (TestModels.realJapaneseAvailable) TestModels.realJapanese() else TestModels.japanese()
        val conv = Converter(dict)
        val h = UserHistory()
        conv.history = h
        // As many choices as it keeps, each after the one before: the words predicted from two kana.
        val kana = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわ"
        var prev: String? = null
        loop@ for (a in kana) for (b in kana) for (w in dict.predict("$a$b", 3)) {
            h.learn(prev, listOf(Converter.Segment(w.surface, w.reading, w.rid, w.lid)))
            prev = w.surface
            if (h.size >= UserHistory.MOST) break@loop
        }
        val ed = FakeEditor()
        val t = KanaTypist(ed, conv)
        val s = KanaStrip()
        val sentences = listOf("watashihagakuseidesu", "kyouhaiitenkidesune", "arigatougozaimasu", "konnichiha", "ashitagakkouniikimasu")
        fun run(): Pair<Double, Int> {
            var keys = 0
            val start = System.nanoTime()
            for (sentence in sentences) {
                t.reset()
                for (c in sentence) {
                    t.text(c.toString())
                    s.make(t)
                    keys++
                }
                t.text(" ")
                s.make(t)
                t.enter()
                s.make(t)
                keys += 2
            }
            return (System.nanoTime() - start) / 1e6 to keys
        }
        repeat(3) { run() }  // warm up
        var total = 0.0
        var keys = 0
        repeat(10) {
            val (ms, n) = run()
            total += ms
            keys += n
        }
        val each = total / keys
        println("strip: %.3f ms a key, with %d chosen words (%s)".format(each, h.size, if (TestModels.realJapaneseAvailable) "Mozc" else "test"))
        assertTrue("%.3f ms".format(each), each < 3)
    }

    /** Text from a panel ends the reading as it is, then goes in. */
    @Test
    fun anEmojiEndsTheReading() {
        type("kami")
        typist.insert("😊")
        assertEquals("かみ😊", editor.committed.toString())
    }
}
