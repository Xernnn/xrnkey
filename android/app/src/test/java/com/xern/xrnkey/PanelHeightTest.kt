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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every panel keeps the keyboard's height. The keyboard's window gives the input view at most
 * the screen's height (AT_MOST), and a panel that took all of it pushed the window up to the
 * top of the screen: on the phone the emoji tabs sat over the app (frame [0,104][1080,2400]
 * against [0,1410][1080,2400] for the letters).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
class PanelHeightTest {
    private val screen = 2400
    private val nav = 135

    private fun start(): Pair<XrnkeyService, ViewGroup> {
        val s = Robolectric.setupService(XrnkeyService::class.java)
        val frame = s.onCreateInputView() as ViewGroup
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        ViewCompat.dispatchApplyWindowInsets(
            frame,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                .build(),
        )
        return s to frame
    }

    /** The height the window would give the input view, as the IME window measures it. */
    private fun height(frame: ViewGroup): Int {
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen, View.MeasureSpec.AT_MOST),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        return frame.measuredHeight
    }

    private fun keys(frame: ViewGroup) =
        (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

    @Test
    fun everyPanelIsAsTallAsTheLetters() {
        val (s, frame) = start()
        val letters = height(frame)
        // The strip (44 dp), Laban Key's grid (300 dp) and the navigation band, at 2.5 px a dp.
        assertEquals(((44 + 300) * 2.5).toInt() + nav, letters)

        s.onTool(Tool.EMOJI)
        assertEquals("emoji", letters, height(frame))
        s.onEmojiSearch()
        assertEquals("emoji search", letters, height(frame))
        s.onSearchClose()
        assertEquals("emoji, after search", letters, height(frame))
        s.onClosePanel()
        assertEquals("letters again", letters, height(frame))

        s.onTool(Tool.CLIPBOARD)
        assertEquals("clipboard", letters, height(frame))
        s.onClosePanel()

        s.onTool(Tool.CURSOR)
        assertEquals("cursor pad", letters, height(frame))

        val k = keys(frame)
        for (page in listOf(Page.SYMBOLS, Page.MORE_SYMBOLS, Page.NUMPAD)) {
            k.page = page
            assertEquals("$page", letters, height(frame))
        }
        // The pads of number, PIN, phone and date fields.
        for (type in listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
        )) {
            s.onStartInputView(EditorInfo().apply { inputType = type }, false)
            assertEquals("pad for $type", letters, height(frame))
        }
        s.onDestroy()
    }

    @Test
    fun thePanelsFillTheKeyboardsPlaceAboveTheBand() {
        val (s, frame) = start()
        val letters = height(frame)
        s.onTool(Tool.EMOJI)
        height(frame)
        val panel = (0 until frame.childCount).map { frame.getChildAt(it) }.single { it.visibility == View.VISIBLE }
        assertEquals(0, panel.top)
        assertEquals(letters - nav, panel.bottom)
        s.onDestroy()
    }
}
