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
        assertEquals(EmojiKeyword(listOf("😢", "😭"), replace = false, words = 1), idx.keyword(listOf("tôi", "khóc"), max = 2))
        // Up to five, for the row of emoji the strip fills when there are no words.
        assertEquals(listOf("😂", "🤣", "😆", "😄", "😁"), idx.keyword(listOf("haha"), max = 5)!!.emoji)
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
    fun theGridHasRecentsFirstThenEachGroupOnNewRows() = index().use { idx ->
        val rows = EmojiGrid.rows(EmojiTab.EMOJI, idx.all, listOf("🎂", "😀"), mapOf("👍" to "👍🏽"))
        assertEquals(listOf("🎂", "😀"), rows[0].cells.map { it.text })
        assertEquals(0, rows[0].section)
        assertEquals(1, rows[1].section)  // smileys start on a row of their own
        assertEquals("😀", rows[1].cells[0].text)
        assertTrue(rows.all { it.cells.size <= 8 })
        assertEquals(9, rows.maxOf { it.section })  // the nine groups
        assertTrue(rows.flatMap { it.cells }.any { it.text == "👍🏽" })  // the chosen tone
        assertEquals(-1, EmojiGrid.firstRow(EmojiGrid.rows(EmojiTab.EMOJI, idx.all, emptyList()), 0))
        val emoticons = EmojiGrid.rows(EmojiTab.EMOTICONS, idx.all, emptyList())
        assertTrue(emoticons.all { it.cells.size <= 3 } && emoticons.flatMap { it.cells }.any { it.text == ":v" })
        assertTrue(EmojiGrid.rows(EmojiTab.SYMBOLS, idx.all, emptyList()).flatMap { it.cells }.any { it.text == "₫" })
    }
}
