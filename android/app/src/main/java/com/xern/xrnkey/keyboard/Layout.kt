package com.xern.xrnkey.keyboard

/*
 * The keys, as data: what each row holds, how wide each key is (in letter-key widths, ten to a
 * row) and what holding it gives. KeyboardView draws and hits these; the tests check them.
 * The layout follows the owner's reference keyboard (android/docs/feature-spec.md, "The
 * keyboard"): a number row, QWERTY, Shift and Backspace beside z..m, and a bottom row of ?123,
 * comma, space, full stop and a round Enter.
 */

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SYMBOLS, MORE_SYMBOLS, LETTERS, SPACE, COMMA, PERIOD, ENTER, BLANK }

data class Key(
    val kind: KeyKind,
    /** What the key shows (a letter in the case it types, a symbol, "?123"). */
    val label: String = "",
    /** Width in letter keys. */
    val width: Float = 1f,
    /** What a tap types, for CHAR, COMMA and PERIOD. */
    val text: String = label,
    /**
     * What holding it offers: the first is chosen at once (a top-row letter's digit), the rest
     * in a row above the key to slide to (the letter's Vietnamese forms).
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

    /** The pads, for number, phone and date fields: no strip, big keys. */
    NUMBER, PHONE, DATETIME;

    val isPad: Boolean get() = this == NUMBER || this == PHONE || this == DATETIME
}

/** What a number field allows besides digits: its decimal separator, and a minus sign. */
data class Pad(val decimal: String? = null, val signed: Boolean = false)

enum class Shift { OFF, ONCE, LOCKED }

object Layouts {
    private const val DIGITS = "1234567890"

    /** Each letter's Vietnamese forms, as holding it offers them: the marked vowels, then its tones. */
    val VARIANTS = mapOf(
        'a' to "â ă á à ả ã ạ",
        'd' to "đ",
        'e' to "ê é è ẻ ẽ ẹ",
        'i' to "í ì ỉ ĩ ị",
        'o' to "ô ơ ó ò ỏ õ ọ",
        'u' to "ư ú ù ủ ũ ụ",
        'w' to "ư",
        'y' to "ý ỳ ỷ ỹ ỵ",
    ).mapValues { it.value.split(' ') }

    private fun chars(s: String, shift: Shift = Shift.OFF, digits: Boolean = false) =
        s.mapIndexed { i, c ->
            val up = shift != Shift.OFF
            val label = if (up) c.uppercase() else c.toString()
            val forms = VARIANTS[c].orEmpty().map { if (up) it.uppercase() else it }
            Key(KeyKind.CHAR, label, variants = (if (digits) listOf(DIGITS[i].toString()) else emptyList()) + forms)
        }

    private fun symbols(s: String) = s.split(' ').map { Key(KeyKind.CHAR, it) }

    private fun bottom(switch: Key) = Row(
        listOf(
            switch,
            Key(KeyKind.COMMA, ",", text = ","),
            Key(KeyKind.SPACE, width = 5f, text = " "),
            Key(KeyKind.PERIOD, ".", text = "."),
            Key(KeyKind.ENTER, width = 1.5f),
        ),
    )

    private fun digit(d: Char) = Key(KeyKind.CHAR, d.toString(), width = 2.5f)
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
     * The rows of a page, top to bottom. The number row is there when [numberRow]; holding a
     * top-row letter gives its digit when [holdDigits]. A pad is a dialer's grid of digits with
     * a column beside it; [pad] says what a number pad adds. [home] is the page the symbols'
     * way back leads to (the letters, or the field's pad).
     */
    fun rows(
        page: Page,
        shift: Shift = Shift.OFF,
        numberRow: Boolean = true,
        holdDigits: Boolean = true,
        pad: Pad = Pad(),
        home: Page = Page.LETTERS,
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
            if (numberRow) add(Row(chars(DIGITS)))
            add(Row(chars("qwertyuiop", shift, digits = holdDigits)))
            add(Row(chars("asdfghjkl", shift), inset = 0.5f))
            add(
                Row(
                    listOf(Key(KeyKind.SHIFT, width = 1.5f)) + chars("zxcvbnm", shift) +
                        Key(KeyKind.BACKSPACE, width = 1.5f),
                ),
            )
            add(bottom(Key(KeyKind.SYMBOLS, "?123", width = 1.5f)))
        }
        Page.SYMBOLS -> listOf(
            Row(chars(DIGITS)),
            Row(symbols("@ # ₫ _ & - + ( ) /")),
            Row(
                listOf(Key(KeyKind.MORE_SYMBOLS, "=\\<", width = 1.5f)) + symbols("* \" ' : ; ! ?") +
                    Key(KeyKind.BACKSPACE, width = 1.5f),
            ),
            bottom(Key(KeyKind.LETTERS, if (home.isPad) "123" else "ABC", width = 1.5f)),
        )
        Page.MORE_SYMBOLS -> listOf(
            Row(symbols("~ ` | • √ π ÷ × ¶ ∆")),
            Row(symbols("£ € $ ¥ ^ ° = { } \\")),
            Row(
                listOf(Key(KeyKind.SYMBOLS, "?123", width = 1.5f)) + symbols("% © ® ™ ✓ [ ]") +
                    Key(KeyKind.BACKSPACE, width = 1.5f),
            ),
            bottom(Key(KeyKind.LETTERS, if (home.isPad) "123" else "ABC", width = 1.5f)),
        )
    }
}

/** The strip's other face: the tool row (no mic: the keyboard is fully offline). */
enum class Tool { THEME, SETTINGS, CLIPBOARD, CURSOR, EMOJI }

/** What the strip above the keys shows. */
sealed interface Strip {
    data object Tools : Strip

    /** Up to three candidates; [centre] is what Space would commit. */
    data class Candidates(val left: String?, val centre: String?, val right: String?, val more: Boolean = false) : Strip

    /** Emoji search: the query typed so far and the emoji it finds. */
    data class Search(val query: String, val results: List<String>) : Strip
}

/** What Enter does in this field, drawn as its icon. */
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE }
