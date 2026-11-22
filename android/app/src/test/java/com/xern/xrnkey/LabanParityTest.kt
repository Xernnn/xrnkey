package com.xern.xrnkey

import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Laban Key's behaviour, measured on the owner's phone (Laban 26.09.01, set up as the owner
 * uses it: auto-correct Flexible, non-Vietnamese words without marks, full Telex), each entry
 * typed with real key taps and a space: what xrnkey does by default. Laban never adds tones on
 * Space; the accented reading waits in the strip.
 */
class LabanParityTest {
    private fun typed(text: String, opened: TestModels.Opened, setup: Typist.() -> Unit = {}): String {
        TypingState().use { s ->
            s.useModels(opened.models)
            val f = FakeEditor()
            val t = Typist(s, f, opened.models).also { it.setup(); it.reset() }
            t.text("$text ")
            return f.text
        }
    }

    /** The rows that come out the same with any model: kept as typed, or Telex's own. */
    private val asTyped = listOf(
        "ong", "ban", "chao", "toi", "ma", "co", "nha", "khong", "duoc", "nguoi", "viet", "nhieu",
        "truong", "thuong", "chuyen", "cung", "hello", "meeting", "ok", "file", "free", "good",
        "love", "email", "thanks", "deadline", "google", "toi khong biet", "cam on ban",
        "hom nay troi dep lam", "meeting luc ba gio", "gui file cho anh", "ok nhe", "con ong",
    )

    @Test
    fun spaceKeepsWhatWasTyped() = TestModels.test().use { m ->
        for (w in asTyped) assertEquals(w, "$w ", typed(w, m))
        // Telex marks typed by the owner are applied, as in Laban.
        assertEquals("tiếng việt ", typed("tieengs vieejt", m))
        // A word wrong in both languages, with one clear fix.
        assertEquals("the ", typed("teh", m))
        assertEquals("The ", typed("Teh", m))
    }

    @Test
    fun theWholeTableWithTheRealModels() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            for (w in asTyped) assertEquals(w, "$w ", typed(w, m))
            assertEquals("tiếng việt ", typed("tieengs vieejt", m))
            assertEquals("the ", typed("teh", m))
            // Laban keeps "helo" too: help, held and hello are all one edit away.
            assertEquals("helo ", typed("helo", m))
            // Laban's Telex turns these into "tét", "cofee", "sổ", "sê" and "sory". xrnkey keeps
            // the English words it knows as they were typed: deliberately better ("test" was
            // "tét" on 0.3.1 too, from restoring every word).
            assertEquals("coffee ", typed("coffee", m))
            assertEquals("door ", typed("door", m))
            assertEquals("see ", typed("see", m))
            assertEquals("sorry ", typed("sorry", m))
            assertEquals("test ", typed("test", m))
        }
    }

    @Test
    fun onlyWhenNeededTonesTheWordsThatArentWordsAsTheyStand() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            val needed: Typist.() -> Unit = { tonesOnSpace = Typist.TONES_WHEN_NEEDED }
            for (w in listOf("ong", "ban", "chao", "ma", "co", "nha", "cung", "toi", "meeting", "file", "ok")) {
                assertEquals(w, "$w ", typed(w, m, needed))
            }
            assertEquals("không ", typed("khong", m, needed))
            assertEquals("được ", typed("duoc", m, needed))
            assertEquals("người ", typed("nguoi", m, needed))
            assertEquals("việt ", typed("viet", m, needed))
            // No look-back: an earlier word stays as it was committed.
            assertEquals("con ong ", typed("con ong", m, needed))
        }
    }

    @Test
    fun alwaysIsTheOldBehaviour() = TestModels.test().use { m ->
        assertEquals("tôi không biết ", typed("toi khong biet", m) { tonesOnSpace = Typist.TONES_ALWAYS })
    }

    @Test
    fun theMiddleSlotIsBoldOnlyWhenSpaceCommitsIt() = TestModels.test().use { m ->
        TypingState().use { s ->
            s.useModels(m.models)
            val t = Typist(s, FakeEditor(), m.models).also { it.reset() }
            val strip = StripMaker(m.models)
            t.text("khong")
            val off = strip.make(t) as Strip.Candidates
            // The reading, for a tap: alone, or with the word that nearly always follows it at a
            // sentence's start in the test text ("Không được").
            assertTrue(off.centre == "không" || off.centre!!.startsWith("không "))
            assertFalse(off.bold)  // Space keeps "khong"
            t.tonesOnSpace = Typist.TONES_ALWAYS
            val always = strip.make(t) as Strip.Candidates
            assertEquals("không", always.centre)
            assertTrue(always.bold)
        }
    }

    @Test
    fun thePhraseSlotAccentsTheWholeRunAndBackspaceUndoesIt() = TestModels.test().use { m ->
        TypingState().use { s ->
            s.useModels(m.models)
            val f = FakeEditor()
            val t = Typist(s, f, m.models).also { it.reset() }
            val strip = StripMaker(m.models)
            t.text("toi khong biet")
            val shown = strip.make(t) as Strip.Candidates
            assertEquals("tôi không biết", shown.left)
            val pick = strip.pick(0)!!
            t.pickPhrase(pick.phrase!!)
            assertEquals("tôi không biết ", f.text)
            t.backspace()
            assertEquals("toi khong biet ", f.text)
            // One word is not a run: no phrase.
            val g = FakeEditor()
            val u = Typist(s, g, m.models).also { it.reset() }
            u.text("khong")
            assertEquals(null, u.phrase())
        }
    }
}
