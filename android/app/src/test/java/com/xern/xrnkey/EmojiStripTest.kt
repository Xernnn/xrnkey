package com.xern.xrnkey

import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The strip's emoji as Laban Key offers them (measured on the owner's phone, 2026-11-22): only
 * when the word as it stands is one of the keywords ("cam" 🍊, "khóc" 😢, never bare "khoc"), in
 * the right slot, in every slot from the right when there are no words (five across the strip
 * when there are four or more), for a phrase typed with or without marks and after its Space
 * ("chuc mung sinh nhat"); a tap puts the emoji after the word ("ok 👌") or in its place ("🍊").
 * xrnkey used to offer one for 164 of 1,445 bare syllables, from CLDR's general keywords and the
 * word's readings ("co" 🙂‍↕️, "duoc" 👌).
 */
class EmojiStripTest {
    private class Typed(val maker: StripMaker, val typist: Typist, val field: FakeEditor) {
        fun strip(): Strip = maker.make(typist)
        fun slots(): List<String?> = (strip() as Strip.Candidates).let { listOf(it.left, it.centre, it.right) }

        /** The row of emoji across the strip, when it shows one (empty: the three slots). */
        fun row(): List<String?> = (strip() as Strip.Candidates).row

        /** The slots (or a row's places) that hold an emoji. */
        fun emojiSlots(): List<Int> {
            strip()
            return (0 until Strip.Candidates.ROW).filter { maker.pick(it)?.emoji == true }
        }

        /** A tap on the emoji in `slot` (or a row's place). */
        fun tap(slot: Int) {
            strip()
            val pick = maker.pick(slot)!!
            assertTrue("$slot: $pick", pick.emoji)
            maker.putEmoji(typist, pick)
        }
    }

    private fun typed(m: TestModels.Opened, index: EmojiIndex?, keys: String, before: String = ""): Typed {
        val field = FakeEditor(before)
        val t = Typist(TypingState().also { it.useModels(m.models) }, field, m.models).also {
            it.tonesOnSpace = Typist.TONES_OFF  // as Laban Key, and the owner's phone
            it.reset()
        }
        t.text(keys)
        return Typed(StripMaker(m.models, index), t, field)
    }

    private fun withModels(block: (TestModels.Opened, EmojiIndex) -> Unit) {
        val index = testEmojiIndex()
        TestModels.test().use { block(it, index) }
        index.close()
    }

    @Test
    fun aKeywordAsItStandsPutsItsEmojiInTheRightSlot() = withModels { m, index ->
        // Telex "khocs" is "khóc": a keyword.
        val khoc = typed(m, index, "khocs")
        assertEquals("khóc", khoc.typist.shown)
        val shown = khoc.strip() as Strip.Candidates
        assertEquals("😢", shown.right)
        assertTrue(2 in khoc.emojiSlots())
        // Typed bare it is not: no emoji anywhere.
        val bare = typed(m, index, "khoc")
        bare.strip()
        assertEquals(emptyList<Int>(), bare.emojiSlots())
        // "cam" as typed is the orange, whatever its readings ("cảm", "cám").
        val cam = typed(m, index, "cam")
        assertEquals("🍊", cam.slots()[2])
        // Any case.
        assertEquals("👌", typed(m, index, "OK").slots()[2])
    }

    @Test
    fun ordinaryWordsShowNoEmoji() = withModels { m, index ->
        // CLDR's general keywords, through the words' readings, gave these an emoji: "có" a nod,
        // "được" an OK hand, "đỏ" a clipboard, "nhà" a potted plant, "ngày" a calendar.
        for (keys in listOf("co", "cos", "duoc", "dduowcj", "do", "ddor", "la", "khong", "nha", "nhaf", "ngay", "ngayf", "the", "you")) {
            val t = typed(m, index, keys)
            val strip = t.strip()
            assertEquals("$keys: $strip", emptyList<Int>(), t.emojiSlots())
        }
    }

    @Test
    fun withNoWordsTheEmojiFillTheStripFromTheRight() = withModels { m, index ->
        // Three or fewer: the three slots, the best on the right.
        val kk = typed(m, index, "kk")
        val few = kk.strip() as Strip.Candidates
        assertEquals(listOf(null, "🤣", "😂"), listOf(few.left, few.centre, few.right))
        assertFalse(few.bold)  // Space doesn't commit the middle
        assertEquals(emptyList<String?>(), few.row)
        // Four or more: a row of five across the strip, as Laban Key fills it ("haha": five; it
        // used to show three).
        val hpbd = typed(m, index, "hpbd")
        val shown = hpbd.strip() as Strip.Candidates
        assertEquals(listOf("🎈", "🎁", "🥳", "🎉", "🎂"), shown.row)
        assertEquals(listOf(null, null, null), listOf(shown.left, shown.centre, shown.right))
        assertFalse(shown.bold)
        assertEquals(listOf(0, 1, 2, 3, 4), hpbd.emojiSlots())
        assertEquals(listOf("😁", "😄", "😆", "🤣", "😂"), typed(m, index, "haha").row())
        assertEquals(listOf(null, "😹", "😆", "🤣", "😂"), typed(m, index, "kkk").row())  // four: the left place stays empty
        hpbd.typist.text(" ")
        assertEquals("hpbd ", hpbd.field.text)  // Space keeps the word
        // Each place is a tap on its emoji: a laugh takes the word's place, a wish goes after it.
        typed(m, index, "haha").also { it.tap(0) }.let { assertEquals("😁", it.field.text) }
        typed(m, index, "hpbd").also { it.tap(4) }.let { assertEquals("hpbd 🎂", it.field.text) }
        // With words to offer, one emoji, on the right (with one word, a second on the left:
        // withOneWordASecondEmojiTakesTheLeft).
        val cam = typed(m, index, "cam")
        assertEquals("🍊", cam.slots()[2])
        assertEquals(listOf(2), cam.emojiSlots())
    }

    @Test
    fun aTapPutsTheEmojiAfterTheWordOrInItsPlace() = withModels { m, index ->
        // After the word, as typed or as Telex marked it, with no space after the emoji.
        typed(m, index, "ok").also { it.tap(2) }.let { assertEquals("ok 👌", it.field.text) }
        typed(m, index, "buoonf", before = "Hôm nay ").also { it.tap(2) }.let { assertEquals("Hôm nay buồn 😞", it.field.text) }
        // In the word's place: a lone fruit, a laugh.
        typed(m, index, "cam").also { it.tap(2) }.let { assertEquals("🍊", it.field.text) }
        typed(m, index, "haha", before = "vui ").let { t ->
            t.tap(t.emojiSlots().last())  // the row's right end
            assertEquals("vui 😂", t.field.text)
        }
        // A word of two syllables, after other words: both syllables give way.
        typed(m, index, "caf phee", before = "Đi uống ").also { it.tap(2) }.let { assertEquals("Đi uống ☕", it.field.text) }
        // The next word follows as usual.
        typed(m, index, "cam").also { it.tap(2) }.let { t ->
            t.typist.text("nhes ")
            assertEquals("🍊nhé ", t.field.text)
        }
    }

    @Test
    fun anEmoticonGivesWayToItsEmoji() = withModels { m, index ->
        // ":" and ")" go straight into the field: no word is in progress.
        val t = typed(m, index, "vui :)")
        assertEquals("", t.typist.shown)
        val shown = t.strip() as Strip.Candidates
        assertEquals(listOf(null, "😊", "🙂"), listOf(shown.left, shown.centre, shown.right))
        t.tap(2)
        assertEquals("vui 🙂", t.field.text)
        typed(m, index, "=))").also { it.tap(2) }.let { assertEquals("🤣", it.field.text) }
        typed(m, index, "<3").also { it.tap(2) }.let { assertEquals("❤️", it.field.text) }
        // Nothing for symbols that are no emoticon.
        assertEquals(Strip.Tools, typed(m, index, "+-").strip())
    }

    @Test
    fun aPhraseBringsItsEmojiTypedBareAndAfterItsSpace() = withModels { m, index ->
        // While its last word is typed, without marks as with them.
        val typing = typed(m, index, "chuc mung sinh nhat")
        assertEquals("🎂", typing.slots()[2])
        typing.tap(2)
        assertEquals("chuc mung sinh nhat 🎂", typing.field.text)
        assertEquals("🎂", typed(m, index, "chucs muwngf sinh nhaatj").slots()[2])
        // After its Space a wish of three words or more fills the strip with its emoji, as on
        // Laban Key (it used to keep the next words, "and" and "tag", on the left and in the
        // middle); a tap puts one at the cursor.
        val after = typed(m, index, "chuc mung sinh nhat ")
        assertEquals(listOf("🎈", "🎁", "🥳", "🎉", "🎂"), after.row())
        after.tap(4)
        assertEquals("chuc mung sinh nhat 🎂", after.field.text)
        assertEquals("🙏", typed(m, index, "cam on ban nhieu ").row().last())
        // A shorter one keeps the next words, the emoji on the right; so does one word. The
        // longest keyword wins.
        val thanks = typed(m, index, "cam on ")
        assertEquals(emptyList<String?>(), thanks.row())
        assertEquals("🙏", thanks.slots()[2])
        assertTrue(thanks.slots().toString(), thanks.slots()[1] != null)
        assertEquals("👌", typed(m, index, "ok ").slots()[2])
        assertEquals("😴", typed(m, index, "buoonf ngur").slots()[2])
        // Not across a full stop.
        val stop = typed(m, index, "sinh. nhaatj")
        stop.strip()
        assertEquals(emptyList<Int>(), stop.emojiSlots())
    }

    @Test
    fun anEnglishWordTelexMarkedMatchesAsSpaceEndsIt() = withModels { m, index ->
        // "sorry" shows "sory" while typed (rr undoes the mark), and Space ends it "sorry".
        val sorry = typed(m, index, "sorry")
        assertEquals("sory", sorry.typist.shown)
        assertEquals("😔", sorry.slots()[2])
        sorry.tap(2)
        assertEquals("sorry 😔", sorry.field.text)
    }

    /**
     * With one word to offer, a second emoji takes the left, as Laban Key lays it out: "sorry"
     * was " | sorry | 😔", the left empty.
     */
    @Test
    fun withOneWordASecondEmojiTakesTheLeft() = withModels { m, index ->
        val sorry = typed(m, index, "sorry")
        assertEquals(listOf("🙏", "sorry", "😔"), sorry.slots())
        assertEquals(listOf(0, 2), sorry.emojiSlots())
        assertTrue((sorry.strip() as Strip.Candidates).bold)  // Space ends "sory" as "sorry"
        sorry.tap(0)
        assertEquals("sorry 🙏", sorry.field.text)
    }

    /**
     * The everyday words of Vietnamese chat have their emoji (data/emoji/keywords-hand.tsv): "ngu"
     * as typed (🐮, as chat says it), "uk" and "ừ" (yes), "tức" (angry), "ăn" (eat); none had one.
     */
    @Test
    fun chatWordsHaveTheirEmoji() = withModels { m, index ->
        for ((keys, emoji) in listOf("ngu" to "🐮", "uk" to "👌", "uwf" to "👌", "tuwcs" to "😤", "awn" to "😋", "camon" to "🙏", "huhu" to "😭")) {
            val t = typed(m, index, keys)
            val shown = t.strip() as Strip.Candidates
            val best = shown.row.lastOrNull() ?: shown.right
            assertEquals("$keys: $shown", emoji, best)
        }
        // Space keeps the letters: an emoji is only offered.
        typed(m, index, "ngu").also { it.typist.text(" ") }.let { assertEquals("ngu ", it.field.text) }
    }

    @Test
    fun noEmojiWhenOffInAPasswordFieldOrWithoutTheTable() = withModels { m, index ->
        val off = typed(m, index, "cam")
        off.maker.emojiSuggestions = false
        off.strip()
        assertEquals(emptyList<Int>(), off.emojiSlots())
        val password = typed(m, index, "")
        password.typist.raw = true
        password.typist.text(":)")
        assertEquals(Strip.Tools, password.strip())
        EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!.use { plain ->
            val t = typed(m, plain, "cam")
            t.strip()
            assertEquals(emptyList<Int>(), t.emojiSlots())
        }
    }

    @Test
    fun anEmojiTheOwnerHidIsNotOfferedAndHiddenWordsLeaveTheEmojiAlone() = withModels { m, index ->
        // The strip's remove mark on an emoji hides it: the keyword's next emoji takes its place.
        val ok = typed(m, index, "ok")
        ok.maker.hidden = { it == "👌" }
        val shown = ok.strip() as Strip.Candidates
        assertEquals("👍", shown.right)
        assertFalse("👌" in listOf(shown.left, shown.centre, shown.right))
        // Hidden words don't touch the emoji.
        val cam = typed(m, index, "cam")
        cam.maker.hidden = { it == "cảm" || it == "cảm ơn" }
        assertEquals("🍊", cam.slots()[2])
        val after = typed(m, index, "chuc mung sinh nhat ")
        after.maker.hidden = { ' ' !in it && it.any(Char::isLetter) }  // every next word hidden
        assertEquals("🎂", after.row().last())
    }

    @Test
    fun aReplaceLeavesTextTheFieldNoLongerShows() = withModels { m, _ ->
        val field = FakeEditor("abc ")
        val t = Typist(TypingState().also { it.useModels(m.models) }, field, m.models).also { it.reset() }
        t.text("cam")
        t.replaceWith("🍊", "xyz ")  // the field shows "abc ", not "xyz ": only the word goes
        assertEquals("abc 🍊", field.text)
        assertEquals("", t.shown)
        assertTrue(t.before().endsWith("🍊"))
    }

    /** Laban Key on the owner's phone: "cam" -> cám ơn | cảm ơn | 🍊. The middle and the emoji with the real models. */
    @Test
    fun camIsTheOrangeOnTheRightAsOnLaban() {
        assumeTrue(TestModels.realAvailable)
        val index = testEmojiIndex()
        TestModels.real().use { m ->
            val cam = typed(m, index, "cam")
            val shown = cam.strip() as Strip.Candidates
            assertEquals("cảm ơn", shown.centre)
            assertEquals("🍊", shown.right)
            assertTrue(shown.more)  // the pair the emoji took the place of is still in the list
            cam.tap(2)
            assertEquals("🍊", cam.field.text)
        }
        index.close()
    }
}
