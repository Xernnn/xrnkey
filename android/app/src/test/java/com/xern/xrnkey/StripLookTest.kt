package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View.MeasureSpec
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Strip
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

/**
 * The strip's candidates as drawn: the part already in the field is grey and the rest in the
 * slot's own colour ("bạn" of "bạn bè"), as Laban Key draws a word with its next word; a row of
 * emoji in five even places.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StripLookTest {
    private fun draw(strip: Strip, palette: Palette): Bitmap {
        val v = KeyboardView(RuntimeEnvironment.getApplication())
        v.palette = palette
        v.strip = strip
        v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    /** The contrast with the strip of the inkiest pixel in columns x0 until x1 (WCAG's ratio). */
    private fun ink(bmp: Bitmap, palette: Palette, x0: Int, x1: Int): Double {
        val bg = luminance(palette.strip)
        var most = 1.0
        for (x in x0 until x1) for (y in 10 until 110) {
            val l = luminance(bmp.getPixel(x, y))
            most = maxOf(most, (maxOf(l, bg) + 0.05) / (minOf(l, bg) + 0.05))
        }
        return most
    }

    @Test
    fun theTypedPartOfACandidateIsDrawnGrey() {
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            val plain = draw(Strip.Candidates("bạn bè", "bạn bè", "bạn bè", bold = false), palette)
            val two = draw(Strip.Candidates("bạn bè", "bạn bè", "bạn bè", bold = false, grey = listOf(3, 3, 3)), palette)
            // The slots are 280 px wide from x = 120 (1080 px, 48 dp at each end); the text is
            // centred in each, "bạn" left of the centre and "bè" right of it.
            for (centre in listOf(260, 540, 820)) {
                val head = centre - 55 until centre - 15
                val tail = centre + 20 until centre + 60
                val why = "$palette, slot at $centre"
                assertTrue(why, ink(two, palette, head.first, head.last) < 0.8 * ink(plain, palette, head.first, head.last))
                assertTrue(why, ink(two, palette, tail.first, tail.last) > 0.9 * ink(plain, palette, tail.first, tail.last))
            }
        }
    }

    /**
     * A row of emoji takes five even places between the menu and the emoji button (168 px each
     * from x = 120), each drawn at its own centre and each a tap of its own; an empty place
     * stays blank. The three slots are 280 px each.
     */
    @Test
    fun aRowOfEmojiTakesFiveEvenPlaces() {
        // Letters stand in for the emoji: the host's fonts may have no colour emoji.
        val row = Strip.Candidates(null, null, null, bold = false, row = listOf(null, "W", "W", "W", "W"))
        for (palette in listOf(Palette.DARK, Palette.LIGHT)) {
            val bmp = draw(row, palette)
            val places = (0 until 5).map { 120 + 168 * it + 84 }
            assertTrue(ink(bmp, palette, places[0] - 30, places[0] + 30) < 1.5)
            for (x in places.drop(1)) assertTrue("$palette at $x", ink(bmp, palette, x - 20, x + 20) > 3.0)
            // Between two places, nothing: each emoji sits in its own.
            assertTrue(ink(bmp, palette, 120 + 168 * 2 - 6, 120 + 168 * 2 + 6) < 1.5)
        }
        val v = KeyboardView(RuntimeEnvironment.getApplication())
        val taps = mutableListOf<Int>()
        v.listener = object : KeyboardView.Listener {
            override fun onCandidate(slot: Int) { taps += slot }
        }
        v.strip = row
        v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        for (x in listOf(204f, 372f, 540f, 708f, 876f)) {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val e = MotionEvent.obtain(0, 0, action, x, 60f, 0)
                v.onTouchEvent(e)
                e.recycle()
            }
        }
        assertEquals(listOf(0, 1, 2, 3, 4), taps)
        // Three slots again: a tap at 876 is the right slot.
        taps.clear()
        v.strip = Strip.Candidates("a", "b", "c")
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(0, 0, action, 876f, 60f, 0)
            v.onTouchEvent(e)
            e.recycle()
        }
        assertEquals(listOf(2), taps)
    }
}
