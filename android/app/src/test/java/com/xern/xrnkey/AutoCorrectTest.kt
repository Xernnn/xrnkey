package com.xern.xrnkey

import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Space and punctuation restoring words typed without marks, with look-back and Revert
 * (android/docs/feature-spec.md, "Auto-correct"), against the small test models.
 */
class AutoCorrectTest {
    private fun <T> typing(field: String = "", correction: Int = Models.CORRECT_FLEXIBLE,
                           tones: Int = Typist.TONES_ALWAYS,
                           body: (Typist, FakeEditor, Models) -> T): T =
        TestModels.test().use { m ->
            m.models.correction = correction
            TypingState().use { s ->
                s.useModels(m.models)
                val f = FakeEditor(field)
                val t = Typist(s, f, m.models).also { it.tonesOnSpace = tones; it.reset() }
                body(t, f, m.models)
            }
        }

    /**
     * A form picked from a held key is the typist's choice: its word ends as shown, though its
     * keys spell a common English word ("ò" goes in as "of", "í" as "is", "á" as "as"). The
     * same keys typed stay English, and the word after it ends by the setting again ("cups",
     * not "cúp").
     */
    @Test
    fun aHeldFormIsKeptThoughItsKeysSpellAnEnglishWord() = typing(tones = Typist.TONES_OFF) { t, f, _ ->
        t.text("ò"); t.text(" ")
        t.text("í"); t.text(",")
        t.text(" of ")
        assertEquals("ò í, of ", f.text)
        t.text("á")
        t.finish()
        t.text(" cups ")
        assertEquals("ò í, of á cups ", f.text)
    }

    /**
     * A word that opens again ends as it did: "of" (English, kept) would come back as "ò" through
     * its keys, so it stays as it is; a picked "ò" opens as "ò" and stays "ò".
     */
    @Test
    fun aWordOpensAgainOnlyAsItWas() = typing(tones = Typist.TONES_OFF) { t, f, _ ->
        t.text("of ")
        t.backspace()
        assertEquals("of", f.text)
        assertEquals("", f.composing)
        t.text(" ò"); t.text(" ")
        t.backspace()
        assertEquals("ò", f.composing)
        t.text(" ")
        assertEquals("of ò ", f.text)
    }

    /** With "Non-Vietnamese as typed" off, a held form's word leaves it off for the next. */
    @Test
    fun aHeldFormLeavesTheForeignWordsSettingAsItWas() = typing(tones = Typist.TONES_OFF) { t, f, _ ->
        t.keepForeign = false
        t.text("cups ")
        t.text("ó"); t.text(" ")
        t.text("cups ")
        assertEquals("cúp ó cúp ", f.text)
    }

    /**
     * Chat words and laughs stay as typed on Space: with the phone's models the wrong-word fix
     * made "hehe" "here" and "oke" "one". A slip is still fixed.
     */
    @Test
    fun chatWordsAndLaughsStayAsTyped() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                val f = FakeEditor()
                val t = Typist(s, f, m.models).also { it.reset() }
                t.text("hehe oke hahaha hjhj teh ")
                assertEquals("hehe oke hahaha hjhj the ", f.text)
            }
        }
    }

    @Test
    fun spaceRestoresAWordTypedWithoutMarks() = typing { t, f, _ ->
        t.text("khong ")
        assertEquals("không ", f.text)
        t.text("Toi khong biet ")
        assertEquals("không Tôi không biết ", f.text)
    }

    @Test
    fun backspaceRightAfterPutsBackWhatWasTyped() = typing { t, f, _ ->
        t.text("khong ")
        t.backspace()
        // The restoration and the space go, and the word is open again as typed (Laban Key's
        // "teh␣⌫" is "teh"); it used to come back as "khong " with its space.
        assertEquals("khong", f.text)
        assertEquals("khong", f.composing)
        t.text(" ")
        assertEquals("khong ", f.text)  // the next Space keeps it as typed
        t.backspace()
        assertEquals("khong", f.text)  // then Backspace opens it again, still kept as typed
        assertEquals("khong", f.composing)
        t.text(" ")
        assertEquals("khong ", f.text)
    }

    /**
     * The part of a word before the cursor, opened by a tap there, ends as typed: Space splits
     * the word and restores nothing, since the rest of it is after the cursor. At the word's end,
     * Space restores it as usual.
     */
    @Test
    fun aWordOpenedInsideEndsAsTyped() = typing(field = "khong") { t, f, _ ->
        f.after = "x"
        t.reset()
        assertTrue(t.reopenBefore(f.textAfter(1)))
        t.text(" ")
        assertEquals("khong x", f.text)
        f.after = ""
        t.backspace()
        assertEquals("khong", f.composing)
        t.text(" ")
        assertEquals("không ", f.text)
    }

    @Test
    fun backspaceAfterPunctuationKeepsThePunctuation() = typing { t, f, _ ->
        t.text("khong,")
        assertEquals("không,", f.text)
        t.backspace()
        assertEquals("khong,", f.text)
    }

    @Test
    fun aLaterWordFixesAnEarlierOneAndBackspaceUndoesThat() = typing { t, f, _ ->
        t.text("co ")
        assertEquals("có ", f.text)
        t.text("gang ")
        assertEquals("cố gắng ", f.text)
        t.backspace()
        assertEquals("có gang", f.text)  // as it was before that Space, the word open again
        assertEquals("gang", f.composing)
    }

    @Test
    fun lookBackLeavesAloneTextThisKeyboardDidNotWrite() = typing { t, f, _ ->
        t.text("co ")
        f.committed.insert(0, "x")  // the app changed the field meanwhile ...
        f.committed.setLength(f.committed.length - 1)
        f.committed.append("! ")  // ... "có " is now "xcó! "
        t.text("gang ")
        assertEquals("xcó! gắng ", f.text)
    }

    @Test
    fun theSentenceEndPicksTheReadingThatEndsIt() {
        typing("Bạn ") { t, f, _ ->
            t.text("kha nhe ")
            assertEquals("Bạn khá nhẹ ", f.text)
        }
        typing("Bạn ") { t, f, _ ->
            t.text("kha nhe.")
            assertEquals("Bạn khá nhé.", f.text)
        }
        typing("Bạn ") { t, f, _ ->
            t.text("kha nhe")
            t.enter()
            assertEquals("Bạn khá nhé\n", f.text)
        }
    }

    @Test
    fun wordsTypedWithMarksAndEnglishStayAsTheCoreGivesThem() = typing { t, f, _ ->
        t.text("dduowcj ")
        assertEquals("được ", f.text)
        t.text("we can chat ")
        assertEquals("được we can chat ", f.text)
    }

    @Test
    fun aSlipToANeighbouringKeyIsFixed() = typing("Tôi ") { t, f, _ ->
        t.text("khonh ")
        assertEquals("Tôi không ", f.text)
    }

    @Test
    fun twoSpacesAfterAWordGiveAFullStop() = typing { t, f, _ ->
        t.text("khong  ")
        assertEquals("không. ", f.text)
        t.text("  ")
        assertEquals("không.   ", f.text)  // only right after a word
    }

    @Test
    fun anAddressFieldGetsNoAutoCorrect() = typing { t, f, _ ->
        t.autoCorrect = false
        t.text("khong  ")
        assertEquals("khong  ", f.text)
    }

    @Test
    fun theStripCentreIsWhatSpaceCommits() = typing { t, f, m ->
        val strip = StripMaker(m)
        t.text("co ")
        t.text("gang")
        val shown = strip.make(t) as Strip.Candidates
        assertEquals("gắng", shown.centre)
        t.text(" ")
        assertEquals("cố gắng ", f.text)
    }
}
