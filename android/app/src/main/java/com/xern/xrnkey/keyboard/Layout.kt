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

    /** The 12-key pad's kana keys: a tap types [Key.text], a flick one of [Key.flicks]. */
    KANA,

    /** The 12-key pad's ゛゜小: the last kana in its next form (か が, は ば ぱ, つ っ づ). */
    KANA_MARK,

    /** The 12-key pad's ↺: the last kana a step back along its key's toggle. */
    UNDO,

    /** The 12-key pad's ◀ and ▶: the cursor a character left or right. */
    LEFT, RIGHT,

    /** The 12-key pad's 🌐: the keyboard and language menu, as holding the comma opens it. */
    GLOBE,
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
    /** A kana key's flicks: what a slide left, up, right or down types ("" for none). */
    val flicks: List<String> = emptyList(),
    /** A kana key's toggle: what each tap of it in turn types, from [text] on (Flick.TOGGLE_MILLIS). */
    val toggle: String = "",
) {
    /** What holding it and letting go types, if anything. */
    val hold: String? get() = variants.firstOrNull()

    /** What a kana key types for a direction ([Flick.TAP], [Flick.LEFT] ...): "" for none. */
    fun flick(direction: Int): String = if (direction == Flick.TAP) text else flicks.getOrElse(direction - 1) { "" }
}

data class Row(val keys: List<Key>, /** Empty space at each end, in letter keys. */ val inset: Float = 0f) {
    val width: Float get() = keys.sumOf { it.width.toDouble() }.toFloat() + 2 * inset
}

enum class Page {
    LETTERS, SYMBOLS, MORE_SYMBOLS,

    /** The symbol pages' number pad: digits in a grid with the strip above, for any field. */
    NUMPAD,

    /** The pads, for number, phone and date fields: no strip, big keys. */
    NUMBER, PHONE, DATETIME,

    /** The Japanese 12-key pad (flick input): kana in a 3 by 4 grid, function keys either side. */
    KANA,

    /** The 12-key pad's numbers: digits in its grid, each flicked for symbols, as Gboard's. */
    KANA_NUMBERS;

    val isPad: Boolean get() = this == NUMBER || this == PHONE || this == DATETIME

    /** A page of the 12-key pad: its tiles, flicks and side columns. */
    val isFlick: Boolean get() = this == KANA || this == KANA_NUMBERS
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

    /** The vowels romaji writes long with a macron (Hepburn: Tōkyō). */
    private val MACRON = mapOf('a' to "ā", 'i' to "ī", 'u' to "ū", 'e' to "ē", 'o' to "ō")

    /**
     * What holding a letter offers on the Japanese keyboard, the first under the finger: none of
     * Vietnamese's marks, but what Japanese text uses of the Latin letters: a vowel with its
     * macron (ā, as romaji writes a long vowel), the letter full-width (ａ), then the capital (a:
     * ā ａ A; k: ｋ K). [upper]: as [forms], capitals, and the small letter last.
     */
    fun latinForms(c: Char, upper: Boolean = false): List<String> {
        val lower = c.lowercaseChar()
        val forms = listOfNotNull(MACRON[lower], (lower + 0xFEE0).toString())
        return if (upper) forms.map { it.uppercase() } + lower.toString() else forms + lower.uppercase()
    }

    /**
     * What holding the full stop offers: the common punctuation, the comma under the finger (as
     * on the keyboard the owner came from).
     */
    val PUNCTUATION = ", ? ! ' \" : - ; ( ) / @ & % + #".split(' ')

    /** The same on the Japanese keyboard, whose full stop is 。: Japanese punctuation, 、 first. */
    val KANA_PUNCTUATION = "、 ？ ！ ・ ー 「 」 … ～ ： （ ）".split(' ')

    /**
     * The number row's second page: ten emoji, the ones Laban Key's number row has by default.
     * The emoticon tab keeps the text emoticons.
     */
    val EMOTICON_ROW: List<String> = listOf("😂", "😊", "😍", "😫", "😢", "😞", "😨", "😁", "👌", "👍")

    /** Letter keys; on the Japanese keyboard ([kana]) a held letter gives [latinForms]. */
    private fun chars(s: String, shift: Shift = Shift.OFF, digits: Boolean = false, kana: Boolean = false) =
        s.mapIndexed { i, c ->
            val up = shift != Shift.OFF
            val label = if (up) c.uppercase() else c.toString()
            val forms = if (!c.isLetter()) emptyList() else if (kana) latinForms(c, up) else forms(c, up)
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

    private fun bottom(switch: Key, kana: Boolean) = Row(
        listOf(
            switch,
            if (kana) Key(KeyKind.COMMA, "、", text = "、") else Key(KeyKind.COMMA, ",", text = ","),
            Key(KeyKind.SPACE, width = 5f, text = " "),
            if (kana) Key(KeyKind.PERIOD, "。", text = "。", variants = KANA_PUNCTUATION)
            else Key(KeyKind.PERIOD, ".", text = ".", variants = PUNCTUATION),
            Key(KeyKind.ENTER, width = 1.5f),
        ),
    )

    /**
     * A symbol page's bottom row: the way back, comma, the number pad, a narrower space, two
     * symbols of the page's own (where the letters have only the full stop), Enter.
     */
    private fun symbolBottom(home: Page, own: List<Key>) = Row(
        listOf(
            Key(KeyKind.LETTERS, backLabel(home), width = 1.5f),
            Key(KeyKind.COMMA, ",", text = ","),
            Key(KeyKind.NUMPAD, "1234"),
            Key(KeyKind.SPACE, width = 3f, text = " "),
        ) + own + Key(KeyKind.ENTER, width = 1.5f),
    )

    /** The symbol pages' way back: to the field's pad, the 12-key pad, or the letters. */
    private fun backLabel(home: Page) = when {
        home.isPad -> "123"
        home == Page.KANA -> "あいう"
        else -> "ABC"
    }

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
     * is the page the symbols' way back leads to (the letters, or the field's pad). On the
     * Japanese keyboard ([kana]) the letters' comma and full stop are 、 and 。, and a held letter
     * gives [latinForms], not Vietnamese's marks.
     */
    fun rows(
        page: Page,
        shift: Shift = Shift.OFF,
        numberRow: Boolean = true,
        holdDigits: Boolean = true,
        pad: Pad = Pad(),
        home: Page = Page.LETTERS,
        emoticons: Boolean = false,
        kana: Boolean = false,
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
            add(Row(chars("qwertyuiop", shift, digits = holdDigits && !numberRow, kana = kana)))
            add(Row(chars("asdfghjkl", shift, kana = kana), inset = 0.5f))
            add(
                Row(
                    listOf(Key(KeyKind.SHIFT, width = 1.5f)) + chars("zxcvbnm", shift, kana = kana) +
                        Key(KeyKind.BACKSPACE, width = 1.5f),
                ),
            )
            add(bottom(Key(KeyKind.SYMBOLS, "?123", width = 1.5f), kana))
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
        Page.KANA -> Flick.rows
        Page.KANA_NUMBERS -> Flick.numberRows
        // , 1 2 3 - / ( 4 5 6 . / ) 7 8 9 ⌫ / ABC ?123 0 space ↵, two letter keys a cell.
        Page.NUMPAD -> listOf(
            Row(listOf(cell(","), cell("1"), cell("2"), cell("3"), cell("-"))),
            Row(listOf(cell("("), cell("4"), cell("5"), cell("6"), cell("."))),
            Row(listOf(cell(")"), cell("7"), cell("8"), cell("9"), Key(KeyKind.BACKSPACE, width = 2f))),
            Row(
                listOf(
                    Key(KeyKind.LETTERS, backLabel(home), width = 2f),
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

/**
 * The Japanese 12-key pad, as Gboard's (android/docs/feature-spec.md, "The 12-key pad"): あ か さ /
 * た な は / ま や ら / ゛゜小 わ 、。?! in the middle, ↺ ◀ ?123 🌐 down the left and ⌫ ▶ space
 * Enter down the right. A tap types a key's あ-column kana, a slide left, up, right or down its
 * い, う, え or お one (か: き く け こ); tapped again in time, the same key steps along its toggle
 * (あ い う え お ぁ ぃ ぅ ぇ ぉ).
 */
object Flick {
    /** The directions: a tap, then the four flicks, as [Key.flicks] lists them. */
    const val TAP = 0
    const val LEFT = 1
    const val UP = 2
    const val RIGHT = 3
    const val DOWN = 4

    /** How far a finger slides, in dp, before a tap is a flick: a wobble is still a tap. */
    const val THRESHOLD_DP = 24f

    /** How soon the same key's next tap is a toggle step, not a new kana. */
    const val TOGGLE_MILLIS = 1000L

    /** The side columns' and the kana keys' widths, in letter keys: 1.7 + 3 × 2.2 + 1.7 = 10. */
    private const val SIDE = 1.7f
    private const val WIDE = 2.2f

    /**
     * A kana key: [flicks] its left, up, right and down kana (a space where it has none), and its
     * toggle the tap's kana, then the flicks', then [more] (the small ones).
     */
    private fun kana(label: String, flicks: String, more: String = "", text: String = label) = Key(
        KeyKind.KANA, label, WIDE, text,
        flicks = flicks.map { if (it == ' ') "" else it.toString() },
        toggle = text + flicks.filter { it != ' ' } + more,
    )

    private fun side(kind: KeyKind, label: String = "") = Key(kind, label, SIDE)

    /** The kana keys, in reading order. や and わ have brackets and ー where the columns have gaps. */
    val keys: List<Key> = listOf(
        kana("あ", "いうえお", more = "ぁぃぅぇぉ"),
        kana("か", "きくけこ"),
        kana("さ", "しすせそ"),
        kana("た", "ちつてと", more = "っ"),
        kana("な", "にぬねの"),
        kana("は", "ひふへほ"),
        kana("ま", "みむめも"),
        // や: （ ゆ ） よ, but its toggle is や ゆ よ and the small ones; わ's ends with ー.
        kana("や", "（ゆ）よ").copy(toggle = "やゆよゃゅょ"),
        kana("ら", "りるれろ"),
        kana("わ", "をんー ").copy(toggle = "わをんゎー"),
        kana("、。?!", "。？！ ", text = "、"),
    )

    val rows: List<Row> = listOf(
        Row(listOf(side(KeyKind.UNDO)) + keys.subList(0, 3) + side(KeyKind.BACKSPACE)),
        Row(listOf(side(KeyKind.LEFT)) + keys.subList(3, 6) + side(KeyKind.RIGHT)),
        Row(listOf(side(KeyKind.SYMBOLS, "?123")) + keys.subList(6, 9) + Key(KeyKind.SPACE, width = SIDE, text = " ")),
        Row(listOf(side(KeyKind.GLOBE), Key(KeyKind.KANA_MARK, "゛゜小", WIDE)) + keys.subList(9, 11) + side(KeyKind.ENTER)),
    )

    /**
     * The number pad's keys, as Gboard's (Google Japanese Input's 12-key numbers): a tap types the
     * digit, a flick left, up or right a symbol (1: ☆ ♪ →, 2: ¥ $ €, 3: % ° #, 4: ○ * ・,
     * 5: + × ÷, 6: < = >, 7: 「 」 :, 8: 〒 々 〆, 9: ^ | \, 0: ~ …), and the last row's brackets
     * and ., - / beside the 0. No toggle: a digit tapped twice is two digits.
     */
    private fun number(label: String, flicks: List<String>, text: String = label) = Key(
        KeyKind.KANA, label, WIDE, text, flicks = flicks + List(4 - flicks.size) { "" },
    )

    val numberKeys: List<Key> = listOf(
        number("1", listOf("☆", "♪", "→")),
        number("2", listOf("¥", "$", "€")),
        number("3", listOf("%", "°", "#")),
        number("4", listOf("○", "*", "・")),
        number("5", listOf("+", "×", "÷")),
        number("6", listOf("<", "=", ">")),
        number("7", listOf("「", "」", ":")),
        number("8", listOf("〒", "々", "〆")),
        number("9", listOf("^", "|", "\\")),
        number("()[]", listOf(")", "[", "]"), text = "("),
        number("0", listOf("~", "…")),
        number(".,-/", listOf(",", "-", "/"), text = "."),
    )

    /**
     * The number pad: its digits where the kana are, ◀ ▶ ⌫ space Enter and 🌐 where they are on
     * the kana pad, あいう back to the kana where ?123 was, and 記号 (the symbols' pages) where ↺ was.
     */
    val numberRows: List<Row> = listOf(
        Row(listOf(side(KeyKind.SYMBOLS, "記号")) + numberKeys.subList(0, 3) + side(KeyKind.BACKSPACE)),
        Row(listOf(side(KeyKind.LEFT)) + numberKeys.subList(3, 6) + side(KeyKind.RIGHT)),
        Row(listOf(side(KeyKind.LETTERS, "あいう")) + numberKeys.subList(6, 9) + Key(KeyKind.SPACE, width = SIDE, text = " ")),
        Row(listOf(side(KeyKind.GLOBE)) + numberKeys.subList(9, 12) + side(KeyKind.ENTER)),
    )

    /** The toggle a kana is on (each is on one), for ↺: null for one no key types (が). */
    fun toggleOf(c: Char): String? = keys.firstOrNull { c in it.toggle }?.toggle

    /**
     * Where a finger [dx], [dy] from where it went down points: a tap within [threshold], else
     * the way it went furthest.
     */
    fun direction(dx: Float, dy: Float, threshold: Float): Int = when {
        dx * dx + dy * dy < threshold * threshold -> TAP
        Math.abs(dx) >= Math.abs(dy) -> if (dx < 0) LEFT else RIGHT
        else -> if (dy < 0) UP else DOWN
    }
}

/** The strip's other face: the tool row (no mic: the keyboard is fully offline). */
/** The tool row's buttons, left to right. HIDE (≡) folds the row away to the bare strip. */
enum class Tool { HIDE, SETTINGS, CLIPBOARD, CURSOR, EMOJI }

/** What the strip above the keys shows. */
sealed interface Strip {
    data object Tools : Strip

    /** Up to three candidates, the best in the centre. */
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
        /**
         * Emoji in [ROW] even places across the strip instead of the three slots, the best on the
         * right, as Laban Key fills the strip when it has four emoji or more and no word to offer
         * ("haha", "chúc mừng sinh nhật "). Null: an empty place. Empty: the three slots.
         */
        val row: List<String?> = emptyList(),
        /** The centre is the candidate the field shows (Japanese, after Space): on the accent. */
        val highlight: Boolean = false,
        /** How many of [extra] the panel shows at most. */
        val most: Int = MorePanelView.MOST,
        /** Its words get the panel's remove marks (not the Japanese keyboard's). */
        val removable: Boolean = true,
        /**
         * A Japanese conversion's phrases (after Space): the strip's sides are ⇤ and ⇥, which
         * shrink and stretch the focused phrase, in place of ≡ and the emoji button.
         */
        val resize: Boolean = false,
    ) : Strip {
        companion object {
            /** The places of a [row]. */
            const val ROW = 5
        }
    }

    /** Emoji search: the query typed so far and the emoji it finds. */
    data class Search(val query: String, val results: List<String>) : Strip
}

/** What Enter does in this field, drawn as its icon. */
enum class EnterAction { NEWLINE, SEND, SEARCH, GO, NEXT, DONE }
