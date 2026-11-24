package com.xern.xrnkey

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The Japanese 12-key pad under real touch events, in a window as on the phone. At xxhdpi
 * (dp = 3) and 1080 px wide: the strip is 144 px, then four rows of 225 px (the height of the
 * letters' five); the side columns are 183.6 px wide, the kana keys 237.6 px, so the columns'
 * centres are at 92, 302, 540, 778 and 988 px, and the rows' at 257, 482, 707 and 932 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlickTouchTest {
    private val events = mutableListOf<String>()
    private var t = 0L

    private val cols = listOf(92f, 302f, 540f, 778f, 988f)
    private val rows = listOf(257f, 482f, 707f, 932f)

    /** The pad, with the number row on (the letters' five rows' height) unless [setup] says. */
    private fun pad(palette: Palette = Palette.DARK, setup: KeyboardView.() -> Unit = {}): KeyboardView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = KeyboardView(activity)
        v.palette = palette
        v.home = Page.KANA
        v.page = Page.KANA
        v.language = Options.FLICK
        v.languages = Options.LANGUAGES
        v.kana = true
        v.setup()
        v.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { events += text }
            override fun onBackspace() { events += "⌫" }
            override fun onEnter() { events += "⏎" }
            override fun onKeyboardMenu() { events += "keyboards" }
            override fun onLanguage(code: String) { events += "language $code" }
            override fun onCursorStart() { events += "cursor" }
            override fun onCursor(dx: Int, dy: Int) { events += "move $dx $dy" }
            override fun onCursorEnd() { events += "end" }
            override fun onKana(text: String) { events += "kana $text" }
            override fun onKanaToggle(cycle: String) { events += "toggle $cycle" }
            override fun onKanaBack() { events += "back" }
            override fun onKanaMark() { events += "mark" }
            override fun onCandidate(slot: Int) { events += "slot $slot" }
        }
        activity.setContentView(v)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1080, v.width)
        return v
    }

    private fun touch(v: KeyboardView, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0, t, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    private fun pause(ms: Long) {
        t += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun tap(v: KeyboardView, col: Int, row: Int) {
        touch(v, MotionEvent.ACTION_DOWN, cols[col], rows[row])
        touch(v, MotionEvent.ACTION_UP, cols[col], rows[row])
    }

    /** A slide of [dx], [dy] px from a key's centre, lifted there. */
    private fun flick(v: KeyboardView, col: Int, row: Int, dx: Float, dy: Float) {
        touch(v, MotionEvent.ACTION_DOWN, cols[col], rows[row])
        touch(v, MotionEvent.ACTION_MOVE, cols[col] + dx / 2, rows[row] + dy / 2)
        touch(v, MotionEvent.ACTION_MOVE, cols[col] + dx, rows[row] + dy)
        touch(v, MotionEvent.ACTION_UP, cols[col] + dx, rows[row] + dy)
    }

    private fun shot(name: String, v: KeyboardView) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val colours = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) colours += bmp.getPixel(x, y)
        assertTrue("$name looks blank", colours.size > 8)
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val left = -100f to 0f
    private val up = 0f to -100f
    private val right = 100f to 0f
    private val down = 0f to 100f

    /**
     * A tap gives a key's あ-column kana, a slide left, up, right or down its い, う, え or お one;
     * や and わ have brackets, を ん ー, and 、。?! its punctuation where the columns have gaps.
     */
    @Test
    fun eachKeyTapsAndFlicksToItsKana() {
        val v = pad()
        assertEquals(1044, v.height)
        val keys = listOf(
            Triple(1, 0, "あいうえお"), Triple(2, 0, "かきくけこ"), Triple(3, 0, "さしすせそ"),
            Triple(1, 1, "たちつてと"), Triple(2, 1, "なにぬねの"), Triple(3, 1, "はひふへほ"),
            Triple(1, 2, "まみむめも"), Triple(2, 2, "や（ゆ）よ"), Triple(3, 2, "らりるれろ"),
            Triple(2, 3, "わをんー"), Triple(3, 3, "、。？！"),
        )
        for ((col, row, kana) in keys) {
            events.clear()
            tap(v, col, row)
            pause(1100)  // past the toggle: the next tap is a kana of its own
            for ((dx, dy) in listOf(left, up, right, down)) flick(v, col, row, dx, dy)
            // A flick where the key has nothing (わ and 、。?! down) types nothing.
            assertEquals(kana, kana.map { "kana $it" }, events)
        }
    }

    /**
     * Within 24 dp (72 px) of where it went down the finger is a tap, however it wobbles; past
     * it, a flick, the way it went furthest. A long slide onto another key stays the first key's.
     */
    @Test
    fun aWobbleIsATapAndALongSlideStaysOnItsKey() {
        val v = pad()
        flick(v, 2, 0, 50f, 45f)       // か, 67 px: a tap
        pause(1100)
        flick(v, 2, 0, -40f, -60f)     // か, 72 px up-left, mostly up: く
        flick(v, 2, 0, 0f, 225f)       // か slid down onto な: こ
        flick(v, 1, 1, -150f, 0f)      // た slid onto ◀: ち
        assertEquals(listOf("kana か", "kana く", "kana こ", "kana ち"), events)
    }

    /**
     * Tapped again within a second, a key steps along its toggle (あ い う …); after a pause, a
     * flick, another key or ▶, its next tap is a new kana. ↺ steps back and the toggle goes on.
     * ▶ that ends a toggle moves nothing.
     */
    @Test
    fun theSameKeyAgainTogglesUntilAPauseOrRight() {
        val v = pad()
        val a = "あいうえおぁぃぅぇぉ"
        repeat(3) { tap(v, 1, 0) }
        assertEquals(listOf("kana あ", "toggle $a", "toggle $a"), events)
        events.clear()
        pause(1100)
        tap(v, 1, 0)
        pause(600)
        tap(v, 1, 0)
        pause(600)
        tap(v, 1, 0)                   // each within a second of the last: still toggling
        assertEquals(listOf("kana あ", "toggle $a", "toggle $a"), events)
        events.clear()
        tap(v, 4, 1)                   // ▶ ends it
        tap(v, 1, 0)
        assertEquals(listOf("kana あ"), events)
        events.clear()
        tap(v, 0, 0)                   // ↺, then あ again: the toggle goes on
        tap(v, 1, 0)
        assertEquals(listOf("back", "toggle $a"), events)
        events.clear()
        tap(v, 2, 0)                   // another key
        tap(v, 1, 0)
        flick(v, 1, 0, 0f, -100f)      // a flick ends it too
        tap(v, 1, 0)
        assertEquals(listOf("kana か", "kana あ", "kana う", "kana あ"), events)
        events.clear()
        tap(v, 1, 3)                   // ゛゜小 ends it
        tap(v, 1, 0)
        assertEquals(listOf("mark", "kana あ"), events)
    }

    @Test
    fun withTheToggleOffEachTapIsAKana() {
        val v = pad { toggleInput = false }
        repeat(2) { tap(v, 1, 0) }
        assertEquals(listOf("kana あ", "kana あ"), events)
    }

    /**
     * ◀ and ▶ move the cursor a character. With a reading composing, ▶ stays put (the cursor is
     * at its end), so a habit of ▶ after a kana commits nothing.
     */
    @Test
    fun theArrowsMoveTheCursor() {
        val v = pad()
        tap(v, 0, 1)
        tap(v, 4, 1)
        assertEquals(listOf("cursor", "move -1 0", "end", "cursor", "move 1 0", "end"), events)
        events.clear()
        v.composing = true
        tap(v, 4, 1)
        assertEquals(emptyList<String>(), events)
        tap(v, 0, 1)
        assertEquals(listOf("cursor", "move -1 0", "end"), events)
    }

    /**
     * ⌫, space and Enter: as on the letters. ?123 opens the number pad, whose 記号 opens the
     * symbols; they lead back to the kana as あいう, and so does the number pad's あいう.
     */
    @Test
    fun theRightColumnAndTheSymbols() {
        val v = pad()
        tap(v, 4, 0)
        tap(v, 4, 2)
        tap(v, 4, 3)
        assertEquals(listOf("⌫", " ", "⏎"), events)
        assertEquals("空白", v.spaceLabel)
        assertNull(v.enterLabel)
        v.composing = true
        assertEquals("変換", v.spaceLabel)
        assertEquals("確定", v.enterLabel)
        tap(v, 0, 2)
        assertEquals(Page.KANA_NUMBERS, v.page)
        assertEquals("変換", v.spaceLabel)
        tap(v, 0, 2)                                  // あいう
        assertEquals(Page.KANA, v.page)
        tap(v, 0, 2)
        tap(v, 0, 0)                                  // 記号
        assertEquals(Page.SYMBOLS, v.page)
        assertEquals("日本語", v.spaceLabel)
        assertNull(v.enterLabel)
        touch(v, MotionEvent.ACTION_DOWN, 81f, 954f)  // the symbols' bottom-left: あいう
        touch(v, MotionEvent.ACTION_UP, 81f, 954f)
        assertEquals(Page.KANA, v.page)
    }

    /**
     * The number pad, as Gboard's: a tap types a digit as a letter key types (no toggle: 1 twice
     * is 11), a flick left, up or right a symbol, and the bottom row's brackets and ., - /. Its
     * guide shows the symbols in a cross, as the kana's does.
     */
    @Test
    fun theNumberPadTypesDigitsAndFlicksSymbols() {
        for ((theme, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            events.clear()
            val v = pad(palette) { page = Page.KANA_NUMBERS }
            shot("flick-numbers-$theme", v)
            val keys = listOf(
                Triple(1, 0, "1☆♪→"), Triple(2, 0, "2¥$€"), Triple(3, 0, "3%°#"),
                Triple(1, 1, "4○*・"), Triple(2, 1, "5+×÷"), Triple(3, 1, "6<=>"),
                Triple(1, 2, "7「」:"), Triple(2, 2, "8〒々〆"), Triple(3, 2, "9^|\\"),
                Triple(1, 3, "()[]"), Triple(2, 3, "0~…"), Triple(3, 3, ".,-/"),
            )
            for ((col, row, typed) in keys) {
                events.clear()
                tap(v, col, row)
                for ((dx, dy) in listOf(left, up, right, down)) flick(v, col, row, dx, dy)
                assertEquals(typed, typed.map { it.toString() }, events)
            }
            events.clear()
            repeat(2) { tap(v, 1, 0) }
            assertEquals(listOf("1", "1"), events)
            touch(v, MotionEvent.ACTION_DOWN, cols[1], rows[0])
            touch(v, MotionEvent.ACTION_MOVE, cols[1], rows[0] - 100f)
            shot("flick-numbers-guide-$theme", v)
            touch(v, MotionEvent.ACTION_UP, cols[1], rows[0] - 100f)
            assertEquals(listOf("1", "1", "♪"), events)
        }
    }

    /**
     * 🌐 opens the comma's menu at once, ⌨ VI EN JA あ: a slide and a lift chooses, as from the
     * comma; a tap leaves it open for a tap to choose, and a tap elsewhere closes it, typing
     * nothing. Its five cells (108 px from x 38) fit on the bottom row.
     */
    @Test
    fun theGlobeOpensTheKeyboardAndLanguageMenu() {
        val v = pad()
        touch(v, MotionEvent.ACTION_DOWN, cols[0], rows[3])
        assertEquals(listOf(KeyboardView.KEYBOARD_MENU, "VI", "EN", "JA", "あ"), v.popupForms)
        touch(v, MotionEvent.ACTION_MOVE, 307f, rows[3])
        assertEquals("EN", v.popupChosen)
        touch(v, MotionEvent.ACTION_UP, 307f, rows[3])
        assertEquals(listOf("language en"), events)
        assertEquals(emptyList<String>(), v.popupForms)
        events.clear()
        tap(v, 0, 3)
        assertEquals(5, v.popupForms.size)
        shot("flick-globe-menu-dark", v)
        touch(v, MotionEvent.ACTION_DOWN, 415f, rows[3])   // JA
        touch(v, MotionEvent.ACTION_UP, 415f, rows[3])
        assertEquals(listOf("language ja"), events)
        assertEquals(emptyList<String>(), v.popupForms)
        events.clear()
        tap(v, 0, 3)
        tap(v, 2, 0)                   // か, outside the menu: it closes
        assertEquals(emptyList<String>(), v.popupForms)
        tap(v, 2, 0)
        assertEquals(listOf("kana か"), events)
        events.clear()
        tap(v, 0, 3)
        tap(v, 0, 3)                   // ⌨, under the globe: Android's keyboard menu
        assertEquals(listOf("keyboards"), events)
        events.clear()
        tap(v, 0, 3)
        touch(v, MotionEvent.ACTION_DOWN, 523f, rows[3])   // あ, the one in use: nothing
        touch(v, MotionEvent.ACTION_UP, 523f, rows[3])
        assertEquals(emptyList<String>(), events)
    }

    /** The comma's menu on the letters has the fifth choice too, and it fits there. */
    @Test
    fun theCommasMenuOffersThePad() {
        val v = pad { home = Page.LETTERS; page = Page.LETTERS; language = "ja" }
        touch(v, MotionEvent.ACTION_DOWN, 216f, 954f)
        pause(450)
        assertEquals(listOf(KeyboardView.KEYBOARD_MENU, "VI", "EN", "JA", "あ"), v.popupForms)
        shot("flick-comma-menu-dark", v)
        touch(v, MotionEvent.ACTION_MOVE, 216f + 4 * 108f, 954f)
        assertEquals("あ", v.popupChosen)
        touch(v, MotionEvent.ACTION_UP, 216f + 4 * 108f, 954f)
        assertEquals(listOf("language ${Options.FLICK}"), events)
    }

    /** A touch on the strip ends a toggle (a candidate changes the reading). */
    @Test
    fun theStripEndsAToggle() {
        val v = pad { strip = Strip.Candidates("かみ", "紙", "カミ") }
        tap(v, 1, 0)
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        touch(v, MotionEvent.ACTION_UP, 540f, 72f)
        tap(v, 1, 0)
        assertEquals(listOf("kana あ", "slot 1", "kana あ"), events)
    }

    /** The pad, and the guide while a finger is on a key: at rest, and mid-flick. */
    @Test
    fun theShots() {
        for ((name, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            val v = pad(palette)
            shot("flick-pad-$name", v)
            v.composing = true
            v.strip = Strip.Candidates("わたし", "私", "ワタシ", more = true)
            shot("flick-pad-composing-$name", v)
            touch(v, MotionEvent.ACTION_DOWN, cols[2], rows[0])
            shot("flick-guide-ka-$name", v)
            touch(v, MotionEvent.ACTION_MOVE, cols[2] - 90f, rows[0] + 10f)
            shot("flick-guide-ka-left-$name", v)
            touch(v, MotionEvent.ACTION_UP, cols[2] - 90f, rows[0] + 10f)
            touch(v, MotionEvent.ACTION_DOWN, cols[2], rows[3])
            touch(v, MotionEvent.ACTION_MOVE, cols[2], rows[3] - 90f)
            shot("flick-guide-wa-up-$name", v)
            touch(v, MotionEvent.ACTION_UP, cols[2], rows[3] - 90f)
            touch(v, MotionEvent.ACTION_DOWN, cols[1], rows[0])
            touch(v, MotionEvent.ACTION_MOVE, cols[1] - 90f, rows[0])
            shot("flick-guide-a-left-$name", v)
            touch(v, MotionEvent.ACTION_UP, cols[1] - 90f, rows[0])
            touch(v, MotionEvent.ACTION_DOWN, cols[2], rows[2])
            touch(v, MotionEvent.ACTION_MOVE, cols[2] + 90f, rows[2])
            shot("flick-guide-ya-right-$name", v)
            touch(v, MotionEvent.ACTION_UP, cols[2] + 90f, rows[2])
        }
        val noRow = pad { numberRow = false }
        assertEquals(864, noRow.height)
        shot("flick-pad-no-number-row-dark", noRow)
    }
}
