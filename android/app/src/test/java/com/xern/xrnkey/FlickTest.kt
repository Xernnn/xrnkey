package com.xern.xrnkey

import com.xern.xrnkey.ja.Kana
import com.xern.xrnkey.keyboard.Flick
import com.xern.xrnkey.keyboard.KeyKind
import com.xern.xrnkey.keyboard.Layouts
import com.xern.xrnkey.keyboard.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Japanese 12-key pad as data: its keys, where they are, and what each flick types. */
class FlickTest {
    private val rows = Layouts.rows(Page.KANA)

    private fun name(k: com.xern.xrnkey.keyboard.Key) = when (k.kind) {
        KeyKind.KANA, KeyKind.KANA_MARK, KeyKind.SYMBOLS -> k.label
        else -> k.kind.name
    }

    /**
     * Gboard's 12-key pad: あ か さ / た な は / ま や ら / ゛゜小 わ 、。?! in the middle, ↺ ◀ ?123 🌐
     * down the left, ⌫ ▶ space Enter down the right; the kana keys wider than the side columns.
     */
    @Test
    fun theKanaAreAThreeByFourGridBetweenTwoColumns() {
        assertEquals(
            listOf(
                listOf("UNDO", "あ", "か", "さ", "BACKSPACE"),
                listOf("LEFT", "た", "な", "は", "RIGHT"),
                listOf("?123", "ま", "や", "ら", "SPACE"),
                listOf("GLOBE", "゛゜小", "わ", "、。?!", "ENTER"),
            ),
            rows.map { r -> r.keys.map(::name) },
        )
        for (r in rows) {
            assertEquals(10f, r.width, 0.001f)
            assertEquals(listOf(1.7f, 2.2f, 2.2f, 2.2f, 1.7f), r.keys.map { it.width })
        }
        // No number row, whatever the setting: the pad shares the height out over its four rows.
        assertEquals(rows, Layouts.rows(Page.KANA, numberRow = false))
        // The symbols lead back to it as あいう.
        assertEquals("あいう", Layouts.rows(Page.SYMBOLS, home = Page.KANA).last().keys.first().label)
        assertEquals("あいう", Layouts.rows(Page.NUMPAD, home = Page.KANA).last().keys.first().label)
        assertEquals("ABC", Layouts.rows(Page.SYMBOLS).last().keys.first().label)
    }

    private fun key(label: String) = Flick.keys.single { it.label == label }

    /** Each key's tap, then its flicks left, up, right and down ("" where it has none). */
    @Test
    fun eachKeyFlicksToItsColumn() {
        val want = mapOf(
            "あ" to "あいうえお", "か" to "かきくけこ", "さ" to "さしすせそ", "た" to "たちつてと",
            "な" to "なにぬねの", "は" to "はひふへほ", "ま" to "まみむめも", "ら" to "らりるれろ",
            "や" to "や（ゆ）よ", "わ" to "わをんー ", "、。?!" to "、。？！ ",
        )
        assertEquals(want.keys, Flick.keys.map { it.label }.toSet())
        for ((label, kana) in want) {
            val k = key(label)
            val got = (Flick.TAP..Flick.DOWN).joinToString("") { k.flick(it).ifEmpty { " " } }
            assertEquals(label, kana, got)
        }
    }

    /**
     * A tap's kana, then each of its flicks, then the small ones: what a key's taps go through
     * in turn. ↺ finds a kana's toggle from the kana alone.
     */
    @Test
    fun eachKeyTogglesThroughItsKana() {
        assertEquals("あいうえおぁぃぅぇぉ", key("あ").toggle)
        assertEquals("かきくけこ", key("か").toggle)
        assertEquals("たちつてとっ", key("た").toggle)
        assertEquals("やゆよゃゅょ", key("や").toggle)
        assertEquals("わをんゎー", key("わ").toggle)
        assertEquals("、。？！", key("、。?!").toggle)
        assertEquals("たちつてとっ", Flick.toggleOf('っ'))
        assertEquals("まみむめも", Flick.toggleOf('む'))
        assertEquals("、。？！", Flick.toggleOf('！'))
        assertNull(Flick.toggleOf('が'))
        // Every kana a key types is on exactly one toggle.
        val all = Flick.keys.flatMap { it.toggle.toList() }
        assertEquals(all.size, all.toSet().size)
    }

    /** Within 24 dp a finger is a tap; past it, the way it went furthest. */
    @Test
    fun aSmallWobbleIsATapAndALongerSlideAFlick() {
        val t = Flick.THRESHOLD_DP * 3  // at xxhdpi
        assertEquals(Flick.TAP, Flick.direction(0f, 0f, t))
        assertEquals(Flick.TAP, Flick.direction(40f, -40f, t))
        assertEquals(Flick.TAP, Flick.direction(-71f, 0f, t))
        assertEquals(Flick.LEFT, Flick.direction(-73f, 10f, t))
        assertEquals(Flick.UP, Flick.direction(30f, -80f, t))
        assertEquals(Flick.RIGHT, Flick.direction(90f, 60f, t))
        assertEquals(Flick.DOWN, Flick.direction(-50f, 75f, t))
    }

    /** ゛゜小: か ⇄ が, は → ば → ぱ, つ → っ → づ, あ ⇄ ぁ, や ⇄ ゃ; ん has none. */
    @Test
    fun theMarkKeyCyclesEachKanasForms() {
        fun cycle(start: Char): String {
            val out = StringBuilder().append(start)
            var c = Kana.nextForm(start)
            while (c != null && c != start && out.length < 5) {
                out.append(c)
                c = Kana.nextForm(c)
            }
            return out.toString()
        }
        assertEquals("かが", cycle('か'))
        assertEquals("がか", cycle('が'))
        assertEquals("はばぱ", cycle('は'))
        assertEquals("つっづ", cycle('つ'))
        assertEquals("うぅゔ", cycle('う'))
        assertEquals("あぁ", cycle('あ'))
        assertEquals("やゃ", cycle('や'))
        assertEquals("よょ", cycle('よ'))
        assertEquals("わゎ", cycle('わ'))
        assertEquals("そぞ", cycle('そ'))
        assertEquals("ほぼぽ", cycle('ほ'))
        assertNull(Kana.nextForm('ん'))
        assertNull(Kana.nextForm('な'))
        // Every kana of the か, さ, た and は rows reaches its voiced form (the next code point).
        for (c in "かきくけこさしすせそたちつてとはひふへほ") {
            val forms = generateSequence(Kana.nextForm(c)) { Kana.nextForm(it) }.take(3).toList()
            assertTrue(c.toString(), c + 1 in forms)
        }
    }
}
