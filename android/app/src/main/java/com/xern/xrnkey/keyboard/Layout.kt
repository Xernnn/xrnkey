package com.xern.xrnkey.keyboard

import java.text.Normalizer

/*
 * The keys, as data: what each row holds, how wide each key is (in letter-key widths, ten to a
 * row) and what holding it gives. KeyboardView draws and hits these; the tests check them.
 * The layout follows the owner's reference keyboard (android/docs/feature-spec.md, "The
 * keyboard"): a number row, QWERTY, Shift and Backspace beside z..m, and a bottom row of ?123,
 * comma, space, full stop and a round Enter. The symbol pages and their number pad are laid out
 * key for key as that keyboard's.
 */

enum class KeyKind {
    CHAR, SHIFT, BACKSPACE, SYMBOLS, MORE_SYMBOLS, LETTERS, SPACE, COMMA, PERIOD, ENTER, BLANK,

    /** A text emoticon on the number row's second page: it goes in whole, never as Telex keys. */
    EMOTICON,

    /** The symbol pages' "1234": their number pad. */
    NUMPAD,
}

data class Key(
    val kind: KeyKind,
    /** What the key shows (a letter in the case it types, a symbol, "?123"). */
    val label: String = "",
    /** Width in letter keys. */
    val width: Float = 1f,
    /** What a tap types, for CHAR, COMMA and PERIOD. */
    val text: String = label,
    /**
     * What holding it offers, in a popup over the key ([PopupGrid]): the first sits under the
     * finger and is typed if the finger lifts without moving, the rest around it to slide to.
     */
    val variants: List<String> = emptyList(),
) {
    /** What holding it and letting go types, if anything. */
    val hold: String? get() = variants.firstOrNull()
}

data class Row(val keys: List<Key>, /** Empty space at each end, in letter keys. */ val inset: Float = 0f) {
    val width: Float get() = keys.sumOf { it.width.toDouble() }.toFloat() + 2 * inset
}

enum class Page {
    LETTERS, SYMBOLS, MORE_SYMBOLS,

    /** The symbol pages' number pad: digits in a grid with the strip above, for any field. */
    NUMPAD,

    /** The pads, for number, phone and date fields: no strip, big keys. */
    NUMBER, PHONE, DATETIME;

    val isPad: Boolean get() = this == NUMBER || this == PHONE || this == DATETIME
}

/** What a number field allows besides digits: its decimal separator, and a minus sign. */
data class Pad(val decimal: String? = null, val signed: Boolean = false)

enum class Shift { OFF, ONCE, LOCKED }

object Layouts {
    private const val DIGITS = "1234567890"

    /** The tones as combining marks, in the order a held vowel offers them: grave, acute, hook, tilde, dot. */
    private const val TONES = "\u0300\u0301\u0309\u0303\u0323"

    /** Each vowel's marked letters: a has ă and â, e has ê, o has ô and ơ, u has ư. */
    private val MARKED = mapOf('a' to "ăâ", 'e' to "ê", 'i' to "", 'o' to "ôơ", 'u' to "ư", 'y' to "")

    private fun toned(c: Char) = TONES.map { Normalizer.normalize("$c$it", Normalizer.Form.NFC) }

    /**
     * What holding a letter offers, the first under the finger: a vowel's toned forms, then each
     * of its marked letters with their toned forms, then the capital (a: à á ả ã ạ, ă ằ ắ ẳ ẵ ặ,
     * â ầ ấ ẩ ẫ ậ, A: 18), so one hold reaches "ặ" or "ữ" without Telex; d gives đ and D; any
     * other letter its capital. [upper]: the letter is shown as a capital (Shift), so are its
     * forms, and the last is the small letter.
     */
    fun forms(c: Char, upper: Boolean = false): List<String> {
        val lower = c.lowercaseChar()
        val marked = MARKED[lower]
        val forms = when {
            marked != null -> toned(lower) + marked.flatMap { listOf(it.toString()) + toned(it) }
            lower == 'd' -> listOf("đ")
            else -> emptyList()
        }
        return if (upper) forms.map { it.uppercase() } + lower.toString() else forms + lower.uppercase()
    }

    /**
     * What holding the full stop offers: the common punctuation, the comma under the finger (as
     * on the keyboard the owner came from).
     */
    val PUNCTUATION = ", ? ! ' \" : - ; ( ) / @ & % + #".split(' ')

    /** The number row's second page: ten of the emoticon tab's own, first ten. */
    val EMOTICON_ROW: List<String> get() = Emoticons.list.take(10)

    private fun chars(s: String, shift: Shift = Shift.OFF, digits: Boolean = false) =
        s.mapIndexed { i, c ->
            val up = shift != Shift.OFF
            val label = if (up) c.uppercase() else c.toString()
            val forms = if (c.isLetter()) forms(c, up) else emptyList()
            Key(KeyKind.CHAR, label, variants = (if (digits) listOf(DIGITS[i].toString()) else emptyList()) + forms)
        }

    /**
     * What holding a symbol offers, the first under the finger. Seen on the owner's reference:
     * ! gives ¡, ' its curly quotes with ’ first, π gives Π and ∏, and | and _ give nothing. The
     * rest are xrnkey's: related symbols, ₫ first on $.
     */
    val SYMBOL_FORMS: Map<String, List<String>> = mapOf(
        "!" to "¡", "'" to "’ ‚ ‘ ‛", "π" to "Π ∏",
        "?" to "¿", "\"" to "“ ” „ « »", "*" to "† ‡ ★", "-" to "– — _ ·", "+" to "±",
        "(" to "[ { <", ")" to "] } >", "$" to "₫ ¢ £ € ¥", "%" to "‰", "#" to "№", "/" to "\\",
        "•" to "· ♪ ♥ ♠ ♦ ♣", "¶" to "§",
        "^" to "↑ ↓ ← →", "°" to "′ ″", "=" to "≠ ≈ ∞", "<" to "≤ « ‹", ">" to "≥ » ›",
    ).mapValues { it.value.split(' ') }

    private fun symbols(s: String) = s.split(' ').map { Key(KeyKind.CHAR, it, variants = SYMBOL_FORMS[it].orEmpty()) }

    private fun bottom(switch: Key) = Row(
        listOf(
            switch,
            Key(KeyKind.COMMA, ",", text = ","),
            Key(KeyKind.SPACE, width = 5f, text = " "),
            Key(KeyKind.PERIOD, ".", text = ".", variants = PUNCTUATION),
            Key(KeyKind.ENTER, width = 1.5f),
        ),
    )

    /**
     * A symbol page's bottom row: the way back, comma, the number pad, a narrower space, two
     * symbols of the page's own (where the letters have only the full stop), Enter.
     */
    private fun symbolBottom(home: Page, own: List<Key>) = Row(
        listOf(
            Key(KeyKind.LETTERS, if (home.isPad) "123" else "ABC", width = 1.5f),
            Key(KeyKind.COMMA, ",", text = ","),
            Key(KeyKind.NUMPAD, "1234"),
            Key(KeyKind.SPACE, width = 3f, text = " "),
        ) + own + Key(KeyKind.ENTER, width = 1.5f),
    )

    /** The full stop on a symbol page: the rest of the punctuation is on the page; holding it gives … */
    private val symbolStop get() = Key(KeyKind.PERIOD, ".", text = ".", variants = listOf("…"))

    /** The row both symbol pages share, under the digits. */
    private const val MATHS = "~ ` | • √ π ÷ × ¶ ∆"

    private fun digit(d: Char) = Key(KeyKind.CHAR, d.toString(), width = 2.5f)
    private fun cell(label: String) = Key(KeyKind.CHAR, label, width = 2f)
    private fun pad(label: String, width: Float = 2.5f) = Key(KeyKind.CHAR, label, width = width)
    private val padBackspace get() = Key(KeyKind.BACKSPACE, width = 2.5f)
    private val padSwitch get() = Key(KeyKind.SYMBOLS, "?123", width = 2.5f)
    private fun blank(width: Float = 2.5f) = Key(KeyKind.BLANK, width = width)

    /**
     * The page a field gets, from its EditorInfo.inputType: a pad for numbers (PINs too),
     * phone numbers and dates and times, the letters for everything else.
     */
    fun pageFor(inputType: Int): Page = when (inputType and android.text.InputType.TYPE_MASK_CLASS) {
        android.text.InputType.TYPE_CLASS_NUMBER -> Page.NUMBER
        android.text.InputType.TYPE_CLASS_PHONE -> Page.PHONE
        android.text.InputType.TYPE_CLASS_DATETIME -> Page.DATETIME
        else -> Page.LETTERS
    }

    /** What a number field's pad offers besides digits; the separator follows [locale]. */
    fun padFor(inputType: Int, locale: java.util.Locale): Pad {
        if (inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_CLASS_NUMBER) return Pad()
        val decimal = inputType and android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
        val signed = inputType and android.text.InputType.TYPE_NUMBER_FLAG_SIGNED != 0
        val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator.toString()
        return Pad(if (decimal) sep else null, signed)
    }

    /**
     * The rows of a page, top to bottom. The number row is there when [numberRow], showing the
     * emoticons instead of the digits when [emoticons] (it is swiped to them). Holding a top-row
     * letter gives its digit first when [holdDigits] and there is no number row. A pad is a
     * dialer's grid of digits with a column beside it; [pad] says what a number pad adds. [home]
     * is the page the symbols' way back leads to (the letters, or the field's pad).
     */
    fun rows(
        page: Page,
        shift: Shift = Shift.OFF,
        numberRow: Boolean = true,
        holdDigits: Boolean = true,
        pad: Pad = Pad(),
        home: Page = Page.LETTERS,
        emoticons: Boolean = false,
    ): List<Row> = when (page) {
        // 1 2 3 ⌫ / 4 5 6 − / 7 8 9 sep / ?123 0 ↵: the minus and the separator only where the
        // field takes them.
        Page.NUMBER -> listOf(
            Row(listOf(digit('1'), digit('2'), digit('3'), padBackspace)),
            Row(listOf(digit('4'), digit('5'), digit('6'), if (pad.signed) pad("-") else blank())),
            Row(listOf(digit('7'), digit('8'), digit('9'), pad.decimal?.let { pad(it) } ?: blank())),
            Row(listOf(padSwitch, digit('0'), Key(KeyKind.ENTER, width = 5f))),
        )
        // The dialer's grid, a column of − and space, and a row of what else a number takes.
        Page.PHONE -> listOf(
            Row(listOf(digit('1'), digit('2'), digit('3'), padBackspace)),
            Row(listOf(digit('4'), digit('5'), digit('6'), pad("-"))),
            Row(listOf(digit('7'), digit('8'), digit('9'), Key(KeyKind.SPACE, width = 2.5f, text = " "))),
            Row(listOf(pad("*"), digit('0'), pad("#"), Key(KeyKind.ENTER, width = 2.5f))),
            Row(listOf(Key(KeyKind.SYMBOLS, "?123", width = 10f / 6)) + "+ ( ) , ;".split(' ').map { pad(it, 10f / 6) }),
        )
        // 1 2 3 ⌫ / 4 5 6 / / 7 8 9 : / ?123 0 − space ↵.
        Page.DATETIME -> listOf(
            Row(listOf(digit('1'), digit('2'), digit('3'), padBackspace)),
            Row(listOf(digit('4'), digit('5'), digit('6'), pad("/"))),
            Row(listOf(digit('7'), digit('8'), digit('9'), pad(":"))),
            Row(listOf(padSwitch, digit('0'), pad("-", 1.25f), Key(KeyKind.SPACE, width = 1.25f, text = " "), Key(KeyKind.ENTER, width = 2.5f))),
        )
        Page.LETTERS -> buildList {
            if (numberRow && emoticons) add(Row(EMOTICON_ROW.map { Key(KeyKind.EMOTICON, it) }))
            else if (numberRow) add(Row(chars(DIGITS)))
            add(Row(chars("qwertyuiop", shift, digits = holdDigits && !numberRow)))
            add(Row(chars("asdfghjkl", shift), inset = 0.5f))
            add(
                Row(
                    listOf(Key(KeyKind.SHIFT, width = 1.5f)) + chars("zxcvbnm", shift) +
                        Key(KeyKind.BACKSPACE, width = 1.5f),
                ),
            )
            add(bottom(Key(KeyKind.SYMBOLS, "?123", width = 1.5f)))
        }
        // With the number row: digits, the shared row, then the page's own. Without it, as many
        // rows as the letters have: page 1 keeps its digits, page 2 its shared row.
        Page.SYMBOLS -> listOfNotNull(
            Row(chars(DIGITS)),
            if (numberRow) Row(symbols(MATHS)) else null,
            Row(symbols("@ # $ % & - + ( ) /")),
            Row(
                listOf(Key(KeyKind.MORE_SYMBOLS, "=\\<", width = 1.5f)) + symbols("* \" ' : ; ! ?") +
                    Key(KeyKind.BACKSPACE, width = 1.5f),
            ),
            symbolBottom(home, symbols("_") + symbolStop),
        )
        Page.MORE_SYMBOLS -> listOfNotNull(
            if (numberRow) Row(chars(DIGITS)) else null,
            Row(symbols(MATHS)),
            Row(symbols("£ ¢ € ¥ ^ ° = { } …")),
            Row(
                listOf(Key(KeyKind.SYMBOLS, "?123", width = 1.5f)) + symbols("\\ © ® ™ ℅ [ ]") +
                    Key(KeyKind.BACKSPACE, width = 1.5f),
            ),
            symbolBottom(home, symbols("< >")),
        )
        // , 1 2 3 - / ( 4 5 6 . / ) 7 8 9 ⌫ / ABC ?123 0 space ↵, two letter keys a cell.
        Page.NUMPAD -> listOf(
            Row(listOf(cell(","), cell("1"), cell("2"), cell("3"), cell("-"))),
            Row(listOf(cell("("), cell("4"), cell("5"), cell("6"), cell("."))),
            Row(listOf(cell(")"), cell("7"), cell("8"), cell("9"), Key(KeyKind.BACKSPACE, width = 2f))),
            Row(
                listOf(
                    Key(KeyKind.LETTERS, if (home.isPad) "123" else "ABC", width = 2f),
                    Key(KeyKind.SYMBOLS, "?123", width = 2f),
                    cell("0"),
                    Key(KeyKind.SPACE, width = 2f, text = " "),
                    Key(KeyKind.ENTER, width = 2f),
                ),
            ),
        )
    }
}

/**
 * Where a held key's forms sit in its popup: up to five in a row, more in a grid as near square
 * as they allow (18 forms: 5 across and 4 up; 12: 4 by 3; 6: 3 by 2), so none is far from the
 * finger. The first form is in the bottom row over the key, under the finger; the others fill
 * that row outward from it, nearest first, then the rows above in the same order. Cells are a
 * key wide.
 */
object PopupGrid {
    fun columns(n: Int): Int = if (n <= 5) maxOf(1, n) else Math.ceil(Math.sqrt(n.toDouble())).toInt()

    fun rows(n: Int): Int = (n + columns(n) - 1) / columns(n)

    /** Each form's cell, as (column, row) with row 0 the bottom, in a grid whose key is over [home]. */
    fun cells(n: Int, home: Int): List<Pair<Int, Int>> {
        val columns = columns(n)
        val order = buildList {
            add(home)
            for (d in 1 until columns) {
                if (home + d < columns) add(home + d)
                if (home - d >= 0) add(home - d)
            }
        }
        return List(n) { order[it % columns] to it / columns }
    }

    /**
     * The column over the key, for a key from [keyLeft] to [keyRight] in a view [width] wide with
     * cells [cell] wide, placed as AOSP's keyboard (and Laban Key, built on it) places it. Room is
     * counted in whole cells from the key's centre, left of it and right of it with the key's own
     * column; the grid takes the middle column (left of the middle when even) where it fits, else
     * as near it as the room allows. A grid that would fill the room to an edge moves one column
     * off it, unless the key is at that edge: a held o, one key from the right edge, has its other
     * forms all to its left ("ọ õ ỏ ó ò"), a held a has them all to its right. The grid's left edge
     * is then `keyLeft - home * cell`.
     */
    fun home(n: Int, keyLeft: Float, keyRight: Float, cell: Float, width: Float): Int {
        val columns = columns(n)
        val centre = (keyLeft + keyRight) / 2
        val maxLeft = (centre / cell + 1e-3f).toInt()
        val maxRight = ((width - centre) / cell + 1e-3f).toInt()
        var left = (columns - 1) / 2
        var right = columns - left
        if (left > maxLeft) {
            left = maxLeft
            right = columns - left
        } else if (right > maxRight + 1) {
            right = maxRight + 1
            left = columns - right
        }
        if (left == maxLeft && left > 0) {
            left--
            right++
        }
        if (right - 1 == maxRight && right > 1) {
            left++
            right--
        }
        return left.coerceIn(0, columns - 1)
    }

    /**
     * The cell nearest a point (inside one, that one), given each cell's left, top, right and
     * bottom: the finger may slide past the popup's edge and still choose its outer forms.
     */
    fun nearest(cells: List<FloatArray>, x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        cells.forEachIndexed { i, c ->
            val dx = maxOf(c[0] - x, 0f, x - c[2])
            val dy = maxOf(c[1] - y, 0f, y - c[3])
            val d = dx * dx + dy * dy
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }
}

/** The strip's other face: the tool row (no mic: the keyboard is fully offline). */
enum class Tool { THEME, SETTINGS, CLIPBOARD, CURSOR, EMOJI }

/** What the strip above the keys shows. */
sealed interface Strip {
    data object Tools : Strip

    /** Up to three candidates; [centre] is what Space would commit. */
    data class Candidates(
        val left: String?,
        val centre: String?,
        val right: String?,
        val more: Boolean = false,
        /** The centre is what Space will commit: drawn in bold. */
        val bold: Boolean = true,
        /** The candidates after these three, best first: the panel slid up from the strip. */
        val extra: List<String> = emptyList(),
        /**
         * How many first characters of each slot (left, centre, right) are already in the field,
         * drawn grey as Laban Key draws them: "bạn" of "bạn bè" while "bạn" is typed, "một" of
         * "một số" after "một ". Missing: none.
         */
        val grey: List<Int> = emptyList(),
    ) : Strip

    /** Emoji search: the query typed so far and the emoji it finds. */
    data class Search(val query: String, val results: List<String>) : Strip
}

/** What Enter does in this field, drawn as its icon. */
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE }
