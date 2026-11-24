package com.xern.xrnkey

import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.engine.PersonalWords
import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Touch
import com.xern.xrnkey.ime.TouchSpots
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Space's typo fixes that know where each finger landed (android/docs/feature-spec.md, "Typos"),
 * with Laban Key's settings: tones on Space off, typo fixes Flexible. The keys are the phone's,
 * 1.4 key widths tall.
 */
class TouchTypoTest {
    private fun <T> typing(models: TestModels.Opened? = null, body: (Typist, FakeEditor, Models) -> T): T {
        val m = models ?: TestModels.test()
        try {
            return TypingState().use { s ->
                s.useModels(m.models)
                val f = FakeEditor()
                val t = Typist(s, f, m.models).also {
                    it.tonesOnSpace = Typist.TONES_OFF
                    it.correctWrong = Models.CORRECT_FLEXIBLE
                    it.reset()
                }
                body(t, f, m.models)
            }
        } finally {
            if (models == null) m.close()
        }
    }

    /** Taps `keys`, each at its key's centre unless `at` says where (by index). */
    private fun Typist.tap(keys: String, at: Map<Int, Pair<Float, Float>> = emptyMap()) {
        keys.forEachIndexed { i, c ->
            val (dx, dy) = at[i] ?: (0f to 0f)
            text(c.toString(), Touch(dx, dy, HEIGHT))
        }
    }

    @Test
    fun spaceFixesAWordThatIsNoWordTowardVietnamese() = typing { t, f, _ ->
        t.text("tooi ")
        t.tap("khpng")  // o -> p
        t.text(" ")
        assertEquals("tôi không ", f.text)
    }

    /** j for h: by j's left edge it was h; by its right edge, toward k, it was nothing sure. */
    @Test
    fun whereTheTapLandedDecides() {
        typing { t, f, _ ->
            t.text("tooi ")
            t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
            t.text(" ")
            assertEquals("tôi không ", f.text)
        }
        typing { t, f, _ ->
            t.text("tooi ")
            t.tap("kjong", mapOf(1 to (0.45f to 0f)))
            t.text(" ")
            assertEquals("tôi kjong ", f.text)
        }
    }

    /** Marks typed keep their meaning: the keys a slip away go through Telex again. */
    @Test
    fun aWordTypedWithMarksIsFixedThroughTelex() = typing { t, f, _ ->
        t.text("tooi ")
        t.tap("kjoong", mapOf(1 to (-0.45f to 0f)))
        t.text(" ")
        assertEquals("tôi không ", f.text)
    }

    /** What Space commits is in the strip's middle, in bold. */
    @Test
    fun theFixIsTheStripsMiddleInBold() = typing { t, _, m ->
        t.text("tooi ")
        t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
        assertEquals("không", t.preview())
        val strip = StripMaker(m).make(t) as Strip.Candidates
        assertEquals("không", strip.centre)
        assertTrue(strip.bold)
    }

    /**
     * Backspace right after takes the fix back with the space, the word open as typed with its
     * taps; the next Space keeps it.
     */
    @Test
    fun backspaceTakesTheFixBack() = typing { t, f, _ ->
        t.text("tooi ")
        t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
        t.text(" ")
        t.backspace()
        assertEquals("tôi ", f.committed.toString())
        assertEquals("kjong", f.composing)
        assertEquals(-0.45f, t.touchesNow()[1]?.dx)
        t.text(" ")
        assertEquals("tôi kjong ", f.text)
    }

    /** The taps follow the keys: Backspace takes a key's tap with it, the others stay. */
    @Test
    fun backspaceKeepsTheTapsOfTheKeysLeft() = typing { t, f, _ ->
        t.tap("kjongx", mapOf(1 to (-0.45f to 0f), 5 to (0.3f to 0.1f)))
        t.backspace()
        assertEquals("kjong", f.composing)
        val taps = t.touchesNow()
        assertEquals(5, taps.size)
        assertEquals(-0.45f, taps[1]?.dx)
        assertEquals(0f, taps[4]?.dx)
        // A tone key's letter taken back takes its keys: "vieetj" BackSpace keeps v, i's taps.
        t.text(" ")
        t.tap("vieetj", mapOf(0 to (0.2f to 0f), 1 to (0.1f to 0f)))
        t.backspace()
        assertEquals("việ", f.composing)
        val left = t.touchesNow()
        assertEquals(t.keys().length, left.size)
        assertEquals(0.2f, left[0]?.dx)
        assertEquals(0.1f, left[1]?.dx)
    }

    /** No touches (text typed by a script, the laptop): the keys' centres, the layout alone. */
    @Test
    fun withoutTouchesTheLayoutDecides() = typing { t, f, _ ->
        t.text("tooi khpng ")
        assertEquals("tôi không ", f.text)
    }

    /** Words, chat, a held form, and the fixes turned off: nothing changes. */
    @Test
    fun wordsChatAndChoicesStay() {
        typing { t, f, _ ->
            t.text("tooi khoong ")
            t.text("khong ")
            t.text("oke ")
            t.text("thx ")
            assertEquals("tôi không khong oke thx ", f.text)
        }
        // A form picked from a held key: its word ends as it would have, never fixed.
        typing { t, f, _ ->
            t.text("tooi ")
            t.tap("kj", mapOf(1 to (-0.45f to 0f)))
            t.text("ô")
            t.tap("ng")
            assertNull(t.typoFix())
            t.text(" ")
            assertEquals("tôi kjoong ", f.text)
        }
        typing { t, f, _ ->
            t.correctWrong = 0
            t.text("tooi ")
            t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
            t.text(" ")
            assertEquals("tôi kjong ", f.text)
        }
    }

    /** Enter ends the word as Space would, fixed. */
    @Test
    fun enterFixesToo() = typing { t, f, _ ->
        t.text("tooi ")
        t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
        t.enter()
        assertEquals("tôi không\n", f.text)
    }

    /** The words that end as typed teach where the owner's taps land, only where words are learned. */
    @Test
    fun wordsEndedAsTypedTeachWhereTheTapsLand() {
        PersonalWords().use { words ->
            typing { t, _, _ ->
                val spots = TouchSpots()
                t.personal = words
                t.touchSpots = spots
                t.tap("khoong", (0..5).associateWith { 0.2f to -0.1f })
                t.text(" ")
                assertEquals(1, spots.count('k'))
                assertEquals(2, spots.count('o'))
                // A fixed word teaches nothing: its taps missed.
                t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
                t.text(" ")
                assertEquals(1, spots.count('k'))
                assertEquals(0, spots.count('j'))
                // Nor an incognito field, a password field, or with learning off.
                t.learnHere = false
                t.tap("khoong")
                t.text(" ")
                assertEquals(1, spots.count('k'))
                t.learnHere = true
                t.personal = null
                t.tap("khoong")
                t.text(" ")
                assertEquals(1, spots.count('k'))
                t.personal = words
                t.raw = true
                t.tap("khoong")
                t.text(" ")
                assertEquals(1, spots.count('k'))
            }
        }
    }

    /**
     * The owner's spots move the keys' centres the fixes measure from: a j tapped a little right
     * of its centre is no h, unless the owner's taps on h land on its right side too.
     */
    @Test
    fun theOwnersSpotsMoveTheKeys() {
        typing { t, f, _ ->
            t.text("tooi ")
            t.tap("kjong", mapOf(1 to (0.2f to 0f)))
            t.text(" ")
            assertEquals("tôi kjong ", f.text)
        }
        typing { t, f, _ ->
            val spots = TouchSpots()
            repeat(200) { spots.learn("h", listOf(Touch(0.33f, 0f, HEIGHT))) }
            t.touchSpots = spots
            t.text("tooi ")
            t.tap("kjong", mapOf(1 to (0.2f to 0f)))
            t.text(" ")
            assertEquals("tôi không ", f.text)
        }
    }

    @Test
    fun touchSpotsAreBoundedAndKept() {
        val spots = TouchSpots()
        repeat(5) { spots.learn("a", listOf(Touch(0.2f, 0.1f, HEIGHT))) }
        assertEquals(0f, spots.sweetX()[0])  // too few taps to move
        repeat(500) { spots.learn("a", listOf(Touch(0.2f, 0.1f, HEIGHT))) }
        assertEquals(TouchSpots.CAP, spots.count('a'))
        assertEquals(0.2f * 200 / 220, spots.sweetX()[0], 1e-4f)
        // A far-off tap (a slide onto the key) teaches nothing; nor a key with no tap.
        spots.learn("bc", listOf(Touch(0.9f, 0f, HEIGHT), null))
        assertEquals(0, spots.count('b'))
        assertEquals(0, spots.count('c'))
        val file = File.createTempFile("spots", ".tsv")
        try {
            assertTrue(spots.save(file))
            val again = TouchSpots().also { it.load(file) }
            assertEquals(TouchSpots.CAP, again.count('a'))
            assertEquals(spots.sweetX()[0], again.sweetX()[0], 1e-6f)
            assertEquals(spots.sweetY()[0], again.sweetY()[0], 1e-6f)
            file.writeText("a\tnan\t0\t3\nzz\t1\t1\t1\nq\t0.1\t0.1\t-2\n")
            again.load(file)
            assertEquals(0, again.count('a'))
            assertEquals(0, again.count('q'))
        } finally {
            file.delete()
        }
    }

    /** With the real models: the owner's kind of slips, Vietnamese and English. */
    @Test
    fun realModelsFixTheOwnersSlips() {
        assumeTrue(TestModels.realAvailable)
        TestModels.real().use { m ->
            fun spaced(before: String, keys: String, at: Map<Int, Pair<Float, Float>> = emptyMap()): String =
                typing(m) { t, f, _ ->
                    t.text("$before ")
                    t.tap(keys, at)
                    t.text(" ")
                    f.text.removePrefix("$before ").trimEnd()
                }
            assertEquals("không", spaced("tôi", "khpng"))
            assertEquals("chào", spaced("xin", "cjao", mapOf(1 to (-0.4f to 0f))))
            assertEquals("chào", spaced("xin", "cjaof", mapOf(1 to (-0.4f to 0f))))
            assertEquals("chào", spaced("em", "chapf", mapOf(3 to (-0.45f to 0f))))
            assertEquals("đẹp", spaced("cô ấy", "ddeeoj", mapOf(4 to (0.45f to 0f))))
            assertEquals("người", spaced("mọi", "nhuowif", mapOf(1 to (-0.4f to 0.1f))))
            assertEquals("biết", spaced("tôi không", "bieetd", mapOf(5 to (-0.45f to 0f))))
            assertEquals("nay", spaced("hôm", "nsy", mapOf(1 to (-0.45f to 0f))))
            assertEquals("meeting", spaced("the", "meetimg", mapOf(5 to (-0.4f to 0f))))
            // Words stay, whatever slip made them.
            assertEquals("khong", spaced("tôi", "khong"))
            assertEquals("bàn", spaced("cái", "banf"))
            assertEquals("helo", spaced("say", "helo"))
            assertEquals("oke", spaced("ok", "oke"))
            // Chat spelt with its marks on purpose ("dzậy" used to become "dậy", "ừa" "vừa").
            assertEquals("dzậy", spaced("tôi", "dzaayj"))
            assertEquals("ừa", spaced("bạn", "uwaf"))
            assertEquals("ùi", spaced("xong", "uif"))
        }
    }

    /** Space's fix stays well under a few milliseconds, long Telex words with two slips too. */
    @Test
    fun theFixIsQuick() = TestModels.best().use { m ->
        typing(m) { t, _, _ ->
            val words = listOf("dduqkqnyf", "dduwownyf", "nguqlwkf", "chuyrwnk", "khpng", "cjaof", "meetimg", "tiesng")
            repeat(3) { for (w in words) { t.tap(w); t.typoFix(); t.text(" ") } }  // warmed up
            val times = ArrayList<Long>()
            repeat(5) {
                for (w in words) {
                    t.tap(w, w.indices.associateWith { (if (it % 2 == 0) 0.3f else -0.3f) to 0.1f })
                    val start = System.nanoTime()
                    t.typoFix()
                    times += System.nanoTime() - start
                    t.text(" ")
                }
            }
            times.sort()
            val worst = times.last() / 1e6
            val median = times[times.size / 2] / 1e6
            println("typo fix: median %.2f ms, worst %.2f ms".format(median, worst))
            assertTrue("worst $worst ms", worst < 10.0)
            assertTrue("median $median ms", median < 3.0)
        }
    }

    @Test
    fun aWordBackspaceOpenedIsKeptAsTyped() = typing { t, f, _ ->
        // "kjong" fixed, taken back, then typed on: the word changed, so Space may fix it again.
        t.text("tooi ")
        t.tap("kjong", mapOf(1 to (-0.45f to 0f)))
        t.text(" ")
        t.backspace()
        assertNull(t.typoFix())
        t.backspace()
        t.tap("g")
        assertEquals("không", t.typoFix())
        t.text(" ")
        assertEquals("tôi không ", f.text)
    }

    private companion object {
        const val HEIGHT = 1.4f
    }
}
