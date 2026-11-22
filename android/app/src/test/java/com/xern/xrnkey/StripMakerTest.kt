package com.xern.xrnkey

import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.engine.TypingState
import java.io.File
import com.xern.xrnkey.ime.HiddenWords
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StripMakerTest {
    @Test
    fun aWordTypedWithoutMarksShowsItsReadingsTheBestInTheCentre() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                val f = FakeEditor("Tôi ")
                val t = Typist(s, f, m.models).also { it.tonesOnSpace = Typist.TONES_ALWAYS; it.reset() }
                val strip = StripMaker(m.models)
                assertTrue(strip.make(t) is Strip.Candidates)  // after "Tôi ": the next word
                t.text("khong ")
                t.text("duoc")
                val shown = strip.make(t) as Strip.Candidates
                assertEquals("được", shown.centre)
                // The left is another reading, alone or with its next word (which one depends on
                // the model: with the real one "dược"); the right, a likely next word with the
                // middle, or another reading.
                val readings = m.models.restoreWord(t.context(), "duoc", 6).map { it.text }
                fun reading(s: String?) = s != null && readings.any { s == it || s.startsWith("$it ") }
                assertTrue(reading(shown.left) && !shown.left!!.startsWith("được"))
                assertTrue(reading(shown.right))
                assertEquals(StripMaker.Pick("được"), strip.pick(1))
                t.pick(strip.pick(1)!!.text)
                assertEquals("Tôi không được ", f.text)  // Space restored "khong"
                assertTrue(strip.make(t) is Strip.Candidates)  // the next word, now
            }
        }
    }

    /**
     * The candidates the strip has no room for are its extra ones, best first, for the panel slid
     * up from it: none of the three shown, and there are some exactly when the dots are.
     */
    @Test
    fun theStripsExtraCandidatesAreTheRest() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                val t = Typist(s, FakeEditor(), m.models).also { it.reset() }
                val strip = StripMaker(m.models)
                var some = 0
                for (typed in listOf("duoc", "khong", "toi", "co", "x")) {
                    t.reset()
                    t.text(typed)
                    val shown = strip.make(t) as Strip.Candidates
                    assertEquals(typed, shown.more, shown.extra.isNotEmpty())
                    assertTrue(typed, shown.extra.none { it == shown.left || it == shown.centre || it == shown.right })
                    assertEquals(shown.extra.distinct(), shown.extra)
                    if (shown.extra.isNotEmpty()) some++
                }
                assertTrue(some > 0)
            }
        }
    }

    /**
     * A suggestion the owner hid with the remove mark is offered nowhere, whichever model it came
     * from: not in a slot, not in the panel, not as a next word. Shown again, it is back.
     */
    @Test
    fun aHiddenSuggestionIsOfferedNoMoreUntilShownAgain() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                val dir = kotlin.io.path.createTempDirectory().toFile()
                val hidden = HiddenWords(File(dir, "hidden.txt"))
                val strip = StripMaker(m.models).also { it.hidden = { t -> t in hidden } }
                val t = Typist(s, FakeEditor("Tôi không "), m.models).also { it.reset() }
                t.text("duoc")
                val before = strip.make(t) as Strip.Candidates
                fun offered(c: Strip.Candidates) = listOfNotNull(c.left, c.centre, c.right) + c.extra
                val gone = listOfNotNull(before.left, before.right).first { it.any(Char::isLetter) }
                hidden.hide(gone)
                val after = strip.make(t) as Strip.Candidates
                assertFalse(gone in offered(after))
                // Kept on disk: read again, still hidden.
                assertTrue(gone in HiddenWords(File(dir, "hidden.txt")))
                hidden.show(gone)
                assertEquals(before, strip.make(t))
                // The next words after a Space.
                val after2 = Typist(s, FakeEditor("Tôi không "), m.models).also { it.reset() }
                val next = strip.make(after2) as Strip.Candidates
                val word = next.centre!!
                hidden.hide(word)
                assertFalse(word in offered(strip.make(after2) as Strip.Candidates))
                hidden.show(word)
                assertEquals(next, strip.make(after2))
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun aWordTypedWithMarksShowsAsItIs() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                val f = FakeEditor()
                val t = Typist(s, f).also { it.reset() }
                t.text("dduowcj")
                val shown = StripMaker(m.models).make(t) as Strip.Candidates
                assertEquals("được", shown.centre)
                assertEquals(null, shown.left)
                assertTrue(shown.bold)
                // On the right, the word with what likely follows it ("được rồi"), if anything.
                assertTrue(shown.right == null || shown.right!!.startsWith("được "))
            }
        }
    }

    @Test
    fun anEmojiThatGoesWithTheWordsTakesTheRightSlot() {
        val index = testEmojiIndex()  // with the strip's keyword table (EmojiStripTest)
        TestModels.test().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                val f = FakeEditor("Chúc mừng ")
                val t = Typist(s, f, m.models).also { it.tonesOnSpace = Typist.TONES_ALWAYS; it.reset() }
                val strip = StripMaker(m.models, index)
                t.text("sinh nhaatj")
                val shown = strip.make(t) as Strip.Candidates
                assertEquals("🎂", shown.right)  // "chúc mừng sinh nhật"
                val pick = strip.pick(2)!!
                assertTrue(pick.emoji)
                strip.emojiSuggestions = false
                val plain = strip.make(t) as Strip.Candidates
                assertEquals("nhật", plain.centre)
                assertTrue(plain.right != shown.right)
            }
        }
        index.close()
    }

    @Test
    fun afterAWordAndASpaceTheStripOffersTheNextWord() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                val f = FakeEditor()
                val t = Typist(s, f, m.models).also { it.tonesOnSpace = Typist.TONES_ALWAYS; it.reset() }
                val strip = StripMaker(m.models)
                assertEquals(Strip.Tools, strip.make(t))  // an empty field: the tools
                t.text("Tooi khoong ")
                val next = strip.make(t) as Strip.Candidates
                // The test corpus has "không thể / có / được / biết".
                assertTrue(next.centre in setOf("thể", "có", "được", "biết"))
                t.pick(strip.pick(1)!!.text)
                assertEquals("Tôi không ${next.centre} ", f.text)
                assertTrue(strip.make(t) is Strip.Candidates)  // and on to the next
                t.text(" ")  // two spaces: ". ", and words that start a sentence
                assertTrue(f.text.endsWith(". "))
                assertTrue(strip.make(t) is Strip.Candidates)
                t.enter()
                assertEquals(Strip.Tools, strip.make(t))  // a new line: the tools
                strip.suggestions = false
                t.text("ddi ")
                assertEquals(Strip.Tools, strip.make(t))
                strip.suggestions = true
                t.raw = true
                assertEquals(Strip.Tools, strip.make(t))
            }
        }
    }

    /** A strip for `keys` typed after `before`, with what made it. */
    private class Typed(val shown: Strip.Candidates, val maker: StripMaker, val typist: Typist, val field: FakeEditor)

    private fun strip(m: TestModels.Opened, keys: String, before: String = "", emoji: EmojiIndex? = null): Typed {
        val field = FakeEditor(before)
        val t = Typist(TypingState().also { it.useModels(m.models) }, field, m.models).also { it.reset() }
        val maker = StripMaker(m.models, emoji)
        t.text(keys)
        return Typed(maker.make(t) as Strip.Candidates, maker, t, field)
    }

    private fun Strip.Candidates.slots() = listOf(left, centre, right)

    /**
     * Laban Key 26.09.01 on the owner's phone (Vietnamese + English, Telex, auto-correct
     * Flexible), [left | middle | right]:
     *   hel  -> help | hello | held
     *   xin  -> xịn | xin lỗi | xinh
     *   meet -> mệt mỏi | mệt | mệt rồi
     *   cam  -> cám ơn | cảm ơn | 🍊
     * The same with the real models, as far as they allow: they put "helping" before "held"
     * and have no "mệt rồi", and xrnkey ends "meet" as the English it is, so that is in the
     * middle, in bold. (The emoji come from the keyword table, not loaded here: EmojiStripTest.)
     */
    @Test
    fun theStripCompletesAndPairsWordsAsLabanDoes() {
        assumeTrue(TestModels.realAvailable)
        val index = EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!
        TestModels.real().use { m ->
            val hel = strip(m, "hel", emoji = index).shown
            assertEquals("hello", hel.centre)
            assertEquals("help", hel.left)
            assertTrue(hel.right!!.startsWith("hel") && hel.right!!.length > 3)
            assertFalse(hel.bold)

            val xin = strip(m, "xin", emoji = index).shown
            assertEquals(listOf("xịn", "xin lỗi", "xinh"), xin.slots())
            assertFalse(xin.bold)

            val meet = strip(m, "meet", emoji = index).shown
            assertEquals(listOf("mệt mỏi", "meet", "mệt"), meet.slots())
            assertTrue(meet.bold)  // Space ends it "meet"

            val cam = strip(m, "cam", emoji = index).shown
            assertEquals("cảm ơn", cam.centre)
            assertTrue("cám ơn" in cam.slots())
            assertFalse(cam.bold)
        }
        index.close()
    }

    /** The letters a text is typed with, without marks: "cảm ơn" -> "cam on". */
    private fun bare(s: String) = java.text.Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), java.text.Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /**
     * Letters that are a common Vietnamese syllable keep the middle Vietnamese: a reading of
     * them, alone or with its next word, or the word itself; an English completion goes to the
     * sides. On the phone after an English word, "cam" put "came" in the middle (Laban: "cảm
     * ơn"). Letters that read as English keep English there; Space is unchanged.
     */
    @Test
    fun vietnameseLettersKeepTheMiddleVietnamese() {
        assumeTrue(TestModels.realAvailable)
        val index = EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!
        val vietnamese = "cam an ban con toi cho den di la ma minh lam hom nha anh em co khong duoc xin".split(" ")
        TestModels.real().use { m ->
            for (before in listOf("", "meet ", "tôi ")) {
                for (w in vietnamese) {
                    val typed = strip(m, w, before, index)
                    val first = typed.shown.centre!!.substringBefore(' ')
                    val why = "'$before$w': ${typed.shown.slots()}"
                    // Vietnamese: a reading (or the word), or at least a Vietnamese completion
                    // ("tôi nha": "nhận", where "nhà" is rare); at a sentence's start, a reading.
                    assertTrue(why, bare(first).startsWith(w) && (first == w || first.any { it.code >= 0x80 }))
                    if (before.isEmpty()) assertEquals(why, w, bare(first))
                    typed.typist.text(" ")
                    assertEquals("$before$w ", typed.field.text)  // Space keeps what was typed
                }
                val cam = strip(m, "cam", before, index).shown
                assertTrue(cam.slots().toString(), cam.centre!!.startsWith("cảm") || cam.centre!!.startsWith("cám"))
            }
            assertEquals("cảm ơn", strip(m, "cam", emoji = index).shown.centre)
            assertEquals("em", strip(m, "em", emoji = index).shown.centre)  // not "êm"
            assertEquals("em", strip(m, "em", "tôi ", index).shown.centre)  // not "emphasized"
            // English: the word itself where it reads as English, in bold; completions where the
            // letters are no word.
            for (w in listOf("the", "ok", "good", "love")) {
                val shown = strip(m, w, emoji = index).shown
                assertEquals(shown.slots().toString(), w, shown.centre)
                assertTrue(shown.bold)
            }
            assertTrue(strip(m, "hel", emoji = index).shown.centre in setOf("hello", "help"))
            val meet = strip(m, "meet", emoji = index).shown
            assertEquals("meet", meet.centre)
            assertTrue(meet.bold)
        }
        index.close()
    }

    @Test
    fun spaceKeepsWhatWasTypedAndATapTakesTheCompletion() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            for (w in listOf("hel", "xin", "cam")) {
                val typed = strip(m, w)
                typed.typist.text(" ")
                assertEquals("$w ", typed.field.text)
            }
            for ((w, taken) in listOf("hel" to "hello ", "xin" to "xin lỗi ")) {
                val typed = strip(m, w)
                typed.typist.pick(typed.maker.pick(1)!!.text)
                assertEquals(taken, typed.field.text)
            }
            // Space fixes "teh", so "the" is in the middle, in bold.
            val teh = strip(m, "teh").shown
            assertEquals("the", teh.centre)
            assertTrue(teh.bold)
        }
    }

    @Test
    fun aWordTypedInPartIsCompletedInBothLanguages() {
        TestModels.test().use { m ->
            // English: "boost" and "boots" in the test corpus.
            // (Telex shows "boo" as "bô"; the keys are completed as English.)
            val boo = strip(m, "boo", before = "I need the ").shown
            assertTrue(boo.slots().toString(), boo.slots().containsAll(listOf("boost", "boots")))
            val pic = strip(m, "pic", before = "The ").shown
            assertEquals(pic.slots().toString(), "picture", pic.centre)  // "The picture hangs"
            assertFalse(pic.bold)
            // Vietnamese, accented: "công ty dược phẩm" in the test corpus.
            val duo = strip(m, "duo", before = "Công ty ").shown
            assertTrue(duo.slots().toString(), duo.slots().any { it == "dược" || it == "dược phẩm" })
            // What was typed has no slot of its own while there is anything else to offer.
            for (shown in listOf(pic, duo)) assertTrue(shown.slots().none { it == "pic" || it == "duo" })
            // Letters that are no word's start leave the word as it is, in bold.
            val q = strip(m, "qzx").shown
            assertEquals("qzx", q.centre)
            assertTrue(q.slots().toString(), q.bold)
        }
    }

    @Test
    fun aWordTypedWithMarksButNoToneOffersItsTones() {
        TestModels.test().use { m ->
            // "bieet" shows "biêt", no word without its tone: Space ends it as typed, and the
            // strip offers its tones ("biết", "biệt": the test model has them alike).
            val shown = strip(m, "bieet", before = "Tôi không ").shown
            assertEquals("bieet", shown.centre)
            assertTrue(shown.bold)
            val toned = shown.slots().filter { it != null && (it.startsWith("biết") || it.startsWith("biệt")) }
            assertTrue(shown.slots().toString(), toned.isNotEmpty() && shown.left in toned)
        }
    }

    @Test
    fun aWordTypedWithMarksIsCompletedWithTheMarksItHas() {
        TestModels.test().use { m ->
            // "nguwow" shows "ngươ": "người" starts like it, "nguồn" would not.
            val shown = strip(m, "nguwow").shown
            assertTrue(shown.slots().toString(), shown.slots().any { it == "người" || it?.startsWith("người ") == true })
            assertTrue(shown.slots().toString(), shown.slots().none { it?.startsWith("nguồn") == true })
        }
    }
}
