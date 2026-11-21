package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View.MeasureSpec
import com.xern.xrnkey.keyboard.EnterAction
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

/**
 * The keys as the owner's phone shows them (1080 px at 2.5 px a dp), measured against Laban
 * Key on the same phone: rows 60 dp, letters about 30 sp, a space bar that reads as one, and a
 * bright Enter in the plum of the tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LookTest {
    private val dp = 2.5f

    private fun view(palette: Palette = Palette.DARK, width: Int = 1080, setup: KeyboardView.() -> Unit = {}): KeyboardView {
        val v = KeyboardView(RuntimeEnvironment.getApplication())
        v.palette = palette
        v.setup()
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun draw(v: KeyboardView): Bitmap =
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun shot(name: String, bmp: Bitmap) {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun theShots() {
        for ((name, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            shot("keys-$name", draw(view(palette) { enterAction = EnterAction.DONE }))
            shot("keys-shift-$name", draw(view(palette) { shift = Shift.ONCE; enterAction = EnterAction.SEND }))
            shot("keys-capslock-$name", draw(view(palette) { shift = Shift.LOCKED; enterAction = EnterAction.SEARCH }))
        }
    }

    @Test
    fun rowsAreAsTallAsLabans() {
        // The strip (48 dp) and five rows of 60 dp.
        assertEquals(((48 + 5 * 60) * dp).toInt(), view().measuredHeight)
    }

    @Test
    fun theAccentIsThePlumAndStandsOut() {
        assertEquals(0xFF983A78.toInt(), Palette.LIGHT.accent)
        for (p in listOf(Palette.DARK, Palette.LIGHT)) {
            assertTrue("accent on the keyboard", contrast(p.accent, p.background) >= 3.0)
            assertTrue("icon on the accent", contrast(p.onAccent, p.accent) >= 3.0)
        }
    }

    @Test
    fun enterIsARoundAccentKey() {
        val v = view()
        val bmp = draw(v)
        // The Enter key: the last key of the bottom row, 1.5 keys wide.
        val cx = (1080 - 1.5f * 108 / 2).toInt()
        val cy = (v.height - 30 * dp).toInt()
        // Its disc around the icon, a little off the centre.
        assertEquals(Palette.DARK.accent, bmp.getPixel(cx - (12 * dp).toInt(), cy - (12 * dp).toInt()))
    }

    @Test
    fun shiftFillsWhenOnAndCapsLockIsItsOwn() {
        fun centre(shift: Shift): Int {
            val v = view { this.shift = shift }
            val y = (v.height - 60 * dp - 30 * dp).toInt()  // the row above the bottom one
            return draw(v).getPixel((0.75f * 108).toInt(), y)
        }
        assertEquals(Palette.DARK.background, centre(Shift.OFF))
        assertEquals(Palette.DARK.glyph, centre(Shift.ONCE))
        assertEquals(Palette.DARK.accent, centre(Shift.LOCKED))
    }

    @Test
    fun theSpaceBarNamesTheLanguageAndShortensWhenNarrow() {
        val wide = view()
        assertEquals("Tiếng Việt", wide.spaceLabel)
        val narrow = view(width = 400)
        assertEquals("VI", narrow.spaceLabel)
    }
}
