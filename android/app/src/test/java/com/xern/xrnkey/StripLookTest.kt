package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View.MeasureSpec
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Strip
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
 * slot's own colour ("bạn" of "bạn bè"), as Laban Key draws a word with its next word.
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
}
