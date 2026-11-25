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
 * Key on the same phone: its grid, its grey tiles on black with darker ones around the letters,
 * and letters about 30 sp.
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
    fun theKeyboardIsAsTallAsLabans() {
        // Laban Key's strip (44 dp), number row (52.4), four rows (61.2 each) and the gap under them.
        assertEquals(860, view().measuredHeight)
    }

    /**
     * Laban Key's tiles on the owner's phone, from a screenshot (screen pixels, the keyboard
     * starting at y 1420): each tile's colour reaches 3 px inside its edges, and the gaps
     * between them are the keyboard's black.
     */
    @Test
    fun theTilesAreWhereLabansAre() {
        val p = Palette.DARK
        val bmp = draw(view { enterAction = EnterAction.DONE })
        fun at(x: Int, screenY: Int) = bmp.getPixel(x, screenY - 1420)
        // left, top, right, bottom on the screen, and the tile's colour.
        val tiles = listOf(
            "1" to intArrayOf(10, 1550, 97, 1649, p.key),
            "q" to intArrayOf(10, 1672, 98, 1801, p.key),
            "a" to intArrayOf(63, 1825, 151, 1954, p.key),
            "Shift" to intArrayOf(10, 1978, 141, 2107, p.function),
            "z" to intArrayOf(172, 1978, 259, 2107, p.key),
            "?123" to intArrayOf(10, 2131, 152, 2260, p.function),
            "space" to intArrayOf(279, 2131, 800, 2260, p.key),
            "Enter" to intArrayOf(928, 2131, 1070, 2260, p.function),
        )
        for ((name, t) in tiles) {
            val (l, top, r, bottom, colour) = t
            val cx = (l + r) / 2
            val cy = (top + bottom) / 2
            for ((x, y) in listOf(l + 3 to cy, r - 3 to cy, cx to top + 3, cx to bottom - 3)) {
                assertEquals("$name at $x, $y", colour, at(x, y))
            }
        }
        // Between q and w, between the number row and q, between q and a, left of a.
        for ((x, y) in listOf(108 to 1736, 54 to 1660, 54 to 1813, 30 to 1890)) {
            assertEquals("gap at $x, $y", p.background, at(x, y))
        }
    }

    @Test
    fun theLettersReadOnTheirTiles() {
        assertEquals(0xFF000000.toInt(), Palette.DARK.background)
        assertEquals(0xFF444444.toInt(), Palette.DARK.key)
        assertEquals(0xFF222222.toInt(), Palette.DARK.function)
        for (p in listOf(Palette.DARK, Palette.LIGHT)) {
            assertTrue("letters on their tiles", contrast(p.glyph, p.key) >= 4.5)
            assertTrue("icons on the darker tiles", contrast(p.glyph, p.function) >= 4.5)
            assertTrue("the language on the space bar", contrast(p.spaceLabel, p.space) >= 3.0)
            assertTrue("a pressed tile shows", contrast(p.pressed, p.key) >= 1.2)
        }
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
    fun enterIsADarkTileAsLabans() {
        val v = view()
        val bmp = draw(v)
        // The Enter key: the last key of the bottom row, 1.5 keys wide; its tile around the icon.
        val cx = (1080 - 1.5f * 108 / 2).toInt()
        val cy = 2195 - 1420
        assertEquals(Palette.DARK.function, bmp.getPixel(cx - (16 * dp).toInt(), cy - (12 * dp).toInt()))
        // Its icon in the letters' white.
        val r = (10 * dp).toInt()
        assertTrue((cx - r..cx + r).any { x -> (cy - r..cy + r).any { y -> bmp.getPixel(x, y) == Palette.DARK.glyph } })
    }

    @Test
    fun shiftFillsWhenOnAndCapsLockIsItsOwn() {
        fun centre(shift: Shift): Int {
            val v = view { this.shift = shift }
            // The middle of Shift's tile: x 10 to 141, y 1978 to 2107 on the screen.
            return draw(v).getPixel((0.75f * 108).toInt(), 2042 - 1420)
        }
        assertEquals(Palette.DARK.function, centre(Shift.OFF))
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
