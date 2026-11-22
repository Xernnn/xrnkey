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
