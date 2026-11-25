package com.xern.xrnkey

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band under the keys, in the keyboard's real window. The input view sits inside the
 * window's own layout, whose top view fits the system windows: given the insets, it took them
 * and padded itself, so the keyboard got none, and on the phone the bottom row sat on the
 * gesture bar (0.3.1). Each page and panel must end above the band, inside the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
class ImeBandTest {
    private val band = 120  // px: the phone's band under a keyboard (48 dp at 2.5 px a dp)
    private val dp = 2.5f

    private fun insets() = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 40))
        .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, band))
        .build()

    private class Window(val service: XrnkeyService, val frame: ViewGroup, val decor: View) {
        val keys get() = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

        /** Insets from the top of the window, as the system gives them, then a measure. */
        fun layout(insets: WindowInsetsCompat) {
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
            )
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
    }

    private fun window(): Window {
        val s = Robolectric.setupService(XrnkeyService::class.java)
        val frame = s.onCreateInputView() as ViewGroup
        s.setInputView(frame)
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        // Shown, as when a field has the keyboard.
        var v: View? = frame
        while (v != null) {
            v.visibility = View.VISIBLE
            v = v.parent as? View
        }
        return Window(s, frame, s.window.window!!.decorView)
    }

    private fun check(w: Window, what: String) {
        w.layout(insets())
        // The strip (44 dp) and Laban Key's grid (a 52.4 dp number row, four of 61.2, 2.8 under them).
        val keysHeight = ((44 + 300) * dp).toInt()
        // The keyboard is its keys and the band, and nothing outside it pads the window.
        assertEquals("$what: the keyboard", keysHeight + band, w.frame.measuredHeight)
        assertEquals("$what: the window", w.frame.measuredHeight, w.decor.measuredHeight)
        // Whatever is showing ends above the band.
        val shown = (0 until w.frame.childCount).map { w.frame.getChildAt(it) }.single { it.visibility == View.VISIBLE }
        assertTrue("$what ends at ${shown.bottom}, the band starts at ${w.frame.height - band}", shown.bottom <= w.frame.height - band)
    }

    @Test
    fun everyPageAndPanelEndsAboveTheBand() {
        val w = window()
        check(w, "letters")
        w.keys.page = Page.SYMBOLS
        check(w, "symbols")
        w.keys.page = Page.MORE_SYMBOLS
        check(w, "more symbols")
        w.keys.page = Page.LETTERS
        w.service.onTool(Tool.EMOJI)
        check(w, "emoji")
        w.service.onEmojiSearch()
        check(w, "emoji search")
        w.service.onSearchClose()
        w.service.onClosePanel()
        w.service.onTool(Tool.CLIPBOARD)
        check(w, "clipboard")
        w.service.onClosePanel()
        w.service.onTool(Tool.CURSOR)
        check(w, "cursor pad")
        for (type in listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        )) {
            w.service.onStartInputView(EditorInfo().apply { inputType = type }, false)
            check(w, "pad $type")
        }
        w.service.onDestroy()
    }

    @Test
    fun theBandIsTheLargerOfTheBarAndTheGestureArea() {
        val w = window()
        w.layout(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 150))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, 60))
                .build(),
        )
        assertEquals(150, w.frame.paddingBottom)
        w.service.onDestroy()
    }

    @Test
    fun theBandFollowsInsetsThatComeLateOrChange() {
        // On the phone the first show could come before the window's insets: 860 px, no band.
        val w = window()
        w.layout(WindowInsetsCompat.Builder().build())
        // The strip (44 dp) and Laban Key's grid (a 52.4 dp number row, four of 61.2, 2.8 under them).
        val keysHeight = ((44 + 300) * dp).toInt()
        assertEquals(keysHeight, w.frame.measuredHeight)
        w.layout(insets())
        assertEquals(keysHeight + band, w.frame.measuredHeight)
        w.layout(
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 66))
                .build(),
        )
        assertEquals(keysHeight + 66, w.frame.measuredHeight)
        w.service.onDestroy()
    }

    @Test
    fun theWindowManagersInsetsGiveTheBandWhenTheViewsGetNone() {
        // 0.4.0 on the phone: the views were never given the bar, but the window manager knew it.
        val w = window()
        (w.frame as com.xern.xrnkey.keyboard.KeyboardFrame).metricsInsets = { androidx.core.graphics.Insets.of(0, 0, 0, band) }
        w.service.onWindowShown()
        w.layout(WindowInsetsCompat.Builder().build())
        assertEquals(((44 + 300) * dp).toInt() + band, w.frame.measuredHeight)
        val keys = w.keys
        assertTrue(keys.bottom <= w.frame.measuredHeight - band)
        w.service.onDestroy()
    }
}
