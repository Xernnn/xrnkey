package com.xern.xrnkey

import com.xern.xrnkey.ime.KanaStrip
import com.xern.xrnkey.ime.KanaTypist
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.JapaneseEmoji
import com.xern.xrnkey.ja.UserHistory
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Japanese strip's emoji: the keyword table built from CLDR's Japanese annotations and the
 * hand list (data/emoji/keywords-ja.tsv), and how the strip offers them while a reading composes.
 */
class JapaneseEmojiTest {
    private val table = JapaneseEmoji.load(File(System.getProperty("xrnkey.emojiJapanese")!!).readBytes())

    /**
     * A reading's best conversion, or the reading itself, is a keyword: わらい (笑い) 😂, ねこ (猫)
     * 🐱, ありがとう 🙏, かお the kaomoji. The conversion's keyword first (あめ: 雨 ☔ before the
     * candy). An association (本 📕) may not take the strip's slot.
     */
    @Test
    fun readingsAndTheirConversionsAreKeywords() {
        assertTrue(table.size > 3000)
        assertEquals("😂", table.of("わらい", "笑い")!!.emoji.first())
        assertEquals("🐱", table.of("ねこ", "猫")!!.emoji.first())
        assertEquals("🙏", table.of("ありがとう", "ありがとう")!!.emoji.first())
        assertEquals("🍣", table.of("すし", "寿司")!!.emoji.first())
        assertEquals("🇯🇵", table.of("にほん", "日本")!!.emoji.first())
        assertTrue(table.of("かお", "顔")!!.emoji.contains("(´・ω・`)"))
        val ame = table.of("あめ", "雨")!!
        assertEquals("☔", ame.emoji.first())
        assertTrue(ame.emoji.toString(), "🍬" in ame.emoji)
        assertTrue(ame.strong)
        assertFalse(table.of("ほん", "本")!!.strong)
        assertTrue(table.of("ねこ", "ねこ")!!.strong)    // CLDR's ネコ, read
        assertNull(table.of("わたし", "私"))
        assertNull(table.of("うえ", "上"))              // left out: an everyday word
        assertTrue(listOf("わらい" to "笑い", "ねこ" to "猫", "かお" to "顔").all { (r, b) -> table.of(r, b)!!.emoji.size <= JapaneseEmoji.MOST })
    }

    private val editor = FakeEditor()
    private val converter = Converter(TestModels.japanese())
    private val typist = KanaTypist(editor, converter).also { it.reset() }
    private val strip = KanaStrip().also { it.emoji = table }

    private fun type(keys: String) = keys.forEach { typist.text(it.toString()) }

    /**
     * The strip's right slot holds the first emoji, the likeliest third going to the panel's
     * start, the other emoji after the panel's first row; a tap puts it in place of the reading, learning
     * nothing and followed by nothing. An association's emoji wait in the panel. Off in the
     * settings, none.
     */
    @Test
    fun theStripOffersAReadingsEmoji() {
        val h = UserHistory()
        converter.history = h
        type("ame")
        val s = strip.make(typist) as Strip.Candidates
        assertEquals("雨", s.centre)
        assertEquals("☔", s.right)
        assertEquals("あめ", s.extra.first())            // the third, now in the panel
        assertEquals(listOf("あめ", "アメ", "🌧️", "🍬", "🍭"), s.extra.take(5))
        assertTrue(strip.isEmoji("☔"))
        assertFalse(strip.isEmoji("雨"))
        typist.pick(strip.pick(2)!!)
        assertEquals("☔", editor.committed.toString())
        assertEquals("", editor.composing)
        assertEquals(0, h.size)
        assertEquals(null, typist.context)
        assertEquals(Strip.Tools, strip.make(typist))
        // 紙 is a keyword of 📜 only as an association: the panel's.
        type("kami")
        val kami = strip.make(typist) as Strip.Candidates
        assertEquals(listOf("神", "紙", "髪"), listOf(kami.left, kami.centre, kami.right))
        assertTrue(kami.extra.toString(), kami.extra.drop(3).contains("📜"))
        typist.reset()
        strip.emojiSuggestions = false
        type("ame")
        val off = strip.make(typist) as Strip.Candidates
        assertTrue(off.toString(), (listOf(off.left, off.centre, off.right) + off.extra).none { it == "☔" })
    }
}
