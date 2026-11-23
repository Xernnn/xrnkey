package com.xern.xrnkey

import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.UserHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the owner chooses on the Japanese keyboard, with the made-up test dictionary: a chosen word
 * ranks first for its reading and wins inside longer readings; the history is kept in a file,
 * bounded, and cleared.
 */
class UserHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dict = TestModels.japanese()
    private val conv = Converter(dict)

    /** The segment of the word `surface` read `reading`, as the converter gives it. */
    private fun word(reading: String, surface: String): Converter.Segment =
        conv.convert(reading).words.first { it.text == surface }.segments.single()

    @Test
    fun aChosenWordRanksFirstTheLatestChoiceFirst() {
        assertEquals("紙", conv.convert("かみ").best.text)
        val h = UserHistory()
        conv.history = h
        h.learn(null, listOf(word("かみ", "髪")))
        val c = conv.convert("かみ")
        assertEquals("髪", c.best.text)
        assertEquals("髪", c.offered.first().text)
        assertEquals("髪", c.cycle.first().text)
        assertEquals(listOf("髪", "紙", "神", "上"), c.words.map { it.text })
        // Chosen again later: the latest choice goes first.
        h.learn(null, listOf(word("かみ", "神")))
        assertEquals("神", conv.convert("かみ").best.text)
        assertEquals(listOf("神", "髪"), h.of("かみ").map { it.surface })
        conv.history = null
        assertEquals("紙", conv.convert("かみ").best.text)
    }

    /** Inside a longer reading too: the chosen word costs less than every other word read so. */
    @Test
    fun aChosenWordWinsInsideASentence() {
        assertEquals("紙です", conv.bestText("かみです"))
        conv.history = UserHistory().also { it.learn(null, listOf(word("かみ", "上"))) }
        assertEquals("上です", conv.bestText("かみです"))
        // Not a particle's place, though: は after 私 stays the particle when 葉 was chosen alone.
        conv.history!!.learn(null, listOf(word("は", "葉")))
        assertEquals("私は学生です", conv.bestText("わたしはがくせいです"))
    }

    /**
     * A chosen word is predicted from its reading's start, before the dictionary's predictions,
     * and a word chosen after the one before comes first after it.
     */
    @Test
    fun chosenWordsArePredictedAndFollowTheirWordBefore() {
        val h = UserHistory()
        conv.history = h
        h.learn(null, listOf(word("にほんじん", "日本人")))
        val ni = conv.convert("にほ")
        assertEquals("日本人", ni.predictions.first().text)
        assertEquals("日本人", ni.offered[1].text)
        // 私 then 学生, learned as a pair: offered first after 私, before the particles.
        h.learn(null, listOf(word("わたし", "私"), word("がくせい", "学生")))
        val next = conv.next(word("わたし", "私"))
        assertEquals("学生", next.first().text)
        assertTrue(next.map { it.text }.containsAll(listOf("は", "が")))
        assertEquals("学生", conv.convert("がく", prev = "私").offered[1].text)
    }

    @Test
    fun itIsKeptInAFileAndReadBackInOrder() {
        val h = UserHistory()
        conv.history = h
        h.learn(null, listOf(word("かみ", "髪")))
        h.learn(null, listOf(word("かみ", "神")))
        h.learn("私", listOf(word("がくせい", "学生")))
        h.learn(null, listOf(word("かみ", "神")))
        val file = File(tmp.root, "japanese-history.tsv")
        assertTrue(h.save(file))
        assertEquals(0, h.unsaved)
        val again = UserHistory().also { it.load(file) }
        assertEquals(3, again.size)
        assertEquals(listOf("神", "髪"), again.of("かみ").map { it.surface })
        assertEquals(2, again.of("かみ").first().count)
        assertEquals(listOf("学生"), again.after("私", 5).map { it.surface })
        assertEquals(h.stamp, again.stamp)
        assertEquals(listOf("神", "学生", "髪"), again.all().map { it.surface })
        // Lines it can't read are skipped.
        file.appendText("w\tかみ\n\nnonsense\np\t\tかみ\t紙\t1\t1\t1\n")
        assertEquals(3, UserHistory().also { it.load(file) }.size)
        // No file: nothing.
        assertEquals(0, UserHistory().also { it.load(File(tmp.root, "none.tsv")) }.size)
    }

    /** At most so many words and pairs: the longest unused go first. */
    @Test
    fun itIsBoundedTheOldestGoingFirst() {
        val h = UserHistory(most = 3)
        conv.history = h
        val words = listOf("かみ" to "紙", "ほん" to "本", "あめ" to "雨", "きって" to "切手", "かみ" to "紙")
        var prev: String? = null
        for ((r, s) in words) {
            h.learn(prev, listOf(word(r, s)))
            prev = s
        }
        assertEquals(3, h.size)
        assertFalse(h.has("ほん"))
        assertEquals(listOf("紙", "切手", "雨"), h.all().map { it.surface })
        // The pairs too: 紙→本 went first.
        assertEquals(emptyList<String>(), h.after("紙", 5).map { it.surface })
        assertEquals(listOf("切手"), h.after("雨", 5).map { it.surface })
        val file = File(tmp.root, "h.tsv")
        h.save(file)
        assertEquals(2, UserHistory(most = 2).also { it.load(file) }.size)
    }

    @Test
    fun aWordIsForgottenAndAllIsCleared() {
        val h = UserHistory()
        conv.history = h
        h.learn(null, listOf(word("わたし", "私"), word("がくせい", "学生")))
        h.learn(null, listOf(word("かみ", "髪")))
        h.forget("がくせい", "学生")
        assertFalse(h.has("がくせい"))
        assertEquals(emptyList<String>(), h.after("私", 5).map { it.surface })
        assertEquals(2, h.size)
        val v = h.version
        h.clear()
        assertEquals(0, h.size)
        assertTrue(h.version > v)
        assertEquals("紙", conv.convert("かみ").best.text)
    }

    /** Latin letters, digits and a kana no word has stand for themselves: nothing to learn. */
    @Test
    fun onlyWordsAreLearned() {
        val h = UserHistory()
        h.learn(null, conv.best("3ぬTokyo"))
        assertEquals(0, h.size)
    }

    /**
     * A history from another build of the dictionary (its parts of speech numbered otherwise)
     * takes this one's: each word those of its likeliest form here, so nothing reads past the
     * connection table.
     */
    @Test
    fun anotherDictionarysPartsOfSpeechAreLookedUpAgain() {
        val file = File(tmp.root, "old.tsv")
        file.writeText("# xrnkey japanese history 1 another\nw\tかみ\t髪\t9999\t9999\t1\np\t私\tかみ\t髪\t9999\t9999\t1\n")
        val h = UserHistory().also { it.load(file) }
        conv.history = h
        val e = h.of("かみ").single()
        assertEquals(word("かみ", "髪").lid, e.lid)
        assertEquals(word("かみ", "髪").rid, e.rid)
        assertEquals(dict.stamp, h.stamp)
        assertEquals("髪", conv.convert("かみ").best.text)
        assertEquals("髪", conv.next(word("わたし", "私")).first().text)
        // This build's, but damaged: the ids that aren't any of its parts of speech are looked up too.
        file.writeText("# xrnkey japanese history 1 ${dict.stamp}\nw\tかみ\t髪\t-3\t70000\t1\n")
        val damaged = UserHistory().also { it.load(file) }
        conv.history = damaged
        assertEquals(word("かみ", "髪").rid, damaged.of("かみ").single().rid)
        assertEquals("髪", conv.convert("かみ").best.text)
    }
}
