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

    /**
     * A word finished with its marks: Space commits it as it stands, so the strip offers what
     * comes after it, as Laban Key does ("banj" -> bạn gái | bạn bè | bạn ấy), not the word
     * again. It used to show "được" in the middle, in bold.
     */
    @Test
    fun aFinishedWordOffersWhatComesAfterItNotItself() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                val f = FakeEditor()
                val t = Typist(s, f).also { it.reset() }
                t.text("dduowcj")
                val maker = StripMaker(m.models)
                val shown = maker.make(t) as Strip.Candidates
                // The test text has "được rồi", "được không", "được việc" ...
                assertTrue(shown.slots().toString(), shown.slots().none { it == "được" })
                assertTrue(shown.slots().toString(), shown.centre!!.startsWith("được "))
                assertFalse(shown.bold)
                assertEquals("được", maker.centre)  // what an emoji pick puts before the emoji
                // A continuation the owner hid with the remove mark is offered nowhere.
                val gone = shown.centre!!
                maker.hidden = { it == gone }
                val without = maker.make(t) as Strip.Candidates
                assertFalse(without.toString(), gone in without.slots() || gone in without.extra)
                maker.hidden = { false }
                val pick = maker.pick(1)!!.text
                t.pick(pick)
                assertEquals("$pick ", f.text)
            }
        }
    }

    @Test
    fun aFinishedWordOffersWhatFollowsItAsLabanDoes() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            // Laban on the owner's phone: banj -> bạn gái | bạn bè | bạn ấy, khoong -> không có |
            // không thể | không biết, mootj -> một mình | một số | một cái.
            for ((keys, word) in listOf("banj" to "bạn", "khoong" to "không", "mootj" to "một", "nguowif" to "người", "dduowcj" to "được")) {
                val shown = strip(m, keys).shown
                val why = "$keys: ${shown.slots()}"
                assertTrue(why, shown.slots().all { it != null && it.startsWith("$word ") })
                typedSpace(m, keys, "$word ")
            }
            // A tone typed keeps the longer words to that tone: "gif" (gì) is never "giá" or "gia",
            // "nos" (nó) never "now".
            assertTrue(strip(m, "gif").shown.slots().none { it == "giá" || it == "gia" })
            assertTrue(strip(m, "nos").shown.slots().none { it == "now" || it == "not" })
            // A word on its way to a longer one still offers it: "khoon" (khôn) has "không".
            assertTrue(strip(m, "khoon").shown.slots().toString(), "không" in strip(m, "khoon").shown.slots())
        }
    }

    /**
     * A word finished at a sentence's start is continued much as it would be anywhere. The
     * models learnt how sentences start from written text ("Làm thế nào", "Ngày nay", "Bạn có
     * thể"): counted in full, that put "làm thế" and "ngày nay" in the middle for the first word
     * typed, and kept "bạn bè" and "bạn gái" out of the strip and its panel.
     */
    @Test
    fun aFinishedWordAtASentencesStartIsContinuedMuchAsAnywhere() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            assertEquals("làm việc", strip(m, "lamf").shown.centre)
            assertEquals("ngày càng", strip(m, "ngayf").shown.centre)
            val ban = strip(m, "banj").shown
            val offered = ban.slots() + ban.extra
            assertTrue(offered.toString(), "bạn bè" in offered && "bạn gái" in offered)
        }
    }

    /** The part of a slot already in the field is drawn grey, as Laban Key draws it. */
    @Test
    fun thePartAlreadyTypedIsGrey() {
        TestModels.test().use { m ->
            val shown = strip(m, "dduowcj").shown
            shown.slots().forEachIndexed { i, text ->
                assertEquals(shown.slots().toString(), if (text != null && text.startsWith("được ")) 4 else 0, shown.grey.getOrElse(i) { 0 })
            }
            assertTrue(shown.grey.contains(4))
        }
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            // xịn | xin lỗi | xinh: the "xin" of "xin lỗi" is what was typed; "cảm ơn" for "cam" is
            // a reading, none of it typed.
            assertEquals(listOf(0, 3, 0), strip(m, "xin").shown.grey)
            assertEquals(0, strip(m, "cam").shown.grey.getOrElse(1) { 0 })
        }
    }

    /**
     * After a Space, a next word that goes with the word before shows with it, that word grey, as
     * Laban Key mixes them ("một" -> một mình | một số | một cái, but "tôi" -> về | đi | đang); a
     * tap adds the next word. The next-word strip used to hold single words only.
     */
    @Test
    fun afterASpaceTheNextWordsMixInPhrasesOfTheWordBefore() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            val mot = strip(m, "mootj ")
            val slots = mot.shown.slots()
            val phrase = slots.firstOrNull { it != null && it.startsWith("một ") }
            assertTrue(slots.toString(), phrase != null)
            slots.forEachIndexed { i, text ->
                assertEquals(slots.toString(), if (text != null && text.startsWith("một ")) 3 else 0, mot.shown.grey.getOrElse(i) { 0 })
            }
            // Hidden with the remove mark, the phrase is offered no more.
            mot.maker.hidden = { it == phrase }
            val hid = mot.maker.make(mot.typist) as Strip.Candidates
            assertTrue(hid.slots().toString(), phrase !in hid.slots() && phrase!!.removePrefix("một ") !in hid.slots())
            mot.maker.hidden = { false }
            mot.maker.make(mot.typist)
            val pick = mot.maker.pick(slots.indexOf(phrase))!!.text
            assertEquals(phrase!!.removePrefix("một "), pick)
            mot.typist.pick(pick)
            assertEquals("$phrase ", mot.field.text)
            // After "tôi" the likeliest words follow anything: they stand alone.
            val toi = strip(m, "tooi ").shown
            assertTrue(toi.slots().toString(), toi.slots().none { it?.startsWith("tôi ") == true })
            // With a comma between, a phrase would not be what the field shows.
            val comma = strip(m, "mootj, ").shown
            assertTrue(comma.slots().toString(), comma.slots().none { it?.startsWith("một ") == true })
        }
    }

    /** `keys` and Space end as `want`: the strip changes nothing Space does. */
    private fun typedSpace(m: TestModels.Opened, keys: String, want: String) {
        val typed = strip(m, keys)
        typed.typist.text(" ")
        assertEquals(want, typed.field.text)
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
                assertTrue(shown.slots().toString(), shown.centre!!.startsWith("nhật"))  // or what follows it
                assertEquals("🎂", shown.right)  // "chúc mừng sinh nhật"
                val pick = strip.pick(2)!!
                assertTrue(pick.emoji)
                strip.emojiSuggestions = false
                val plain = strip.make(t) as Strip.Candidates
                assertTrue(plain.slots().toString(), plain.centre!!.startsWith("nhật"))
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
     * them, alone or with its next word, or the word itself with a word that goes with it; an
     * English completion goes to the sides. On the phone after an English word, "cam" put "came"
     * in the middle (Laban: "cảm ơn"). Space is unchanged.
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
                    // Vietnamese: a reading (or the word), or a longer Vietnamese word when that is
                    // likelier ("tôi nha": "nhận", where "nhà" is rare; "an": "ảnh", where Laban
                    // has "anh"), never English.
                    assertTrue(why, bare(first).startsWith(w) && (first == w || first.any { it.code >= 0x80 }))
                    typed.typist.text(" ")
                    assertEquals("$before$w ", typed.field.text)  // Space keeps what was typed
                }
                val cam = strip(m, "cam", before, index).shown
                assertTrue(cam.slots().toString(), cam.centre!!.startsWith("cảm") || cam.centre!!.startsWith("cám"))
            }
            assertEquals("cảm ơn", strip(m, "cam", emoji = index).shown.centre)
            // "em": Space keeps it; the middle goes on with it ("em bé"), never "email".
            for (before in listOf("", "tôi ")) {
                val em = strip(m, "em", before, index).shown
                assertTrue(em.slots().toString(), em.centre!!.startsWith("em ") || em.centre!!.any { it.code >= 0x80 })
            }
            assertTrue(strip(m, "hel", emoji = index).shown.centre in setOf("hello", "help"))
            val meet = strip(m, "meet", emoji = index).shown
            assertEquals("meet", meet.centre)
            assertTrue(meet.bold)
        }
        index.close()
    }

    /**
     * Space keeps the word as typed, so the middle doesn't show it again: it holds the best
     * other candidate, not in bold, as Laban Key's does ("em", "the", "ok" and "love" were in
     * the middle as typed, in bold, a slot spent on what Space commits anyway). The word takes
     * the middle only when there is nothing else, in bold. A fix Space makes is in the middle,
     * in bold: bold is what Space commits.
     */
    @Test
    fun theMiddleNeverRepeatsWhatSpaceKeeps() {
        TestModels.test().use { m ->
            for (w in listOf("em", "the", "dduowcj", "banj", "ngu", "toi", "cup", "qzx", "teh")) {
                val typed = strip(m, w)
                val shown = typed.shown
                val word = typed.typist.shown
                val commits = typed.typist.preview()
                val why = "$w: $shown"
                if (shown.centre == word) {
                    assertTrue(why, shown.left == null && shown.right == null && shown.extra.isEmpty())
                }
                assertEquals(why, shown.centre == (commits ?: word), shown.bold)
                assertTrue(why, listOf(shown.left, shown.right).none { it == word })
            }
            for (w in listOf("em", "the")) {
                val shown = strip(m, w).shown
                assertTrue(shown.toString(), shown.centre != w && !shown.bold)
                typedSpace(m, w, "$w ")  // Space still keeps it
            }
            assertEquals("qzx", strip(m, "qzx").shown.centre)  // nothing else: the word, in bold
            assertTrue(strip(m, "qzx").shown.bold)
            assertEquals("the", strip(m, "teh").shown.centre)  // Space fixes it: the fix, in bold
            assertTrue(strip(m, "teh").shown.bold)
        }
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            // What else the letters may be, the likeliest in the middle; Space keeps them.
            assertEquals(listOf("theo", "thế", "they"), strip(m, "the").shown.slots())
            assertEquals("okay", strip(m, "ok").shown.centre)
            assertTrue(strip(m, "love").shown.slots().toString(), strip(m, "love").shown.centre in setOf("loved", "lovely"))
            assertEquals("thanks to", strip(m, "thanks").shown.centre)
            val em = strip(m, "em").shown
            assertTrue(em.slots().toString(), em.centre!!.startsWith("em ") && "email" in em.slots())
            assertEquals(2, em.grey[1])  // "em" grey: it is in the field
            for (w in listOf("the", "ok", "love", "thanks", "em")) {
                assertFalse(w, strip(m, w).shown.bold)
                typedSpace(m, w, "$w ")
            }
            // An English word's next word shows with it only when it nearly always follows it:
            // the English model's likeliest after "love" at a sentence's start is "island".
            assertTrue(strip(m, "love").shown.slots().none { it?.startsWith("love ") == true })
        }
    }

    /**
     * Laban's middle for letters typed without marks is mostly a reading alone, with the reading
     * and its next word at a side, and a longer Vietnamese word when that is likelier: "toan" ->
     * toàn (xrnkey had "toàn bộ"), "vi" -> việc (xrnkey had "vì"), "ngu" -> người (had "ngủ").
     * A pair nearly always said together keeps the middle ("cảm ơn").
     */
    @Test
    fun theMiddleIsTheReadingOrALongerWordAsOnLaban() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            val toan = strip(m, "toan").shown
            assertEquals(toan.slots().toString(), "toàn", toan.centre)
            assertTrue(toan.slots().toString(), "toàn bộ" in toan.slots())
            assertEquals(strip(m, "vi").shown.slots().toString(), "việc", strip(m, "vi").shown.centre)
            assertEquals(strip(m, "ngu").shown.slots().toString(), "người", strip(m, "ngu").shown.centre)
            assertEquals("cảm ơn", strip(m, "cam").shown.centre)
            // Never an English completion there for Vietnamese letters, and Space is unchanged.
            for (w in listOf("toan", "vi", "ngu")) typedSpace(m, w, "$w ")
        }
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
