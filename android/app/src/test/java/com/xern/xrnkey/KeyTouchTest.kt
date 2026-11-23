package com.xern.xrnkey

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.MorePanelView
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
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
 * Holding and sliding on the keys, through real touch events on a keyboard in a window.
 * At xxhdpi (dp = 3) and 1080 px wide: the strip is 144 px, then five rows of 180 px (the
 * number row, qwerty, asdf, zxcv, the bottom row); a letter key is 108 px wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyTouchTest {
    private val events = mutableListOf<String>()
    private var t = 0L

    private fun keyboard(palette: Palette = Palette.DARK, setup: KeyboardView.() -> Unit = {}): KeyboardView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = KeyboardView(activity)
        v.palette = palette
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
            override fun onDeleteWord() { events += "word" }
            override fun onEmoticon(text: String) { events += "emoticon $text" }
            override fun onCandidate(slot: Int) { events += "slot $slot" }
            override fun onMorePick(text: String) { events += "more $text" }
            override fun onRemove(text: String) { events += "remove $text" }
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

    private fun hold(ms: Long) {
        t += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun shot(name: String, v: KeyboardView) {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val spaceY = 954f
    private val topRowY = 414f

    @Test
    fun slidingAlongTheSpaceBarMovesTheCursorAndTypesNoSpace() {
        val v = keyboard()
        val slop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop
        touch(v, MotionEvent.ACTION_DOWN, 540f, spaceY)
        touch(v, MotionEvent.ACTION_MOVE, 545f, spaceY)        // within the slop: nothing yet
        // Past the slop, every 10 dp (30 px) is a character.
        touch(v, MotionEvent.ACTION_MOVE, 540f + slop + 150f, spaceY)
        touch(v, MotionEvent.ACTION_MOVE, 540f + slop + 90f, spaceY)
        touch(v, MotionEvent.ACTION_UP, 540f + slop + 90f, spaceY)
        assertEquals(listOf("cursor", "move 5 0", "move -2 0", "end"), events)
        events.clear()
        touch(v, MotionEvent.ACTION_DOWN, 540f, spaceY)
        touch(v, MotionEvent.ACTION_UP, 545f, spaceY)
        assertEquals(listOf(" "), events)
    }

    /**
     * Holding the space bar is a space, nothing more: it used to turn the keys into a cursor pad,
     * which the owner doesn't use (the tool row's cursor pad and the space bar's slide stay).
     */
    @Test
    fun holdingTheSpaceBarTypesASpace() {
        val v = keyboard()
        touch(v, MotionEvent.ACTION_DOWN, 540f, spaceY)
        hold(450)
        touch(v, MotionEvent.ACTION_UP, 540f, spaceY)
        assertEquals(listOf(" "), events)
    }

    @Test
    fun theSettingsTurnTheGesturesOff() {
        val v = keyboard { spaceSwipe = false; commaHold = false }
        touch(v, MotionEvent.ACTION_DOWN, 540f, spaceY)
        hold(450)
        touch(v, MotionEvent.ACTION_MOVE, 740f, spaceY)
        touch(v, MotionEvent.ACTION_UP, 740f, spaceY)
        touch(v, MotionEvent.ACTION_DOWN, 216f, spaceY)       // the comma
        hold(450)
        touch(v, MotionEvent.ACTION_UP, 216f, spaceY)
        assertEquals(listOf(" ", ","), events)
    }

    /**
     * Holding the comma opens its menu over it, as Laban Key's globe does: ⌨ under the finger,
     * then the languages to its right. Lifting on ⌨ opens Android's keyboard menu; sliding to a
     * language and lifting switches to it; the one in use does nothing. Nothing is typed.
     */
    @Test
    fun holdingTheCommaOpensTheKeyboardAndLanguageMenu() {
        val v = keyboard()
        touch(v, MotionEvent.ACTION_DOWN, 216f, spaceY)
        hold(450)
        assertEquals(listOf(KeyboardView.KEYBOARD_MENU, "VI", "EN"), v.popupForms)
        assertEquals(KeyboardView.KEYBOARD_MENU, v.popupChosen)
        shot("comma-menu-dark", v)
        touch(v, MotionEvent.ACTION_UP, 216f, spaceY)
        assertEquals(listOf("keyboards"), events)
        events.clear()
        for ((dx, want) in listOf(108f to emptyList(), 216f to listOf("language en"))) {
            touch(v, MotionEvent.ACTION_DOWN, 216f, spaceY)
            hold(450)
            touch(v, MotionEvent.ACTION_MOVE, 216f + dx, spaceY)
            touch(v, MotionEvent.ACTION_UP, 216f + dx, spaceY)
            assertEquals(want, events)
            events.clear()
        }
    }

    private fun holdAndLift(v: KeyboardView, x: Float, y: Float, dx: Float = 0f, dy: Float = 0f) {
        touch(v, MotionEvent.ACTION_DOWN, x, y)
        hold(450)
        if (dx != 0f || dy != 0f) touch(v, MotionEvent.ACTION_MOVE, x + dx, y + dy)
        touch(v, MotionEvent.ACTION_UP, x + dx, y + dy)
    }

    private val homeRowY = 594f

    /**
     * A held vowel offers all its toned forms, the grave one under the finger: let go there for
     * it, slide a key's width (or a cell's height, 44 dp) for the next. Cells are 108 x 132 px.
     */
    @Test
    fun holdingALetterOffersItsFormsAndTheFingerChooses() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            events.clear()
            val v = keyboard(palette)
            touch(v, MotionEvent.ACTION_DOWN, 270f, topRowY)  // e
            hold(450)
            assertEquals(12, v.popupForms.size)
            assertEquals("è", v.popupChosen)
            touch(v, MotionEvent.ACTION_MOVE, 378f, topRowY)  // one to the right: é
            shot("variants-e-${if (palette == Palette.DARK) "dark" else "light"}", v)
            touch(v, MotionEvent.ACTION_UP, 378f, topRowY)
            holdAndLift(v, 270f, topRowY)                     // let go where it was: è
            holdAndLift(v, 270f, topRowY, dy = -132f)         // a row up: ẹ
            holdAndLift(v, 270f, topRowY, dx = -108f, dy = -264f)  // two up, one left: ệ
            holdAndLift(v, 108f, homeRowY, dx = 971f)         // a, slid far right: the row's last
            assertEquals(listOf("é", "è", "ẹ", "ệ", "ạ"), events)
        }
    }

    /**
     * o is in the top row: its four rows don't fit above it, so the popup sits lower and the
     * finger still starts on ò; a cell up from there is ô, not the row the finger is over.
     */
    @Test
    fun aPopupMovedToFitStillStartsUnderTheFinger() {
        val v = keyboard()
        touch(v, MotionEvent.ACTION_DOWN, 918f, topRowY)
        hold(450)
        assertEquals(18, v.popupForms.size)
        assertEquals("ò", v.popupChosen)
        shot("variants-o-dark", v)
        touch(v, MotionEvent.ACTION_MOVE, 918f, topRowY - 132f)
        assertEquals("ô", v.popupChosen)
        touch(v, MotionEvent.ACTION_UP, 918f, topRowY - 132f)
        assertEquals(listOf("ô"), events)
    }

    /** Holding q typed 1, the digit right above it on the number row; it gives Q now. */
    @Test
    fun holdingAConsonantGivesItsCapitalNotADigit() {
        val v = keyboard()
        holdAndLift(v, 54f, topRowY)                // q
        holdAndLift(v, 324f, homeRowY)              // d: đ
        holdAndLift(v, 324f, homeRowY, dx = 108f)   // d, one right: D
        assertEquals(listOf("Q", "đ", "D"), events)
        // Without the number row, holding the top row gives its digit.
        events.clear()
        val noRow = keyboard { numberRow = false }
        holdAndLift(noRow, 54f, 48 * 3f + 90f)
        assertEquals(listOf("1"), events)
    }

    @Test
    fun holdingTheFullStopOffersPunctuation() {
        val v = keyboard()
        touch(v, MotionEvent.ACTION_DOWN, 864f, spaceY)
        hold(450)
        assertEquals(16, v.popupForms.size)
        shot("variants-stop-dark", v)
        touch(v, MotionEvent.ACTION_UP, 864f, spaceY)
        holdAndLift(v, 864f, spaceY, dx = 108f)
        assertEquals(listOf(",", "?"), events)
    }

    private fun tapAt(v: KeyboardView, x: Float, y: Float) {
        touch(v, MotionEvent.ACTION_DOWN, x, y)
        touch(v, MotionEvent.ACTION_UP, x, y)
    }

    /** The symbol pages' 1234 key opens the number pad (four rows of 225 px); ?123 and ABC lead out. */
    @Test
    fun theNumberPadOpensFromTheSymbolsAndLeadsBack() {
        val v = keyboard { page = Page.SYMBOLS }
        tapAt(v, 324f, spaceY)                 // 1234
        assertEquals(Page.NUMPAD, v.page)
        shot("numpad-dark", v)
        tapAt(v, 324f, 256f)                   // 1
        tapAt(v, 108f, 481f)                   // (
        tapAt(v, 972f, 256f)                   // -
        tapAt(v, 324f, 931f)                   // ?123
        assertEquals(Page.SYMBOLS, v.page)
        tapAt(v, 324f, spaceY)
        tapAt(v, 108f, 931f)                   // ABC
        assertEquals(Page.LETTERS, v.page)
        assertEquals(listOf("1", "(", "-"), events)
    }

    private val deleteX = 999f
    private val zRowY = 774f

    /** Slid left from Backspace past a key and a half: a word goes, once however far. */
    @Test
    fun swipingLeftFromBackspaceDeletesAWord() {
        val v = keyboard()
        touch(v, MotionEvent.ACTION_DOWN, deleteX, zRowY)
        touch(v, MotionEvent.ACTION_MOVE, deleteX - 100f, zRowY)  // not yet
        touch(v, MotionEvent.ACTION_MOVE, deleteX - 170f, zRowY)
        touch(v, MotionEvent.ACTION_MOVE, deleteX - 700f, zRowY)  // still one word
        touch(v, MotionEvent.ACTION_UP, deleteX - 700f, zRowY)
        assertEquals(listOf("⌫", "word"), events)
        // A short slide, or one to the right, is a Backspace.
        events.clear()
        touch(v, MotionEvent.ACTION_DOWN, deleteX, zRowY)
        touch(v, MotionEvent.ACTION_MOVE, deleteX - 60f, zRowY)
        touch(v, MotionEvent.ACTION_UP, deleteX - 60f, zRowY)
        assertEquals(listOf("⌫"), events)
    }

    /**
     * Backspace reaches the right edge, where the system's Back gesture would take a swipe that
     * starts on its outer part (and close the keyboard): the keyboard keeps that key for itself.
     */
    @Test
    fun theSystemBackGestureLeavesBackspaceAlone() {
        val v = keyboard()
        val rects = v.systemGestureExclusionRects
        assertEquals(1, rects.size)
        assertTrue(rects[0].contains(deleteX.toInt(), zRowY.toInt()))
        assertTrue(rects[0].contains(1075, zRowY.toInt()))  // out to the edge
        assertTrue(!rects[0].contains(1075, (zRowY - 180f).toInt()))  // not the l row above
    }

    private val numberY = 234f

    /** Swiping the number row left shows ten emoji, each typed whole; right brings the digits back. */
    @Test
    fun swipingTheNumberRowTurnsItToEmoticonsAndBack() {
        val v = keyboard()
        touch(v, MotionEvent.ACTION_DOWN, 594f, numberY)
        touch(v, MotionEvent.ACTION_MOVE, 594f - 250f, numberY)
        touch(v, MotionEvent.ACTION_UP, 594f - 250f, numberY)
        assertEquals(true, v.emoticonRow)
        assertEquals(emptyList<String>(), events)  // the swipe types nothing
        shot("emoticon-row-dark", v)
        touch(v, MotionEvent.ACTION_DOWN, 54f, numberY)
        touch(v, MotionEvent.ACTION_UP, 54f, numberY)
        touch(v, MotionEvent.ACTION_DOWN, 300f, numberY)
        touch(v, MotionEvent.ACTION_MOVE, 600f, numberY)
        touch(v, MotionEvent.ACTION_UP, 600f, numberY)
        assertEquals(false, v.emoticonRow)
        touch(v, MotionEvent.ACTION_DOWN, 54f, numberY)
        touch(v, MotionEvent.ACTION_MOVE, 162f, numberY)  // a short slide: the key it ends on
        touch(v, MotionEvent.ACTION_UP, 162f, numberY)
        assertEquals(listOf("emoticon 😂", "2"), events)
    }

    private val hel = Strip.Candidates(
        "help", "hello", "held", more = true,
        extra = listOf("helium", "hell", "helix", "helps", "helped", "helmet", "helpful", "hello kitty"),
    )

    /**
     * The panel (432 px: three rows of 132 px and 18 px margins) sits right above the strip, so
     * at y -18..-150 its bottom row (best in the middle, then left, right), then -150..-282.
     */
    @Test
    fun slidingUpFromTheStripOpensMoreAndLiftingOnOneTakesIt() {
        val v = keyboard { strip = hel }
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        assertEquals(emptyList<String>(), events)
        touch(v, MotionEvent.ACTION_MOVE, 540f, 20f)
        assertEquals(listOf("helium", "hell", "helix", "helps", "helped", "helmet", "helpful"), v.moreShown)
        assertEquals(-1, v.moreChosen)
        touch(v, MotionEvent.ACTION_MOVE, 540f, -80f)
        assertEquals(0, v.moreChosen)
        touch(v, MotionEvent.ACTION_MOVE, 300f, -200f)
        assertEquals(4, v.moreChosen)
        touch(v, MotionEvent.ACTION_UP, 300f, -200f)
        assertEquals(listOf("more helped"), events)
        assertEquals(emptyList<String>(), v.moreShown)
    }

    @Test
    fun slidingBackDownPastTheStripTakesNothing() {
        val v = keyboard { strip = hel }
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        touch(v, MotionEvent.ACTION_MOVE, 540f, -80f)
        assertEquals(0, v.moreChosen)
        touch(v, MotionEvent.ACTION_MOVE, 540f, 300f)
        assertEquals(emptyList<String>(), v.moreShown)
        touch(v, MotionEvent.ACTION_UP, 540f, 300f)
        assertEquals(emptyList<String>(), events)
    }

    /**
     * Lifted on the strip after a slide, the panel stays: each word or phrase in the strip shows
     * a mark that removes it (no emoji has one).
     */
    @Test
    fun theOpenPanelsMarksRemoveAWord() {
        val v = keyboard { strip = hel }
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        touch(v, MotionEvent.ACTION_MOVE, 540f, 20f)
        touch(v, MotionEvent.ACTION_MOVE, 540f, 60f)
        touch(v, MotionEvent.ACTION_UP, 540f, 60f)
        assertEquals(7, v.moreShown.size)
        shot("strip-more-open-dark", v)
        val mark = v.removeMark("hello")!!
        touch(v, MotionEvent.ACTION_DOWN, mark.x, mark.y)
        touch(v, MotionEvent.ACTION_UP, mark.x, mark.y)
        assertEquals(listOf("remove hello"), events)
        assertEquals(emptyList<String>(), v.moreShown)
        assertEquals(null, v.removeMark("hello"))  // closed: no marks
        // A phrase has one too; an emoji doesn't.
        v.strip = Strip.Candidates("cảm ơn", "cảm", "🍊", more = true, extra = listOf("cám"))
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        touch(v, MotionEvent.ACTION_MOVE, 540f, 20f)
        touch(v, MotionEvent.ACTION_UP, 540f, 20f)
        assertEquals(listOf("cám"), v.moreShown)
        assertTrue(v.removeMark("cảm ơn") != null)
        assertEquals(null, v.removeMark("🍊"))
    }

    /**
     * The dots under the middle only say there are more: a tap on them takes the middle word, as
     * anywhere on its slot (it opened the panel, and a typist hitting the lower half of the word
     * got the panel instead). Taken on lifting, not on landing.
     */
    @Test
    fun aTapOnTheDotsTakesTheMiddleWord() {
        val v = keyboard { strip = hel }
        touch(v, MotionEvent.ACTION_DOWN, 540f, 60f)     // the middle word
        assertEquals(emptyList<String>(), events)
        touch(v, MotionEvent.ACTION_UP, 540f, 60f)
        touch(v, MotionEvent.ACTION_DOWN, 540f, 130f)    // the dots under it
        touch(v, MotionEvent.ACTION_UP, 540f, 130f)
        assertEquals(listOf("slot 1", "slot 1"), events)
        assertEquals(emptyList<String>(), v.moreShown)
    }

    /** A key closes the open panel, and types; with nothing more, a slide up opens nothing. */
    @Test
    fun aKeyClosesThePanelAndTypes() {
        val v = keyboard { strip = hel }
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        touch(v, MotionEvent.ACTION_MOVE, 540f, 20f)
        touch(v, MotionEvent.ACTION_UP, 540f, 20f)
        assertEquals(7, v.moreShown.size)
        touch(v, MotionEvent.ACTION_DOWN, 270f, 387f)    // e
        touch(v, MotionEvent.ACTION_UP, 270f, 387f)
        assertEquals(emptyList<String>(), v.moreShown)
        assertEquals(listOf("e"), events)
        events.clear()
        v.strip = Strip.Candidates("a", "b", "c")
        touch(v, MotionEvent.ACTION_DOWN, 540f, 72f)
        touch(v, MotionEvent.ACTION_MOVE, 540f, -100f)
        touch(v, MotionEvent.ACTION_UP, 540f, -100f)
        assertEquals(emptyList<String>(), v.moreShown)
        assertEquals(emptyList<String>(), events)
    }

    /** Once open, a tap on the panel itself (its own window) takes the candidate. */
    @Test
    fun aTapOnTheOpenPanelTakesACandidate() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val p = MorePanelView(activity)
        p.words = hel.extra
        var picked = -1
        p.onPick = { picked = it }
        p.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        p.layout(0, 0, p.measuredWidth, p.measuredHeight)
        assertEquals(432, p.height)
        val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 900f, 350f, 0)
        p.onTouchEvent(e)
        assertEquals(2, p.chosen)  // the bottom row's right: helix
        e.action = MotionEvent.ACTION_UP
        p.onTouchEvent(e)
        e.recycle()
        assertEquals(2, picked)
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        p.chosen = 0
        p.draw(Canvas(bmp))
        dir.mkdirs()
        File(dir, "more-panel-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theToolRowsCursorPadStaysUntilATap() {
        val v = keyboard()
        v.openCursorPad()
        shot("cursor-pad-dark", v)
        // Each drag moves from where it starts: 10 dp (30 px) a character, 28 dp (84 px) a line.
        touch(v, MotionEvent.ACTION_DOWN, 300f, 500f)
        touch(v, MotionEvent.ACTION_MOVE, 420f, 590f)
        touch(v, MotionEvent.ACTION_UP, 420f, 590f)
        touch(v, MotionEvent.ACTION_DOWN, 600f, 500f)
        touch(v, MotionEvent.ACTION_MOVE, 450f, 500f)
        touch(v, MotionEvent.ACTION_UP, 450f, 500f)
        touch(v, MotionEvent.ACTION_DOWN, 500f, 500f)  // a tap: closed
        touch(v, MotionEvent.ACTION_UP, 500f, 500f)
        assertEquals(listOf("cursor", "move 4 1", "move -5 0", "end"), events)
        events.clear()
        touch(v, MotionEvent.ACTION_DOWN, 270f, 387f)  // e: the keys type again
        touch(v, MotionEvent.ACTION_UP, 270f, 387f)
        assertEquals(listOf("e"), events)
    }
}
