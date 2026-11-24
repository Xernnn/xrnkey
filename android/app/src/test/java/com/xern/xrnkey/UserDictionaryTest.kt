package com.xern.xrnkey

import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.UserDictionary
import com.xern.xrnkey.ja.UserHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The owner's own Japanese words: kept with their readings, and converted as dictionary words
 * that win their reading, with the made-up test dictionary and, where it is built, Mozc's.
 */
class UserDictionaryTest {
    private val conv = Converter(TestModels.japanese())

    /** A reading in hiragana, katakana or romaji is kept in hiragana; one that isn't kana is refused. */
    @Test
    fun readingsAreKeptInHiragana() {
        assertEquals("たなか", UserDictionary.readingOf("たなか"))
        assertEquals("たなか", UserDictionary.readingOf("タナカ"))
        assertEquals("たなか", UserDictionary.readingOf(" Tanaka "))
        assertEquals("しんぶん", UserDictionary.readingOf("shinbun"))
        assertEquals("らーめん", UserDictionary.readingOf("ra-men"))
        assertNull(UserDictionary.readingOf(""))
        assertNull(UserDictionary.readingOf("田中"))
        assertNull(UserDictionary.readingOf("tanak"))
        assertNull(UserDictionary.readingOf("3じ"))
    }

    @Test
    fun wordsAreAddedListedAndRemoved() {
        val d = UserDictionary()
        assertTrue(d.add("tanaka", "田仲"))
        assertTrue(d.add("たなか", "多奈加"))
        assertFalse(d.add("たなか", "田仲"))            // there already
        assertFalse(d.add("たなか", " "))
        assertFalse(d.add("たなか", "田\t仲"))
        assertFalse(d.add("田中", "田仲"))
        assertTrue(d.add("ぜるだ", "ゼルダ"))
        assertEquals(listOf("ゼルダ", "多奈加", "田仲"), d.all().map { it.surface })
        assertEquals(listOf("多奈加", "田仲"), d.of("たなか").map { it.surface })
        assertEquals(listOf(3 to listOf("多奈加", "田仲")), d.at("わたなかさん", 1).map { (n, es) -> n to es.map { it.surface } })
        assertEquals(emptyList<Any>(), d.at("わたなかさん", 2))
        assertEquals(setOf("多奈加", "田仲"), d.startingWith("たな", 5).map { it.surface }.toSet())
        assertEquals(emptyList<Any>(), d.startingWith("たなか", 5))
        val v = d.version
        d.remove("たなか", "多奈加")
        assertTrue(d.version > v)
        assertEquals(listOf("田仲"), d.of("たなか").map { it.surface })
        d.remove("たなか", "田仲")
        assertEquals(emptyList<Any>(), d.at("たなか", 0))
    }

    /**
     * Saved and loaded, exported and imported as reading-tab-word lines; an import takes Mozc's
     * and Gboard's exports (more columns, comments) and skips what won't do.
     */
    @Test
    fun wordsAreSavedAndImported() {
        val file = File.createTempFile("japanese-words", ".tsv")
        try {
            val d = UserDictionary()
            d.add("たなか", "田仲")
            d.add("ぜるだ", "ゼルダ")
            assertTrue(d.save(file))
            val back = UserDictionary().also { it.load(file) }
            assertEquals(listOf("ゼルダ", "田仲"), back.all().map { it.surface })
            assertEquals(d.export(), back.export())
            val other = UserDictionary()
            val mozc = "# Mozc\nかとう\t加籐\t人名\tcomment\nbad line\nたなか\t田仲\tja-JP\n\n田中\tx\n"
            assertEquals(2, other.import(mozc))
            assertEquals(listOf("田仲", "加籐"), other.all().map { it.surface })
            file.delete()
            assertEquals(0, UserDictionary().also { it.load(file) }.size)
        } finally {
            file.delete()
        }
    }

    /**
     * A word the dictionary lacks: added, it converts for its reading, alone, inside a sentence and
     * after the particle before it, is predicted from the reading's start, and is learned when
     * chosen; removed, it is gone.
     */
    @Test
    fun anOwnWordConvertsAndIsPredicted() {
        assertEquals("たな蚊", conv.convert("たなか").best.text)
        val d = UserDictionary()
        d.add("たなか", "田仲")
        conv.userWords = d
        assertEquals("田仲", conv.convert("たなか").best.text)
        assertEquals("田仲", conv.convert("たなか").words.first().text)
        assertEquals("田仲は学生です", conv.bestText("たなかはがくせいです"))
        assertEquals("私は田仲です", conv.bestText("わたしはたなかです"))
        assertTrue(conv.convert("たな").predictions.map { it.text }.contains("田仲"))
        assertTrue(conv.convert("たな").offered.take(3).any { it.text == "田仲" })
        val h = UserHistory()
        conv.history = h
        h.learn(null, conv.convert("たなか").words.first().segments)
        assertEquals(listOf("田仲"), h.of("たなか").map { it.surface })
        conv.history = null
        d.remove("たなか", "田仲")
        assertEquals("たな蚊", conv.convert("たなか").best.text)
    }

    /** A word for a reading the dictionary has wins it, and the dictionary's words follow. */
    @Test
    fun anOwnWordWinsAReadingTheDictionaryHas() {
        val d = UserDictionary()
        d.add("かみ", "加美")
        conv.userWords = d
        val kami = conv.convert("かみ")
        assertEquals("加美", kami.best.text)
        assertEquals(listOf("加美", "紙", "神", "髪", "上"), kami.words.map { it.text })
        assertEquals("加美です", conv.bestText("かみです"))
    }

    /** With Mozc's dictionary: たなか is 田中 until 田仲 is added, then 田仲, in a sentence too. */
    @Test
    fun anOwnWordWinsWithTheRealDictionary() {
        assumeTrue(TestModels.realJapaneseAvailable)
        val c = Converter(TestModels.realJapanese())
        assertEquals("田中", c.convert("たなか").best.text)
        val d = UserDictionary()
        d.add("たなか", "田仲")
        d.add("ぜるだ", "ゼルダ")
        c.userWords = d
        assertEquals("田仲", c.convert("たなか").best.text)
        assertEquals("田仲さんは学生です", c.bestText("たなかさんはがくせいです"))
        assertEquals("ゼルダをする", c.bestText("ぜるだをする"))
        assertTrue(c.convert("ぜる").offered.take(3).map { it.text }.toString(), c.convert("ぜる").offered.take(3).any { it.text == "ゼルダ" })
    }
}
