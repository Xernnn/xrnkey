package com.xern.xrnkey.keyboard

/**
 * The letters A to Z, and the digits, in the styles Unicode encodes: the enclosed and full-width
 * forms, the thirteen alphabets of the Mathematical Alphanumeric Symbols block (bold, italic,
 * script, fraktur, double-struck, sans-serif, monospace…) and small capitals. One page a letter,
 * capital and small, and one for the digits. Worked out from the code points, so nothing is
 * listed by hand.
 *
 * A style without the letter is left out rather than stood in for: there is no small capital X,
 * and Android's fonts don't draw the small capital Q. Regional indicator letters are never used:
 * two side by side make a flag.
 */
object FancyLetters {
    /**
     * A style: where its A and its a are (0: it has none), and the letters the block leaves out
     * because Letterlike Symbols already had them (ℬ ℰ ℱ…), where they are instead.
     */
    private class Style(val upper: Int, val lower: Int, val moved: Map<Char, Int> = emptyMap()) {
        fun of(c: Char): String? {
            moved[c]?.let { return text(it) }
            val base = if (c.isUpperCase()) upper else lower
            return if (base == 0) null else text(base + (c.lowercaseChar() - 'a'))
        }
    }

    /** The enclosed and full-width forms, first on a letter's page: a row of eight. */
    private val enclosed = listOf(
        Style(0x1F170, 0),        // 🅰 negative squared
        Style(0x1F130, 0),        // 🄰 squared
        Style(0x1F150, 0),        // 🅐 negative circled
        Style(0x24B6, 0x24D0),    // Ⓐ ⓐ circled
        Style(0, 0x249C),         // ⒜ parenthesised
        Style(0xFF21, 0xFF41),    // Ａ ａ full width
    )

    /** The maths alphabets, capital and small side by side. */
    private val maths = listOf(
        Style(0x1D400, 0x1D41A),  // bold
        Style(0x1D434, 0x1D44E, mapOf('h' to 0x210E)),  // italic
        Style(0x1D468, 0x1D482),  // bold italic
        Style(
            0x1D49C, 0x1D4B6,     // script
            mapOf(
                'B' to 0x212C, 'E' to 0x2130, 'F' to 0x2131, 'H' to 0x210B, 'I' to 0x2110,
                'L' to 0x2112, 'M' to 0x2133, 'R' to 0x211B, 'e' to 0x212F, 'g' to 0x210A,
                'o' to 0x2134,
            ),
        ),
        Style(0x1D4D0, 0x1D4EA),  // bold script
        Style(
            0x1D504, 0x1D51E,     // fraktur
            mapOf('C' to 0x212D, 'H' to 0x210C, 'I' to 0x2111, 'R' to 0x211C, 'Z' to 0x2128),
        ),
        Style(0x1D56C, 0x1D586),  // bold fraktur
        Style(
            0x1D538, 0x1D552,     // double-struck
            mapOf(
                'C' to 0x2102, 'H' to 0x210D, 'N' to 0x2115, 'P' to 0x2119, 'Q' to 0x211A,
                'R' to 0x211D, 'Z' to 0x2124,
            ),
        ),
        Style(0x1D5A0, 0x1D5BA),  // sans-serif
        Style(0x1D5D4, 0x1D5EE),  // sans-serif bold
        Style(0x1D608, 0x1D622),  // sans-serif italic
        Style(0x1D63C, 0x1D656),  // sans-serif bold italic
        Style(0x1D670, 0x1D68A),  // monospace
    )

    /** Small capitals, A to Z; a space where there is none to use. */
    private const val SMALL_CAPITALS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘ ʀꜱᴛᴜᴠᴡ ʏᴢ"

    /** Every style of one letter, in the page's order. */
    fun styles(letter: Char): List<String> {
        val upper = letter.uppercaseChar()
        val lower = letter.lowercaseChar()
        val small = SMALL_CAPITALS[upper - 'A'].takeIf { it != ' ' }?.toString()
        return enclosed.flatMap { listOfNotNull(it.of(upper), it.of(lower)) } +
            maths.flatMap { listOf(it.of(upper)!!, it.of(lower)!!) } +
            listOfNotNull(small)
    }

    /**
     * Every style of one digit, a row of eight: bold, double-struck, sans-serif, sans-serif bold,
     * monospace, full width, circled and negative circled.
     */
    fun digitStyles(d: Int): List<String> = listOf(
        text(0x1D7CE + d),
        text(0x1D7D8 + d),
        text(0x1D7E2 + d),
        text(0x1D7EC + d),
        text(0x1D7F6 + d),
        text(0xFF10 + d),
        text(if (d == 0) 0x24EA else 0x2460 + d - 1),
        text(if (d == 0) 0x24FF else 0x2776 + d - 1),
    )

    val pages: List<TextPage> =
        ('A'..'Z').map { TextPage("$it", "$it", styles(it)) } +
            TextPage("0–9", "0", (0..9).flatMap { digitStyles(it) })

    private fun text(codePoint: Int) = String(Character.toChars(codePoint))
}
