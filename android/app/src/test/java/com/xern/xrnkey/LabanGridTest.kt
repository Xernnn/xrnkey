package com.xern.xrnkey

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Palette
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Taps where Laban Key's rows are on the owner's phone (1080 x 2400 at 2.5 px a dp) type what
 * they typed there. Laban Key's keyboard starts at y 1420: its strip is 110 px, its number row
 * 131, its other rows 153 (y 1662, 1815, 1968 and 2121 on the screen), with 7 px under the last.
 * The keyboard here is as tall and sits as low, so a point on the screen is the same point on
 * both: y on the keyboard is y on the screen less 1420.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabanGridTest {
    private val typed = mutableListOf<String>()

    private fun keyboard(): KeyboardView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val v = KeyboardView(activity)
        v.palette = Palette.DARK
        v.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed += text }
            override fun onBackspace() { typed += "⌫" }
            override fun onEnter() { typed += "⏎" }
            override fun onKeyboardMenu() = Unit
            override fun onLanguage(code: String) = Unit
            override fun onCursorStart() = Unit
            override fun onCursor(dx: Int, dy: Int) = Unit
            override fun onCursorEnd() = Unit
            override fun onDeleteWord() = Unit
            override fun onEmoticon(text: String) = Unit
            override fun onCandidate(slot: Int) = Unit
            override fun onMorePick(text: String) = Unit
            override fun onRemove(text: String) = Unit
        }
        activity.setContentView(v)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1080, v.width)
        return v
    }

    /** A tap at a point of the owner's screen. */
    private fun tap(v: KeyboardView, x: Int, screenY: Int): String {
        typed.clear()
        val y = (screenY - 1420).toFloat()
        for ((t, action) in listOf(0L to MotionEvent.ACTION_DOWN, 60L to MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(0, t, action, x.toFloat(), y, 0)
            v.onTouchEvent(e)
            e.recycle()
        }
        return typed.joinToString("")
    }

    @Test
    fun theKeyboardIsAsTallAsLabans() {
        // Laban Key's keyboard on the owner's phone: y 1420 to 2280.
        assertEquals(860, keyboard().height)
    }

    @Test
    fun eachRowStartsWhereLabansDoes() {
        val v = keyboard()
        // Just under the strip, and at the bottom of the number row.
        assertEquals("1", tap(v, 54, 1533))
        assertEquals("3", tap(v, 270, 1658))
        // The top of each letter row: with a number row as tall as the others, these typed the
        // row above ("3", "w", "d", "v").
        assertEquals("e", tap(v, 270, 1666))
        assertEquals("s", tap(v, 216, 1819))
        assertEquals("x", tap(v, 324, 1972))
        assertEquals(" ", tap(v, 540, 2125))
        // The bottom of each: the same keys.
        assertEquals("e", tap(v, 270, 1811))
        assertEquals("s", tap(v, 216, 1964))
        assertEquals("x", tap(v, 324, 2117))
        // Under the space bar's tile, in the gap above the navigation bar: still the space bar.
        assertEquals(" ", tap(v, 540, 2278))
    }
}
