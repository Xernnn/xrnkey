package com.xern.xrnkey

import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.Typist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypistTest {
    private fun typist(field: FakeEditor, state: TypingState = TypingState()) =
        Typist(state, field).also { it.reset() }

    @Test
    fun theWordInProgressIsComposingTextAndSpaceCommitsIt() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("chaof")
        assertEquals("", f.committed.toString())
        assertEquals("chào", f.composing)
        t.text(" ")
        assertEquals("chào ", f.text)
        assertEquals("", f.composing)
    }

    @Test
    fun backspaceEditsTheWordThenTheField() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("xin toans")
        t.backspace()
        assertEquals("xin tóa", f.text)  // the tone stays, placed for "toa"
        t.backspace(); t.backspace(); t.backspace()
        assertEquals("xin ", f.text)
        assertEquals("", f.composing)
        t.backspace()
        assertEquals("xin", f.text)  // no word in progress: the field's own Backspace
    }

    @Test
    fun enterCommitsTheWordAndReachesTheFieldInOnePress() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("ddi")
        t.enter()
        assertEquals("đi\n", f.text)
        assertEquals(listOf("compose:d", "compose:đ", "compose:đi", "commit:đi", "enter"), f.log)
    }

    @Test
    fun digitsAndPunctuationAreTextAndEndTheWord() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("vieetj, 2 laanf.")
        assertEquals("việt, 2 lần.", f.text)
    }

    @Test
    fun aPasswordFieldGetsEveryKeyAsTyped() {
        val f = FakeEditor()
        val t = typist(f)
        t.raw = true
        t.text("aa ddj")
        t.backspace()
        assertEquals("aa dd", f.text)
        assertTrue(f.log.none { it.startsWith("compose") })
    }

    @Test
    fun movingTheCursorEndsTheWordWhereItIs() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("terms")
        assertEquals("tém", f.composing)  // shown as Telex until the word ends
        t.finish()
        assertEquals("terms", f.text)  // the core restores it as it ends
        t.text("vieetj")
        t.finish()
        assertEquals("termsviệt", f.text)
    }

    @Test
    fun theContextIsTheLastWordsOfTheSentence() {
        val f = FakeEditor("Hôm qua đi chơi. Tôi ")
        val t = typist(f)
        assertEquals(listOf("<s>", "Tôi"), t.context())
        t.text("khoong bieets ")
        assertEquals(listOf("Tôi", "không", "biết"), t.context().takeLast(3))
        t.enter()
        assertEquals(listOf("<s>"), t.context())
    }

    @Test
    fun theWordBeforeTheCursorIsTheOneASpaceFollows() {
        val f = FakeEditor("Tôi có ")
        val t = typist(f)
        assertEquals("có", t.wordBefore())
        t.text("mootj")
        assertEquals(null, t.wordBefore())  // a word in progress
        t.text(", ")
        assertEquals(null, t.wordBefore())  // a comma between
        t.text("hai ")
        assertEquals("hai", t.wordBefore())
        t.text(" ")
        assertEquals(null, t.wordBefore())  // two spaces
    }

    @Test
    fun aHeldLettersFormJoinsTheWordInProgress() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("vi"); t.text("ê"); t.text("tj")
        t.text(" ")
        assertEquals("việt ", f.text)
        t.text("đi ")
        assertEquals("việt đi ", f.text)
    }

    @Test
    fun theKeysForEachForm() {
        assertEquals("eej", com.xern.xrnkey.ime.VietKeys.of('ệ', com.xern.xrnkey.engine.TypingState.TELEX))
        assertEquals("uw", com.xern.xrnkey.ime.VietKeys.of('ư', com.xern.xrnkey.engine.TypingState.TELEX))
        assertEquals("OWX", com.xern.xrnkey.ime.VietKeys.of('Ỡ', com.xern.xrnkey.engine.TypingState.TELEX)?.uppercase())
        assertEquals("a8", com.xern.xrnkey.ime.VietKeys.of('ă', com.xern.xrnkey.engine.TypingState.VNI))
        assertEquals("e65", com.xern.xrnkey.ime.VietKeys.of('ệ', com.xern.xrnkey.engine.TypingState.VNI))
        assertEquals("dd", com.xern.xrnkey.ime.VietKeys.of('đ', com.xern.xrnkey.engine.TypingState.TELEX))
        assertEquals(null, com.xern.xrnkey.ime.VietKeys.of('e', com.xern.xrnkey.engine.TypingState.TELEX))
        assertEquals(null, com.xern.xrnkey.ime.VietKeys.of('ç', com.xern.xrnkey.engine.TypingState.TELEX))
    }

    /**
     * Every toned form a held vowel offers goes in as the Telex keys that make it, so the word
     * goes on taking marks: "v" + held "ặ" + "n" is "vặn", a capital as typed.
     */
    @Test
    fun aHeldTonedFormJoinsTheWordInProgress() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("v"); t.text("ặ"); t.text("n")
        t.text(" ")
        t.text("Ữ"); t.text("a")
        t.text(" ")
        assertEquals("vặn Ữa ", f.text)
    }

    /**
     * Backspace that leaves the cursor at the end of a word opens it again, as Laban Key does:
     * Telex goes on marking it ("việt␣⌫s" is "viết"), and a Backspace more edits it like the word
     * in progress it is again.
     */
    @Test
    fun backspaceAfterSpaceOpensTheWordAgain() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("vieetj ")
        t.backspace()
        assertEquals("việt", f.text)
        assertEquals("việt", f.composing)
        t.text("s ")
        assertEquals("viết ", f.text)
        t.text("xin chao ")
        t.backspace(); t.backspace()
        assertEquals("viết xin cha", f.text)
        assertEquals("cha", f.composing)
        assertEquals(listOf("<s>", "viết", "xin"), t.context())
        t.text("f ")
        assertEquals("viết xin chà ", f.text)
    }

    /** The cursor put at `at` in the field (a tap there), as the service then opens a word. */
    private fun tap(f: FakeEditor, t: Typist, at: Int): Boolean {
        t.finish()
        val all = f.text
        f.committed.setLength(0)
        f.committed.append(all.take(at))
        f.after = all.drop(at)
        t.reset(at)
        return t.reopenBefore(f.textAfter(1)) || t.touch(f.textAfter(Typist.TOUCH_READ))
    }

    /**
     * A tap inside a word opens it as Laban Key does: underlined, the cursor where the finger put
     * it, the text as it was. A pick replaces the whole word, with no second space before the
     * space that follows it; any key ends it where it is and goes in at the cursor.
     */
    @Test
    fun aTapInsideAWordOpensItWhole() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("xin chao ban")
        assertTrue(tap(f, t, 6))
        assertEquals("xin chao ban", f.text)
        assertEquals(2 to 2, f.marked)
        assertTrue(t.touched)
        assertEquals("chao", t.shown)
        assertEquals(6, t.cursor())
        assertEquals(listOf("<s>", "xin"), t.context())
        t.pick("chào")
        assertEquals("xin chào ban", f.text)
        assertEquals("xin chào", f.committed.toString())
        assertEquals(8, t.cursor())
        // Inside the last word: nothing follows, so the pick gets its space.
        assertTrue(tap(f, t, 10))
        assertEquals("ban", t.shown)
        t.pick("bạn")
        assertEquals("xin chào bạn ", f.text)
        // A key ends the word where it is and goes in at the cursor.
        assertTrue(tap(f, t, 1))
        assertEquals("xin", t.shown)
        t.text("a")
        assertEquals("xain chào bạn ", f.text)
        assertEquals(null, f.marked)
        assertEquals(false, t.touched)
        // Backspace too.
        assertTrue(tap(f, t, 6))
        t.backspace()
        assertEquals("xain hào bạn ", f.text)
    }

    /** Only a whole word opens: not "don't" from inside, not part of a long run of letters. */
    @Test
    fun aTapInsideOpensOnlyAWholeWord() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("don't e-mail ")
        assertEquals(false, tap(f, t, 2))
        assertEquals(false, tap(f, t, 8))
        assertEquals("don't e-mail ", f.text)
        assertEquals("", t.shown)
        val g = FakeEditor("x " + "b".repeat(Typist.TOUCH_READ + 5))
        val u = typist(g)
        assertEquals(false, tap(g, u, 10))
        assertEquals(null, g.marked)
    }

    /** Leaving a word the cursor was inside keeps it as it is: the cursor moving on, or the field. */
    @Test
    fun aWordTheCursorWasInsideStaysAsItIs() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("toi khong biet")
        assertTrue(tap(f, t, 6))
        assertEquals("khong", t.shown)
        t.finish()
        assertEquals("toi khong biet", f.text)
        assertEquals("toi kh", f.committed.toString())
        assertEquals("", t.shown)
    }

    /**
     * Only a whole word opens again, and only as it was: not the end of "don't" or "e-mail", not
     * a word the cursor is inside, not one its keys would make differently ("Đ" + "ượ" ... is
     * fine, an emoji isn't a word).
     */
    @Test
    fun onlyAWholeWordOpensAgain() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("don't ")
        t.backspace()
        assertEquals("don't", f.text)
        assertEquals("", f.composing)
        t.text(" e-mail ")
        t.backspace()
        assertEquals("", f.composing)
        t.text(" 2nd ")
        t.backspace()
        assertEquals("", f.composing)
        f.after = "abc"
        t.text(" xin ")
        t.backspace()
        assertEquals("", f.composing)  // the cursor is inside a word
        f.after = ""
        t.text(" Đượcj ")
        t.backspace()
        assertEquals("", f.composing)  // its keys make "Được" plus a "j": not the same word
    }

    /**
     * A swipe left from Backspace takes the word before the cursor with the spaces after it, one
     * word a swipe, as on the reference: "xin chao ban" leaves "xin chao ", again "xin ".
     */
    @Test
    fun deleteWordTakesTheWordBeforeTheCursor() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("xin chao ban")          // "ban" still in progress
        t.deleteWord()
        assertEquals("xin chao ", f.text)
        assertEquals("", t.shown)
        t.deleteWord()
        assertEquals("xin ", f.text)
        assertEquals(listOf("<s>", "xin"), t.context())
        t.text("chaof ")                 // the typist's copy of the field is still right
        assertEquals("xin chào ", f.text)
        assertEquals(listOf("<s>", "xin", "chào"), t.context())
        t.deleteWord(); t.deleteWord()
        assertEquals("", f.text)
        assertEquals(listOf("<s>"), t.context())
        t.deleteWord()                   // nothing left: nothing happens
        assertEquals("", f.text)
    }

    @Test
    fun deleteWordTakesPunctuationAndEmojiAsAWordAndANewLineAlone() {
        val f = FakeEditor()
        val t = typist(f)
        t.text("ok!! ")
        t.deleteWord()
        assertEquals("ok", f.text)
        t.insert(" 👍🏽❤️")
        t.deleteWord()
        assertEquals("ok ", f.text)
        t.text("vieetj")
        t.enter()
        t.deleteWord()
        assertEquals("ok việt", f.text)
        t.deleteWord()
        assertEquals("ok ", f.text)
        assertEquals(listOf("<s>", "ok"), t.context())
    }

    @Test
    fun aWordsLengthBeforeTheCursor() {
        fun n(s: String) = Typist.wordBefore(s)
        assertEquals(3, n("xin chao ban"))
        assertEquals(4, n("xin chao ban "))
        assertEquals(6, n("được  "))
        assertEquals(2, n("ok!!"))
        assertEquals(1, n("a\n"))
        assertEquals(2, n("a\n  "))   // the spaces after a new line, not the line
        assertEquals("👍🏽❤️".length, n("hi 👍🏽❤️"))
        assertEquals("tiếng".length, n("tiếng"))
        assertEquals(3, n("   "))
        assertEquals(0, n(""))
    }
}
