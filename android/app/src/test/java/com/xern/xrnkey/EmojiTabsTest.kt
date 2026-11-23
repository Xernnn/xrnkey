package com.xern.xrnkey

import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.keyboard.EmojiGrid
import com.xern.xrnkey.keyboard.EmojiTab
import com.xern.xrnkey.keyboard.Emoticons
import com.xern.xrnkey.keyboard.FancyLetters
import com.xern.xrnkey.keyboard.PageIcon
import com.xern.xrnkey.keyboard.Symbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * What the emoji panel's text tabs hold, as Laban Key's do, less its messaging app's stickers:
 * kaomoji, symbols and fancy letters, each tab with its recents first.
 */
class EmojiTabsTest {
    private val textTabs = listOf(EmojiTab.EMOTICONS, EmojiTab.SYMBOLS, EmojiTab.LETTERS)

    @Test
    fun everyTextTabHasRecentsThenItsPagesAndNoPageIsEmpty() {
        for (tab in textTabs) {
            val pages = EmojiGrid.pages(tab, emptyList(), listOf("x"))
            assertEquals(PageIcon.RECENTS, pages[EmojiGrid.RECENTS].icon)
            assertEquals(listOf("x"), pages[EmojiGrid.RECENTS].cells.map { it.text })
            assertEquals(EmojiGrid.textPages(tab).size + 1, pages.size)
            for (p in pages.drop(1)) assertTrue("${tab.name} ${p.name}", p.cells.isNotEmpty() && p.sample.isNotEmpty())
        }
        assertEquals(
            listOf("Classic", "Happy", "Love", "Sad", "Angry", "Surprised", "Hi", "Animals", "Other"),
            Emoticons.pages.map { it.name },
        )
        assertEquals(
            listOf(36, 34, 34, 32, 32, 32, 34, 36, 34),
            Emoticons.pages.map { it.items.size },
        )
        assertEquals(304, Emoticons.list.size)
        // Each kaomoji page is named on the bar by one of its own faces.
        for (p in Emoticons.pages) assertTrue(p.name, p.sample in p.items)
        assertTrue(listOf(":v", "=))", ":))", "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻").all { it in Emoticons.list })

        assertEquals(listOf("♡", "ツ", "A", "⑨", "Σ", "⟨⟩", "→", "★"), Symbols.pages.map { it.sample })
        assertEquals(listOf(48, 56, 64, 80, 72, 64, 64, 80), Symbols.pages.map { it.items.size })
        assertEquals(528, Symbols.list.size)
        assertEquals("₫", Symbols.pages[2].items.first())
        // One character each.
        for (s in Symbols.list) assertEquals(s, 1, s.codePointCount(0, s.length))

        assertEquals(('A'..'Z').map { "$it" } + "0–9", FancyLetters.pages.map { it.name })
        assertEquals(('A'..'Z').map { "$it" } + "0", FancyLetters.pages.map { it.sample })
    }

    @Test
    fun noTabListsTheSameThingTwice() {
        for (tab in textTabs) {
            val all = EmojiGrid.textPages(tab).flatMap { it.items }
            assertEquals(tab.name, emptyList<String>(), all.groupBy { it }.filter { it.value.size > 1 }.keys.toList())
        }
    }

    /** Which letter a fancy one is: its compatibility form (𝓐 → A), or its name (🅰, ᴀ). */
    private fun letterOf(s: String): Char? {
        val plain = Normalizer.normalize(s, Normalizer.Form.NFKC).filter { it.isLetter() }
        if (plain.length == 1 && plain[0] in 'A'..'z') return plain[0].uppercaseChar()
        val name = Character.getName(s.codePointAt(0)) ?: return null
        return Regex("(?:LETTER|CAPITAL) ([A-Z])$").find(name)?.groupValues?.get(1)?.single()
    }

    @Test
    fun everyLetterCellIsItsPagesLetterInOneStyle() {
        for (page in FancyLetters.pages.dropLast(1)) {
            val letter = page.name.single()
            for (s in page.items) {
                val c = s.codePointAt(0)
                assertEquals(s, 1, s.codePointCount(0, s.length))
                assertFalse("$s is a regional indicator", c in 0x1F1E6..0x1F1FF)
                assertTrue("$s is no plain letter", c > 0x7F)
                assertEquals("$s on $letter", letter, letterOf(s))
            }
            // Capital and small: the bold A and a, and so on.
            assertTrue(page.items.any { Character.isUpperCase(it.codePointAt(0)) })
            assertTrue(page.items.any { Character.isLowerCase(it.codePointAt(0)) })
            // Every style but the small capital that X lacks and Android's fonts don't draw for Q.
            assertEquals(letter.toString(), if (letter in "QX") 34 else 35, page.items.size)
        }
        val a = FancyLetters.pages.first().items
        assertEquals(listOf("🅰", "🄰", "🅐", "Ⓐ", "ⓐ", "⒜", "Ａ", "ａ", "𝐀", "𝐚"), a.take(10))
        assertEquals("ᴀ", a.last())
        // Letterlike Symbols fill the maths alphabets' holes, in their places.
        assertEquals("ℬ", FancyLetters.styles('B')[14])
        assertEquals("𝒷", FancyLetters.styles('B')[15])
        assertEquals("ℎ", FancyLetters.styles('H')[11])
        assertEquals("ℭ", FancyLetters.styles('C')[18])
        assertEquals("ℤ", FancyLetters.styles('Z')[22])
        assertTrue("ℯ" in FancyLetters.styles('E') && "ℊ" in FancyLetters.styles('G') && "ℴ" in FancyLetters.styles('O'))
        // The digits: a row of eight styles for each, 0 to 9.
        val digits = FancyLetters.pages.last().items
        assertEquals(80, digits.size)
        digits.forEachIndexed { i, s ->
            assertEquals(s, 1, s.codePointCount(0, s.length))
            assertEquals(s, i / 8, Character.getNumericValue(s.codePointAt(0)))
        }
        assertEquals(listOf("𝟏", "𝟙", "𝟣", "𝟭", "𝟷", "１", "①", "❶"), digits.subList(8, 16))
    }

    /** A character the emoji index lists bare draws as a colour emoji: none is in these tabs. */
    @Test
    fun theTextTabsDrawAsTextNotAsColourEmoji() {
        val emoji = EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!.use { idx ->
            idx.all.map { it.text }.toSet()
        }
        for (tab in textTabs) {
            for (s in EmojiGrid.textPages(tab).flatMap { it.items }) {
                var i = 0
                while (i < s.length) {
                    val c = s.codePointAt(i)
                    val ch = String(Character.toChars(c))
                    assertFalse("$s holds the emoji $ch", ch in emoji)
                    assertFalse("$s holds a regional indicator", c in 0x1F1E6..0x1F1FF)
                    assertFalse("$s asks for emoji", c == 0xFE0F)
                    i += Character.charCount(c)
                }
            }
        }
    }
}
