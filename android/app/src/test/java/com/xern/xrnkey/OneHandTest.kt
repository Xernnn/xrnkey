package com.xern.xrnkey

import android.app.Activity
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.ClipboardPanelView
import com.xern.xrnkey.keyboard.EmojiPanelView
import com.xern.xrnkey.keyboard.Hand
import com.xern.xrnkey.keyboard.KeyboardFrame
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The one-handed keyboard, as Gboard's: the keys, the strip and every panel at 82 % of the width
 * against one edge, the free column holding full width and the switch of sides. Turned on from
 * the tool row, kept in the settings, portrait only. At xxhdpi (dp = 3) and 1080 px wide: the
 * keys are 886 px wide, the column 194; the strip is 144 px, the rows 180.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class OneHandTest {
    private fun app() = RuntimeEnvironment.getApplication()

    private fun start(o: Options = Options()): Pair<XrnkeyService, KeyboardFrame> {
        o.save(Options.prefs(app()))
        val s = Robolectric.setupService(XrnkeyService::class.java)
        val frame = s.onCreateInputView() as KeyboardFrame
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        shadowOf(Looper.getMainLooper()).idle()
        layout(frame)
        return s to frame
    }

    private fun layout(frame: ViewGroup) {
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
    }

    private inline fun <reified T : View> ViewGroup.child(): T = (0 until childCount).map { getChildAt(it) }.filterIsInstance<T>().single()

    @Test
    fun theKeysAndEveryPanelSitAgainstEitherEdge() {
        val (full, fullFrame) = start()
        val height = fullFrame.child<KeyboardView>().height
        assertEquals(View.GONE, fullFrame.bar.visibility)
        full.onDestroy()
        for ((side, keysLeft, barLeft) in listOf(Triple("left", 0, 886), Triple("right", 194, 0))) {
            val (s, frame) = start(Options(oneHanded = true, oneHandSide = side))
            val keys = frame.child<KeyboardView>()
            assertEquals(side, keysLeft, keys.left)
            assertEquals(side, 886, keys.width)
            // As tall as at full width: only the width changes.
            assertEquals(side, height, keys.height)
            assertEquals(side, View.VISIBLE, frame.bar.visibility)
            assertEquals(side, barLeft, frame.bar.left)
            assertEquals(side, 194, frame.bar.width)
            assertEquals(side, height, frame.bar.height)
            for ((tool, panel) in listOf(Tool.EMOJI to frame.child<EmojiPanelView>(), Tool.CLIPBOARD to frame.child<ClipboardPanelView>())) {
                s.onTool(tool)
                layout(frame)
                assertEquals("$side $tool", keysLeft, panel.left)
                assertEquals("$side $tool", 886, panel.width)
                assertEquals("$side $tool", height, panel.height)
                s.onClosePanel()
            }
            s.onDestroy()
        }
    }

    /**
     * The tool row's button turns it on and off; the column's arrow moves it to the other side,
     * its full-width button turns it off. The side is kept while it is off.
     */
    @Test
    fun theToolTurnsItOnAndTheColumnMovesOrWidensIt() {
        val (s, frame) = start()
        assertNull(frame.hand)
        s.onTool(Tool.ONE_HAND)
        assertEquals(Hand.RIGHT, frame.hand)
        assertTrue(Options.load(Options.prefs(app())).oneHanded)
        layout(frame)
        // The column's lower half is the arrow, its upper half full width.
        frame.bar.tap(frame.bar.height * 0.7f)
        assertEquals(Hand.LEFT, frame.hand)
        assertEquals("left", Options.load(Options.prefs(app())).oneHandSide)
        layout(frame)
        assertEquals(0, frame.child<KeyboardView>().left)
        frame.bar.tap(frame.bar.height * 0.3f)
        assertNull(frame.hand)
        assertFalse(Options.load(Options.prefs(app())).oneHanded)
        layout(frame)
        assertEquals(1080, frame.child<KeyboardView>().width)
        s.onTool(Tool.ONE_HAND)
        assertEquals(Hand.LEFT, frame.hand)
        s.onTool(Tool.ONE_HAND)
        assertNull(frame.hand)
        s.onDestroy()
    }

    /** Kept for the next field and the next start; in landscape the keyboard is full width. */
    @Test
    fun itIsRememberedInPortraitOnly() {
        val (s, frame) = start(Options(oneHanded = true, oneHandSide = "left"))
        assertEquals(Hand.LEFT, frame.hand)
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        assertEquals(Hand.LEFT, frame.hand)
        assertTrue(Tool.ONE_HAND in frame.child<KeyboardView>().tools)
        s.onDestroy()
        RuntimeEnvironment.setQualifiers("+land")
        val (l, landscape) = start(Options(oneHanded = true, oneHandSide = "left"))
        assertNull(landscape.hand)
        assertEquals(1080, landscape.child<KeyboardView>().width)
        // No button for it there.
        assertEquals(listOf(Tool.HIDE, Tool.SETTINGS, Tool.CLIPBOARD, Tool.CURSOR, Tool.EMOJI), landscape.child<KeyboardView>().tools)
        l.onDestroy()
    }

    /** A frame of keys alone, as the service builds it, with what the keys report. */
    private fun keysIn(hand: Hand?, events: MutableList<String>): KeyboardFrame {
        val frame = KeyboardFrame(app())
        val keys = KeyboardView(app())
        keys.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { events += text }
            override fun onLetter(text: String, dx: Float, dy: Float, keyHeight: Float) {
                events += "%s %.2f %.2f %.2f".format(java.util.Locale.ROOT, text, dx, dy, keyHeight)
            }
        }
        frame.addView(keys, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.hand = hand
        layout(frame)
        return frame
    }

    private fun tap(frame: ViewGroup, x: Float, y: Float) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(0, 0, action, x, y, 0)
            frame.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    /**
     * A tap on a narrowed key types that key, and says where on it the finger landed in that
     * key's widths and heights, as at full width: the typo fixes see the same offsets for the
     * same place on a key. The key is taller in its own widths (180 / 88.6 = 2.03).
     */
    @Test
    fun aTapOnANarrowedKeyTypesItWithItsOwnOffsets() {
        val unit = 886 / 10f
        val qwerty = 144f + 180f + 90f
        for ((hand, left) in listOf(Hand.LEFT to 0f, Hand.RIGHT to 194f)) {
            val events = mutableListOf<String>()
            val frame = keysIn(hand, events)
            tap(frame, left + 2.5f * unit, qwerty)                       // e, at its centre
            tap(frame, left + 0.5f * unit + 0.3f * unit, qwerty + 45f)   // q, right of and below its centre
            tap(frame, left + 9.5f * unit - 0.45f * unit, qwerty)        // p, near its left edge
            tap(frame, left + 1.5f * unit, 144f + 90f)                   // 2, on the number row
            assertEquals(
                "$hand",
                listOf("e 0.00 0.00 2.03", "q 0.30 0.25 2.03", "p -0.45 0.00 2.03", "2"),
                events,
            )
            // The free column types nothing.
            tap(frame, if (hand == Hand.LEFT) 1000f else 100f, qwerty)
            assertEquals(4, events.size)
        }
        // At full width, the same place on e is the same offset, in its own (wider) key.
        val events = mutableListOf<String>()
        tap(keysIn(null, events), 2.5f * 108f + 0.3f * 108f, qwerty)
        assertEquals(listOf("e 0.30 0.00 1.67"), events)
    }

    /**
     * Holds, slides and flicks, measured in the narrowed keys: the comma's menu (slide to EN),
     * the swipe from Backspace (a key and a half of the narrowed keys), and a flick on the 12-key
     * pad, in a window so that holds run.
     */
    @Test
    fun holdsSlidesAndFlicksWorkNarrowed() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val events = mutableListOf<String>()
        val frame = KeyboardFrame(activity)
        val keys = KeyboardView(activity)
        keys.languages = listOf("vi", "en")
        keys.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { events += text }
            override fun onBackspace() { events += "⌫" }
            override fun onDeleteWord() { events += "word" }
            override fun onLanguage(code: String) { events += "language $code" }
            override fun onKana(text: String) { events += text }
        }
        frame.addView(keys, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.hand = Hand.RIGHT
        activity.setContentView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        assertEquals(886, keys.width)
        val unit = 886 / 10f
        var t = 0L
        fun touch(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(0, t, action, 194f + x, y, 0)
            frame.dispatchTouchEvent(e)
            e.recycle()
        }
        // The comma (after ?123's key and a half), held: ⌨ VI EN from over it; slid two cells.
        val bottom = 144f + 4 * 180f + 90f
        touch(MotionEvent.ACTION_DOWN, 2f * unit, bottom)
        t += 450
        looper.idleFor(Duration.ofMillis(450))
        touch(MotionEvent.ACTION_MOVE, 4f * unit, bottom)
        touch(MotionEvent.ACTION_UP, 4f * unit, bottom)
        // Backspace, swiped left a key and a half and a little.
        val zxcv = 144f + 3 * 180f + 90f
        touch(MotionEvent.ACTION_DOWN, 9.25f * unit, zxcv)
        touch(MotionEvent.ACTION_MOVE, 7.6f * unit, zxcv)
        touch(MotionEvent.ACTION_UP, 7.6f * unit, zxcv)
        // The 12-key pad: か flicked left is き.
        keys.kana = true
        keys.page = Page.KANA
        val top = 144f + 225f / 2
        touch(MotionEvent.ACTION_DOWN, 5f * unit, top)
        touch(MotionEvent.ACTION_MOVE, 5f * unit - 120f, top)
        touch(MotionEvent.ACTION_UP, 5f * unit - 120f, top)
        assertEquals(listOf("language en", "⌫", "word", "き"), events)
    }
}
