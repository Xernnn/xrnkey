package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.Key
import com.xern.xrnkey.keyboard.KeyKind
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Layouts
import com.xern.xrnkey.keyboard.Pad
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/** Number, PIN, phone and date fields get a pad, as the system dialer has. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PadTest {
    private val number = InputType.TYPE_CLASS_NUMBER
    private val us = Locale.US
    private val vi = Locale.forLanguageTag("vi-VN")

    @Test
    fun eachKindOfFieldGetsItsPage() {
        assertEquals(Page.LETTERS, Layouts.pageFor(InputType.TYPE_CLASS_TEXT))
        assertEquals(Page.LETTERS, Layouts.pageFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(Page.NUMBER, Layouts.pageFor(number))
        assertEquals(Pad(), Layouts.padFor(number, us))
        assertEquals(Pad(signed = true), Layouts.padFor(number or InputType.TYPE_NUMBER_FLAG_SIGNED, us))
        // The decimal separator follows the locale.
        assertEquals(Pad(decimal = "."), Layouts.padFor(number or InputType.TYPE_NUMBER_FLAG_DECIMAL, us))
        assertEquals(Pad(decimal = ","), Layouts.padFor(number or InputType.TYPE_NUMBER_FLAG_DECIMAL, vi))
        assertEquals(
            Pad(decimal = ".", signed = true),
            Layouts.padFor(number or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, us),
        )
        // A PIN: the number pad, and a password field (no suggestions, nothing learned).
        val pin = number or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertEquals(Page.NUMBER, Layouts.pageFor(pin))
        assertTrue(XrnkeyService.isPassword(EditorInfo().apply { inputType = pin }))
        assertEquals(Page.PHONE, Layouts.pageFor(InputType.TYPE_CLASS_PHONE))
        assertEquals(Page.DATETIME, Layouts.pageFor(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE))
        assertEquals(Pad(), Layouts.padFor(InputType.TYPE_CLASS_PHONE, us))
    }

    private fun labels(rows: List<com.xern.xrnkey.keyboard.Row>) = rows.map { r -> r.keys.map(::name) }

    private fun name(k: Key) = when (k.kind) {
        KeyKind.BACKSPACE -> "⌫"
        KeyKind.ENTER -> "↵"
        KeyKind.SPACE -> "␣"
        KeyKind.BLANK -> ""
        else -> k.label
    }

    @Test
    fun thePadsAreADialersGrid() {
        val plain = Layouts.rows(Page.NUMBER)
        assertEquals(
            listOf(listOf("1", "2", "3", "⌫"), listOf("4", "5", "6", ""), listOf("7", "8", "9", ""), listOf("?123", "0", "↵")),
            labels(plain),
        )
        val full = Layouts.rows(Page.NUMBER, pad = Pad(decimal = ",", signed = true))
        assertEquals(listOf("4", "5", "6", "-"), labels(full)[1])
        assertEquals(listOf("7", "8", "9", ","), labels(full)[2])
        val phone = labels(Layouts.rows(Page.PHONE)).flatten()
        for (c in listOf("+", "*", "#", "(", ")", "-", ",", ";", "␣", "⌫", "↵", "?123")) assertTrue(c, c in phone)
        val date = labels(Layouts.rows(Page.DATETIME)).flatten()
        for (c in listOf("/", ":", "-", "␣", "⌫", "↵", "?123")) assertTrue(c, c in date)
        for (page in listOf(Page.NUMBER, Page.PHONE, Page.DATETIME)) {
            val rows = Layouts.rows(page, pad = Pad(".", true))
            for (row in rows) assertEquals("$page", 10f, row.width, 0.01f)
            for (d in '0'..'9') assertTrue("$page $d", rows.flatMap { it.keys }.any { it.label == d.toString() })
            // Every digit has the same column width as the dialer's.
            assertTrue(rows.flatMap { it.keys }.filter { it.label.length == 1 && it.label[0].isDigit() }.all { it.width == 2.5f })
        }
        // The symbols' way back says where it leads.
        assertEquals("123", Layouts.rows(Page.SYMBOLS, home = Page.NUMBER).last().keys.first().label)
        assertEquals("ABC", Layouts.rows(Page.SYMBOLS).last().keys.first().label)
    }

    private fun keyboard(palette: Palette = Palette.DARK, setup: KeyboardView.() -> Unit = {}): KeyboardView {
        val v = KeyboardView(RuntimeEnvironment.getApplication())
        v.palette = palette
        v.setup()
        v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    @Test
    fun aPadSwitchesToTheSymbolsAndBack() {
        val v = keyboard { home = Page.NUMBER; page = Page.NUMBER }
        val typed = mutableListOf<String>()
        v.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed += text }
        }
        val letters = keyboard()
        assertEquals("a pad is as tall as the letters", letters.measuredHeight, v.measuredHeight)
        v.tap(Key(KeyKind.SYMBOLS, "?123"))
        assertEquals(Page.SYMBOLS, v.page)
        v.tap(Key(KeyKind.SPACE, text = " "))
        assertEquals(Page.NUMBER, v.page)  // a space on the symbols goes back to the pad
        v.tap(Key(KeyKind.SYMBOLS, "?123"))
        v.tap(Key(KeyKind.LETTERS, "123"))
        assertEquals(Page.NUMBER, v.page)
        v.tap(Key(KeyKind.CHAR, "7", width = 2.5f))
        assertEquals(listOf(" ", "7"), typed)
    }

    @Test
    fun aFieldsPadComesUpWithTheKeyboard() {
        val s = Robolectric.setupService(XrnkeyService::class.java)
        val frame = s.onCreateInputView() as android.view.ViewGroup
        val keys = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        for ((type, page) in listOf(
            number or InputType.TYPE_NUMBER_VARIATION_PASSWORD to Page.NUMBER,
            InputType.TYPE_CLASS_PHONE to Page.PHONE,
            InputType.TYPE_CLASS_DATETIME to Page.DATETIME,
            InputType.TYPE_CLASS_TEXT to Page.LETTERS,
        )) {
            s.onStartInputView(EditorInfo().apply { inputType = type }, false)
            assertEquals(page, keys.page)
            assertEquals(page, keys.home)
        }
        s.onStartInputView(EditorInfo().apply { inputType = number or InputType.TYPE_NUMBER_FLAG_DECIMAL }, false)
        assertFalse(keys.pad.decimal.isNullOrEmpty())
        s.onDestroy()
    }

    @Test
    fun theShots() {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        val pads = listOf(
            "number" to { v: KeyboardView -> v.home = Page.NUMBER; v.page = Page.NUMBER },
            "number-signed-decimal" to { v: KeyboardView -> v.home = Page.NUMBER; v.pad = Pad(",", true); v.page = Page.NUMBER },
            "phone" to { v: KeyboardView -> v.home = Page.PHONE; v.page = Page.PHONE },
            "datetime" to { v: KeyboardView -> v.home = Page.DATETIME; v.page = Page.DATETIME },
            "number-symbols" to { v: KeyboardView -> v.home = Page.NUMBER; v.page = Page.SYMBOLS },
        )
        for ((name, set) in pads) for ((theme, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            val v = keyboard(palette) { set(this); enterAction = com.xern.xrnkey.keyboard.EnterAction.DONE }
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "pad-$name-$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
