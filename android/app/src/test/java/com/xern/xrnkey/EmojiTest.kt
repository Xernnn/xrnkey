package com.xern.xrnkey

import com.xern.xrnkey.engine.Emoji
import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.engine.EmojiKeyword
import com.xern.xrnkey.engine.SkinTones
import com.xern.xrnkey.keyboard.EmojiGrid
import com.xern.xrnkey.keyboard.EmojiTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The emoji index with the strip's keyword table, as the keyboard loads them. */
fun testEmojiIndex(): EmojiIndex = EmojiIndex.load(
    File(System.getProperty("xrnkey.emojiIndex")!!).readBytes(),
    File(System.getProperty("xrnkey.emojiKeywords")!!).readBytes(),
)!!

class EmojiTest {
    private fun index() = EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!

    @Test
    fun theIndexComesThroughTheBridgeWhole() = index().use { idx ->
        assertEquals(1914, idx.all.size)
        assertEquals(Emoji("😀", "Smileys & Emotion", "mặt cười toe toét", false), idx.all.first())
        assertTrue(idx.all.first { it.text == "👍" }.skinTones)
        assertEquals("😀", idx.search("cuoi toe toet").first())
        assertTrue("🎂" in idx.suggest(listOf("chúc", "mừng", "sinh", "nhật")))
    }

    @Test
    fun theStripsKeywordTableComesThroughTheBridge() = testEmojiIndex().use { idx ->
        assertEquals(EmojiKeyword(listOf("🍊"), replace = true, words = 1), idx.keyword(listOf("cam")))
        assertEquals(null, idx.keyword(listOf("khoc")))
        assertEquals(EmojiKeyword(listOf("😢", "😭"), replace = false, words = 1), idx.keyword(listOf("tôi", "khóc")))
        assertEquals(4, idx.keyword(listOf("chuc", "mung", "sinh", "nhat"))!!.words)
        assertEquals(listOf("🎂"), idx.keyword(listOf("hpbd"), max = 1)!!.emoji)
        assertEquals("😂", idx.search("haha").first())
        // Without the table the strip has no keywords; the panel's search still has CLDR's.
        index().use { plain ->
            assertEquals(null, plain.keyword(listOf("cam")))
            assertEquals("😀", plain.search("cuoi toe toet").first())
        }
    }

    @Test
    fun skinTonesGoAfterTheFirstCharacter() {
        val v = SkinTones.variants("👍")
        assertEquals(6, v.size)
        assertEquals("👍", v[0])
        assertEquals("👍🏻", v[1])
        // A sequence keeps the rest after the tone; a variation selector there goes.
        assertEquals("🧑🏽‍💻", SkinTones.variants("🧑‍💻")[3])
        assertEquals("✌🏿", SkinTones.variants("✌️")[5])
    }

    @Test
    fun theGridHasRecentsThenSixCategoriesEachAPage() = index().use { idx ->
        val pages = EmojiGrid.pages(EmojiTab.EMOJI, idx.all, listOf("🎂", "😀"), mapOf("👍" to "👍🏽"))
        assertEquals(7, pages.size)
        assertEquals(listOf("🎂", "😀"), pages[EmojiGrid.RECENTS].cells.map { it.text })
        assertEquals("😀", pages[EmojiGrid.SMILEYS].cells[0].text)
        // Unicode's nine groups, every emoji once, in the six categories.
        assertEquals(idx.all.size, pages.drop(1).sumOf { it.cells.size })
        assertTrue(pages[EmojiGrid.SMILEYS].cells.any { it.text == "👍🏽" })  // the chosen tone
        assertTrue(pages.last().cells.any { it.text == "🏁" })  // flags go with objects
        // No recents: an empty first page, and the tab opens on smileys.
        assertEquals(0, EmojiGrid.pages(EmojiTab.EMOJI, idx.all, emptyList())[0].cells.size)
        assertEquals(EmojiGrid.SMILEYS, EmojiGrid.firstPage(EmojiTab.EMOJI, emptyList()))
        assertEquals(EmojiGrid.RECENTS, EmojiGrid.firstPage(EmojiTab.EMOJI, listOf("🎂")))
        val emoticons = EmojiGrid.pages(EmojiTab.EMOTICONS, idx.all, emptyList())
        assertEquals(4, EmojiGrid.columns(EmojiTab.EMOTICONS))
        assertTrue(emoticons.flatMap { it.cells }.any { it.text == ":v" })
        assertEquals(60, emoticons.sumOf { it.cells.size })
        val symbols = EmojiGrid.pages(EmojiTab.SYMBOLS, idx.all, emptyList())
        assertEquals(listOf("→", "±", "₫", "©", "★", "♪"), symbols.map { it.sample })
        assertTrue(symbols[2].cells.any { it.text == "₫" })
    }
}
