package com.xern.xrnkey

import com.xern.xrnkey.ime.KanaTypist
import com.xern.xrnkey.ja.Converter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Japanese typist fed by the 12-key pad, with the made-up test dictionary: flicked kana go
 * into the reading romaji would spell, and convert, commit and delete as QWERTY's do.
 */
class KanaPadTypistTest {
    private val editor = FakeEditor()
    private val typist = KanaTypist(editor, Converter(TestModels.japanese())).also { it.reset() }

    /** The 12-key pad's toggles: what each tap of a key in turn types. */
    private val toggles = mapOf("あ" to "あいうえおぁぃぅぇぉ", "た" to "たちつてとっ", "ま" to "まみむめも", "、。?!" to "、。？！")

    private fun toggle(key: String) = toggles.getValue(key)

    /** わ (tap), た (tap), し (さ flicked left): わたし, and Space converts it to 私. */
    @Test
    fun flickedKanaComposeAndConvert() {
        typist.flick("わ")
        assertEquals("わ", editor.composing)
        assertEquals("", editor.committed.toString())
        typist.flick("た")
        typist.flick("し")
        assertEquals("わたし", editor.composing)
        assertEquals("わたし", typist.reading)
        typist.text(" ")
        assertEquals("私", editor.composing)
        assertTrue(typist.converting)
        typist.enter()
        assertEquals("私", editor.committed.toString())
        assertEquals("", editor.composing)
        // A longer one: わたしはがくせいです, with が and で from ゛.
        for (k in "わたしはか") typist.flick(k.toString())
        typist.mark()
        for (k in "くせいてす") typist.flick(k.toString())
        assertEquals("わたしはがくせいてす", editor.composing)
        typist.backspace()
        assertTrue(typist.mark())
        typist.flick("す")
        assertEquals("わたしはがくせいです", editor.composing)
        typist.text(" ")
        assertEquals("私は学生です", editor.composing)
    }

    /** After Space, a kana commits the conversion shown and starts a new reading, as a letter does. */
    @Test
    fun aKanaAfterSpaceCommitsTheConversion() {
        for (k in "かみ") typist.flick(k.toString())
        typist.text(" ")
        typist.flick("か")
        assertEquals("紙", editor.committed.toString())
        assertEquals("か", editor.composing)
        assertFalse(typist.converting)
    }

    /** A toggle step changes the last kana in place; ↺ steps it back; after お come the small ones. */
    @Test
    fun aToggleStepsTheLastKanaAndBackGoesBack() {
        val a = toggle("あ")
        typist.flick("か")
        typist.flick("あ")
        assertTrue(typist.step(a, 1))
        assertTrue(typist.step(a, 1))
        assertEquals("かう", editor.composing)
        assertTrue(typist.step(a, -1))
        assertEquals("かい", editor.composing)
        repeat(8) { typist.step(a, 1) }
        assertEquals("かぉ", editor.composing)
        typist.step(a, 1)
        assertEquals("かあ", editor.composing)
        typist.step(a, -1)
        assertEquals("かぉ", editor.composing)
        // Not on that key's toggle: nothing changes.
        assertFalse(typist.step(toggle("ま"), 1))
        assertEquals("かぉ", editor.composing)
        // ↺: the last kana's own toggle, back.
        typist.backspace()
        typist.flick("と")
        assertEquals('と', typist.lastKana)
        assertTrue(typist.step(toggle("た"), -1))
        assertEquals("かて", editor.composing)
        assertEquals("", editor.committed.toString())
    }

    /** With nothing composing there is no last kana to step or mark. */
    @Test
    fun nothingComposingHasNoLastKana() {
        assertNull(typist.lastKana)
        assertFalse(typist.step(toggle("あ"), 1))
        assertFalse(typist.mark())
        assertEquals("", editor.composing)
        assertEquals(emptyList<String>(), editor.log)
    }

    /** ゛゜小 goes round each kana's forms: は ば ぱ は, つ っ づ つ, や ゃ; ん has none. */
    @Test
    fun theMarkKeyChangesTheLastKana() {
        typist.flick("は")
        val seen = mutableListOf(editor.composing)
        repeat(3) {
            assertTrue(typist.mark())
            seen += editor.composing
        }
        assertEquals(listOf("は", "ば", "ぱ", "は"), seen)
        typist.flick("つ")
        typist.mark()
        assertEquals("はっ", editor.composing)
        typist.mark()
        assertEquals("はづ", editor.composing)
        typist.mark()
        assertEquals("はつ", editor.composing)
        typist.flick("や")
        typist.mark()
        assertEquals("はつゃ", editor.composing)
        typist.flick("ん")
        assertFalse(typist.mark())
        assertEquals("はつゃん", editor.composing)
    }

    /** ゛ after Space: back to the reading, changed, and Space converts it again. */
    @Test
    fun theMarkKeyAfterSpaceGoesBackToTheReading() {
        for (k in "かくせい") typist.flick(k.toString())
        typist.text(" ")
        assertTrue(typist.converting)
        assertTrue(typist.mark())
        assertFalse(typist.converting)
        assertEquals("かくせぃ", editor.composing)
        assertEquals("", editor.committed.toString())
    }

    /**
     * 、。？！ join the reading and convert with it, as QWERTY's 、 and 。 do; with nothing
     * composing they start one, so a toggle can step 、 to 。. A kana after a reading of
     * punctuation alone commits it first.
     */
    @Test
    fun punctuationJoinsTheReadingAndEndsASentence() {
        for (k in "かみ、") typist.flick(k.toString())
        assertEquals("かみ、", editor.composing)
        typist.text(" ")
        assertEquals("紙、", editor.composing)
        typist.enter()
        assertEquals("紙、", editor.committed.toString())
        typist.flick("、")
        assertEquals("、", editor.composing)
        assertTrue(typist.step(toggle("、。?!"), 1))
        assertEquals("。", editor.composing)
        typist.flick("あ")
        assertEquals("紙、。", editor.committed.toString())
        assertEquals("あ", editor.composing)
        typist.flick("め")
        typist.flick("！")
        typist.text(" ")
        assertEquals("雨！", editor.composing)
    }

    /** Backspace takes the last flicked kana; with none, what is before the cursor. */
    @Test
    fun backspaceTakesTheLastKana() {
        editor.commit("雨")
        typist.reset()
        typist.flick("か")
        typist.flick("み")
        typist.backspace()
        assertEquals("か", editor.composing)
        typist.backspace()
        assertEquals("", editor.composing)
        typist.backspace()
        assertEquals("", editor.committed.toString())
    }
}
