package com.xern.xrnkey

import com.xern.xrnkey.keyboard.Emoticons
import com.xern.xrnkey.keyboard.KeyKind
import com.xern.xrnkey.keyboard.Layouts
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.PopupGrid
import com.xern.xrnkey.keyboard.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class LayoutTest {
    @Test
    fun everyRowIsTenKeysWide() {
        for (page in Page.entries) for (row in Layouts.rows(page)) assertEquals(10f, row.width, 0.001f)
    }

    @Test
    fun lettersHaveTheNumberRowOnTopWhenItIsOn() {
        val rows = Layouts.rows(Page.LETTERS)
        assertEquals(5, rows.size)
        assertEquals("1234567890", rows[0].keys.joinToString("") { it.label })
        assertEquals("qwertyuiop", rows[1].keys.joinToString("") { it.label })
        assertEquals(4, Layouts.rows(Page.LETTERS, numberRow = false).size)
    }

    /**
     * Holding q typed 1 while the number row showed the 1 right above it; the keyboard the owner
     * came from gives Q there, and the digit only where there is no number row.
     */
    @Test
    fun holdingATopRowLetterGivesItsDigitOnlyWithoutTheNumberRow() {
        val withRow = Layouts.rows(Page.LETTERS)[1].keys
        assertEquals("Q", withRow.first().hold)
        assertEquals("P", withRow.last().hold)
        assertTrue(withRow.all { k -> k.variants.none { it.single().isDigit() } })
        val noRow = Layouts.rows(Page.LETTERS, numberRow = false)[0].keys
        assertEquals("1", noRow.first().hold)
        assertEquals("0", noRow.last().hold)
        assertEquals(listOf("3", "è", "é"), noRow[2].variants.take(3))
        assertEquals("Q", Layouts.rows(Page.LETTERS, numberRow = false, holdDigits = false)[0].keys.first().hold)
    }

    @Test
    fun theBottomRowIsSymbolsCommaSpacePeriodEnter() {
        val bottom = Layouts.rows(Page.LETTERS).last().keys
        assertEquals(
            listOf(KeyKind.SYMBOLS, KeyKind.COMMA, KeyKind.SPACE, KeyKind.PERIOD, KeyKind.ENTER),
            bottom.map { it.kind },
        )
        assertEquals("?123", bottom[0].label)
        assertEquals(5f, bottom[2].width)
    }

    @Test
    fun shiftCapitalisesTheLettersOnly() {
        val rows = Layouts.rows(Page.LETTERS, Shift.ONCE)
        assertEquals("QWERTYUIOP", rows[1].keys.joinToString("") { it.label })
        assertEquals("1234567890", rows[0].keys.joinToString("") { it.label })
    }

    /** A row as its keys show, symbols as themselves and the other keys by kind. */
    private fun shown(row: com.xern.xrnkey.keyboard.Row): String = row.keys.joinToString(" ") {
        when (it.kind) {
            KeyKind.CHAR, KeyKind.COMMA, KeyKind.PERIOD, KeyKind.SYMBOLS, KeyKind.MORE_SYMBOLS, KeyKind.LETTERS -> it.label
            KeyKind.NUMPAD -> "[1234]"
            KeyKind.SPACE -> "[space ${it.width.toInt()}]"
            KeyKind.BACKSPACE -> "[del]"
            KeyKind.ENTER -> "[enter]"
            else -> "[${it.kind}]"
        }
    }

    /** The symbol pages, key for key as the owner's reference keyboard has them, number row on. */
    @Test
    fun theSymbolPagesAreLaidOutAsTheReferences() {
        assertEquals(
            listOf(
                "1 2 3 4 5 6 7 8 9 0",
                "~ ` | • √ π ÷ × ¶ ∆",
                "@ # $ % & - + ( ) /",
                "=\\< * \" ' : ; ! ? [del]",
                "ABC , [1234] [space 3] _ . [enter]",
            ),
            Layouts.rows(Page.SYMBOLS).map(::shown),
        )
        assertEquals(
            listOf(
                "1 2 3 4 5 6 7 8 9 0",
                "~ ` | • √ π ÷ × ¶ ∆",
                "£ ¢ € ¥ ^ ° = { } …",
                "?123 \\ © ® ™ ℅ [ ] [del]",
                "ABC , [1234] [space 3] < > [enter]",
            ),
            Layouts.rows(Page.MORE_SYMBOLS).map(::shown),
        )
        // The way keys and their widths: =\< and ?123 a key and a half, as are ABC and Enter.
        val one = Layouts.rows(Page.SYMBOLS)
        assertEquals(listOf(1.5f, 1f, 1f, 3f, 1f, 1f, 1.5f), one.last().keys.map { it.width })
        assertEquals(1.5f, one[3].keys.first().width)
        assertEquals(KeyKind.MORE_SYMBOLS, one[3].keys.first().kind)
        assertEquals(KeyKind.SYMBOLS, Layouts.rows(Page.MORE_SYMBOLS)[3].keys.first().kind)
        // In a number field the way back is to its pad.
        assertEquals("123", Layouts.rows(Page.SYMBOLS, home = Page.NUMBER).last().keys.first().label)
    }

    /** Without the number row the pages have the letters' four rows: page 1 keeps its digits, page 2 its shared row. */
    @Test
    fun withoutTheNumberRowTheSymbolPagesKeepFourRows() {
        assertEquals(
            listOf("1 2 3 4 5 6 7 8 9 0", "@ # $ % & - + ( ) /"),
            Layouts.rows(Page.SYMBOLS, numberRow = false).take(2).map(::shown),
        )
        assertEquals(4, Layouts.rows(Page.SYMBOLS, numberRow = false).size)
        assertEquals(
            listOf("~ ` | • √ π ÷ × ¶ ∆", "£ ¢ € ¥ ^ ° = { } …"),
            Layouts.rows(Page.MORE_SYMBOLS, numberRow = false).take(2).map(::shown),
        )
        assertEquals(4, Layouts.rows(Page.MORE_SYMBOLS, numberRow = false).size)
    }

    /** The 1234 key's pad: five columns of two keys' width, four rows. */
    @Test
    fun theNumberPadIsAFiveColumnGrid() {
        assertEquals(
            listOf(
                ", 1 2 3 -",
                "( 4 5 6 .",
                ") 7 8 9 [del]",
                "ABC ?123 0 [space 2] [enter]",
            ),
            Layouts.rows(Page.NUMPAD).map(::shown),
        )
        assertTrue(Layouts.rows(Page.NUMPAD).all { r -> r.keys.all { it.width == 2f } })
        assertEquals(Layouts.rows(Page.NUMPAD), Layouts.rows(Page.NUMPAD, numberRow = false))
        assertFalse(Page.NUMPAD.isPad)  // it keeps the strip, unlike a field's pad
        assertEquals(KeyKind.NUMPAD, Layouts.rows(Page.SYMBOLS).last().keys[2].kind)
        assertEquals(KeyKind.NUMPAD, Layouts.rows(Page.MORE_SYMBOLS).last().keys[2].kind)
    }

    private fun symbolKey(page: Page, label: String) = Layouts.rows(page).flatMap { it.keys }.first { it.label == label }

    /**
     * Holding a symbol: ¡ on !, ’ first on ', Π on π, nothing on | and _ (as on the reference);
     * ₫, which page 1 no longer shows, first on $.
     */
    @Test
    fun holdingASymbolOffersItsRelatives() {
        assertEquals(listOf("¡"), symbolKey(Page.SYMBOLS, "!").variants)
        assertEquals("’", symbolKey(Page.SYMBOLS, "'").hold)
        assertEquals(setOf("‘", "’", "‚", "‛"), symbolKey(Page.SYMBOLS, "'").variants.toSet())
        assertEquals("Π", symbolKey(Page.SYMBOLS, "π").hold)
        assertEquals(emptyList<String>(), symbolKey(Page.SYMBOLS, "|").variants)
        assertEquals(emptyList<String>(), symbolKey(Page.SYMBOLS, "_").variants)
        assertEquals("₫", symbolKey(Page.SYMBOLS, "$").hold)
        assertEquals("¿", symbolKey(Page.SYMBOLS, "?").hold)
        assertEquals(listOf("…"), Layouts.rows(Page.SYMBOLS).last().keys.single { it.kind == KeyKind.PERIOD }.variants)
        assertEquals("≤", symbolKey(Page.MORE_SYMBOLS, "<").hold)
        // Digits hold nothing, on every page.
        for (page in listOf(Page.LETTERS, Page.SYMBOLS, Page.MORE_SYMBOLS, Page.NUMPAD)) {
            assertTrue(Layouts.rows(page).flatMap { it.keys }.filter { it.label.singleOrNull()?.isDigit() == true }.all { it.variants.isEmpty() })
        }
        // Every symbol of both pages can be reached, ₫ included.
        val reachable = listOf(Page.SYMBOLS, Page.MORE_SYMBOLS).flatMap { Layouts.rows(it) }.flatMap { it.keys }.flatMap { listOf(it.label) + it.variants }
        assertTrue("₫" in reachable)
    }

    private fun held(c: Char, shift: Shift = Shift.OFF): List<String> =
        Layouts.rows(Page.LETTERS, shift).flatMap { it.keys }.single { it.label.equals(c.toString(), ignoreCase = true) && it.kind == KeyKind.CHAR }.variants

    private fun base(s: String): Char = Normalizer.normalize(s, Normalizer.Form.NFD)[0]

    /**
     * Holding a vowel offers every toned form of it and of its marked letters, then the capital:
     * a and o 18, e and u 12, i and y 6, as on the keyboard the owner came from. The grave form
     * is first (under the finger).
     */
    @Test
    fun holdingAVowelOffersEveryTonedFormOfItAndItsMarkedLetters() {
        assertEquals("à á ả ã ạ ă ằ ắ ẳ ẵ ặ â ầ ấ ẩ ẫ ậ A".split(' '), held('a'))
        assertEquals("ò ó ỏ õ ọ ô ồ ố ổ ỗ ộ ơ ờ ớ ở ỡ ợ O".split(' '), held('o'))
        assertEquals("è é ẻ ẽ ẹ ê ề ế ể ễ ệ E".split(' '), held('e'))
        assertEquals("ù ú ủ ũ ụ ư ừ ứ ử ữ ự U".split(' '), held('u'))
        assertEquals("ì í ỉ ĩ ị I".split(' '), held('i'))
        assertEquals("ỳ ý ỷ ỹ ỵ Y".split(' '), held('y'))
        for (v in "aeiouy") {
            val forms = held(v)
            assertEquals(forms.size, forms.toSet().size)
            assertTrue(forms.dropLast(1).all { base(it) == v && it.length == 1 })
        }
    }

    @Test
    fun holdingAConsonantGivesItsCapitalAndDAlsoGivesDStroke() {
        assertEquals(listOf("đ", "D"), held('d'))
        for (c in "qwrtpsfghjklzxcvbnm") assertEquals("$c", listOf(c.uppercase()), held(c))
        // With Shift, the forms are capitals and the small letter is last.
        assertEquals(listOf("Đ", "d"), held('d', Shift.ONCE))
        assertEquals(listOf("q"), held('q', Shift.LOCKED))
        val a = held('a', Shift.ONCE)
        assertEquals(listOf("À", "Á"), a.take(2))
        assertTrue("Ặ" in a)
        assertEquals("a", a.last())
    }

    @Test
    fun holdingTheFullStopOffersTheCommonPunctuationTheCommaFirst() {
        val stop = Layouts.rows(Page.LETTERS).last().keys.single { it.kind == KeyKind.PERIOD }
        assertEquals(",", stop.hold)
        assertEquals("@ & % + ; / ( ) \" ' # - : ! , ?".split(' ').toSet(), stop.variants.toSet())
        assertEquals(16, stop.variants.size)
    }

    /** The bottom row of a popup, left to right, for a key from `left` to `right` px of 1080. */
    private fun bottomRow(forms: List<String>, left: Float, right: Float): String {
        val home = PopupGrid.home(forms.size, left, right, 108f, 1080f)
        val cells = PopupGrid.cells(forms.size, home)
        return cells.withIndex().filter { it.value.second == 0 }.sortedBy { it.value.first }.joinToString(" ") { forms[it.index] }
    }

    /**
     * The popup is a grid as near square as it can be, so no form is more than a few keys from
     * the finger: the first in the bottom row over the key, the others outward from it. At the
     * keyboard's edges it fills inward.
     */
    @Test
    fun aPopupIsACompactGridAroundTheKey() {
        assertEquals(listOf(5, 4, 3, 4, 5, 4, 2, 1), listOf(18, 12, 6, 16, 5, 4, 2, 1).map { PopupGrid.columns(it) })
        assertEquals(listOf(4, 3, 2, 4, 1, 1, 1, 1), listOf(18, 12, 6, 16, 5, 4, 2, 1).map { PopupGrid.rows(it) })
        // e (third key of the top row): è over the key, é right of it, ẻ left, ẽ further right.
        assertEquals("ẻ è é ẽ", bottomRow(held('e'), 216f, 324f))
        // a, at the left edge (half a key in): from the key rightward.
        assertEquals("à á ả ã ạ", bottomRow(held('a'), 54f, 162f))
        assertEquals(0, PopupGrid.home(18, 54f, 162f, 108f, 1080f))
        // o, one key from the right edge: the grid stops at the edge, ò stays over the key.
        val o = held('o')
        val home = PopupGrid.home(18, 864f, 972f, 108f, 1080f)
        assertEquals(3, home)
        assertEquals("ọ õ ỏ ò ó", bottomRow(o, 864f, 972f))
        // Every form of a held a is within two columns and three rows of the first.
        val cells = PopupGrid.cells(18, 2)
        assertTrue(cells.all { (c, r) -> Math.abs(c - 2) <= 2 && r <= 3 })
    }

    @Test
    fun theNearestCellIsChosenWhenTheFingerLeavesThePopup() {
        val cells = listOf(floatArrayOf(0f, 0f, 10f, 10f), floatArrayOf(10f, 0f, 20f, 10f), floatArrayOf(0f, -10f, 10f, 0f))
        assertEquals(1, PopupGrid.nearest(cells, 15f, 5f))  // inside
        assertEquals(1, PopupGrid.nearest(cells, 99f, 5f))  // far right
        assertEquals(2, PopupGrid.nearest(cells, 2f, -50f))  // far up
        assertEquals(0, PopupGrid.nearest(cells, -5f, 40f))  // below and left
    }

    @Test
    fun theNumberRowsSecondPageIsTenEmoticons() {
        val row = Layouts.rows(Page.LETTERS, emoticons = true)[0].keys
        assertEquals(10, row.size)
        assertTrue(row.all { it.kind == KeyKind.EMOTICON })
        assertEquals(Emoticons.list.take(10), row.map { it.label })
        assertEquals("qwertyuiop", Layouts.rows(Page.LETTERS, emoticons = true)[1].keys.joinToString("") { it.label })
        // Without the number row there is no second page to show.
        assertEquals("qwertyuiop", Layouts.rows(Page.LETTERS, numberRow = false, emoticons = true)[0].keys.joinToString("") { it.label })
    }
}
