package com.xern.xrnkey

import com.xern.xrnkey.ja.Candidate
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Kana to kanji with the made-up test dictionary (data/ja/test/dict.txt), and, where this machine
 * has it built, with Mozc's (data/ja/README.md).
 */
class ConverterTest {
    private val dict = TestModels.japanese()
    private val conv = Converter(dict)

    @Test
    fun aSentenceConvertsWhole() {
        assertEquals("私は学生です", conv.bestText("わたしはがくせいです"))
        assertEquals(listOf("わたし", "は", "がくせい", "です"), conv.best("わたしはがくせいです").map { it.reading })
        assertEquals("今日は雨です", conv.bestText("きょうはあめです"))
        assertEquals("学校に行きます", conv.bestText("がっこうにいきます"))
        assertEquals("日本語", conv.bestText("にほんご"))
    }

    /** The words read exactly so, cheapest alone first: かみ is 紙, 神, 髪, 上. */
    @Test
    fun theWordsForAReading() {
        assertEquals(listOf("紙", "神", "髪", "上"), dict.lookup("かみ").map { it.surface })
        assertEquals(listOf("紙", "神", "髪", "上"), conv.convert("かみ").words.map { it.text })
        assertEquals(emptyList<String>(), dict.lookup("かみさま").map { it.surface })
        assertEquals(emptyList<String>(), dict.lookup("").map { it.surface })
        assertEquals(emptyList<String>(), dict.lookup("kami").map { it.surface })
    }

    /** Text no word reads stands for itself: Latin letters, digits, punctuation, a lone kana. */
    @Test
    fun whatNoWordReadsStaysAsItIs() {
        assertEquals("紙、", conv.bestText("かみ、"))
        assertEquals("Tokyo", conv.bestText("Tokyo"))
        assertEquals("3日本", conv.bestText("3にほん"))
        assertEquals("ぬ", conv.bestText("ぬ"))
    }

    /**
     * The words a reading may become, cheapest alone first, both where a prefix starts so many
     * readings that its words are stored ("に", as the test dictionary's @scan sets) and where
     * they are looked through ("にほ").
     */
    @Test
    fun predictionsGoOnFromTheReading() {
        assertEquals(listOf("日本", "日本語", "日本人"), dict.predict("に", 5).map { it.surface })
        assertEquals(listOf("日本", "日本語", "日本人"), dict.predict("にほ", 5).map { it.surface })
        assertEquals(listOf("日本語", "日本人"), dict.predict("にほん", 5).map { it.surface })
        assertEquals(listOf("日本"), dict.predict("にほ", 1).map { it.surface })
        assertEquals(emptyList<String>(), dict.predict("にほんご", 5).map { it.surface })
    }

    /**
     * The candidates for a reading: the best conversion, the hiragana and katakana, the words,
     * the pieces (the first word and the others read like it, then the other starts that are
     * words) and the predictions. Space goes through the conversions only.
     */
    @Test
    fun aConversionOffersEveryCandidate() {
        val c = conv.convert("わたしはがくせいです")
        assertEquals(Candidate("私は学生です", 10), c.best)
        assertEquals("わたしはがくせいです", c.hiragana.text)
        assertEquals("ワタシハガクセイデス", c.katakana.text)
        assertEquals(listOf("私", "渡し"), c.pieces.map { it.text })
        assertTrue(c.pieces.all { it.length == 3 })
        assertEquals(listOf("私は学生です", "わたしはがくせいです", "ワタシハガクセイデス", "私", "渡し"), c.offered.map { it.text })
        assertEquals(listOf("私は学生です", "わたしはがくせいです", "ワタシハガクセイデス", "私", "渡し"), c.cycle.map { it.text })
        val kami = conv.convert("かみ")
        assertEquals(listOf("紙", "かみ", "カミ", "神", "髪", "上", "蚊"), kami.offered.map { it.text })
        assertEquals(listOf("紙", "神", "髪", "上", "かみ", "カミ", "蚊"), kami.cycle.map { it.text })
        assertEquals(1, kami.cycle.last().length)
        val ni = conv.convert("にほ")
        assertEquals(listOf("日本", "日本語", "日本人"), ni.predictions.map { it.text })
        assertTrue(ni.cycle.none { it.text == "日本語" })
    }

    /**
     * The rest of a reading after a piece of it was taken follows that piece: "はがくせいです"
     * after 私 starts with the particle は; alone, at a sentence's start, it would be 葉.
     */
    @Test
    fun theRestFollowsThePieceTaken() {
        val first = conv.convert("わたしはがくせいです").pieces.first()
        assertEquals("葉学生です", conv.bestText("はがくせいです"))
        assertEquals("は学生です", conv.bestText("はがくせいです", first.rid))
        assertEquals("は学生です", conv.convert("はがくせいです", first.rid).best.text)
    }

    @Test
    fun latinLettersHaveAFullWidthCandidate() {
        val c = conv.convert("Tokyo")
        assertEquals("Tokyo", c.best.text)
        assertEquals("Ｔｏｋｙｏ", c.wide?.text)
        assertEquals(null, conv.convert("かみ").wide)
    }

    @Test
    fun aDictionaryOfAnotherVersionIsRefused() {
        val bytes = TestModels.testJapanese.readBytes()
        bytes[4] = 9
        val refused = runCatching { Dictionary.of(bytes) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
    }

    // ---- Mozc's dictionary, where it is built ---------------------------------------------

    private fun real(): Converter {
        assumeTrue(TestModels.realJapaneseAvailable)
        return Converter(TestModels.realJapanese())
    }

    @Test
    fun theRealDictionaryConvertsEverydayJapanese() {
        val c = real()
        assertEquals("私は学生です", c.bestText("わたしはがくせいです"))
        assertEquals("日本語", c.bestText("にほんご"))
        assertEquals("今日はいい天気ですね", c.bestText("きょうはいいてんきですね"))
        assertEquals("新聞を読む", c.bestText("しんぶんをよむ"))
        assertEquals("明日学校に行きます", c.bestText("あしたがっこうにいきます"))
        assertEquals("ありがとうございます", c.bestText("ありがとうございます"))
        val kami = c.convert("かみ").words.map { it.text }
        assertTrue(kami.toString(), "紙" in kami && "神" in kami && "髪" in kami)
        assertTrue(c.convert("にほ").predictions.map { it.text }.containsAll(listOf("日本", "日本語")))
        assertTrue(c.convert("ありが").predictions.map { it.text }.contains("ありがとう"))
    }

    /**
     * Mozc's evaluation set (data/ja/README.md), where -Pxrnkey.jaEval names it: the best
     * conversion is Mozc's as often as build_dict.py's convert() makes it (505 of 683). Of two
     * ways as cheap, the first found wins, as there; a hash map's order decided it before, and
     * seven sentences came out otherwise (503).
     */
    @Test
    fun theRealDictionaryOnMozcsEvaluationSet() {
        val c = real()
        assertEquals("服切る", c.bestText("ふくきる"))
        assertEquals("熱出た", c.bestText("ねつでた"))
        val file = System.getProperty("xrnkey.jaEval")?.let(::File)
        assumeTrue(file?.isFile == true)
        var best = 0
        var total = 0
        file!!.forEachLine { line ->
            if (line.startsWith("#")) return@forEachLine
            val f = line.split('\t')
            if (c.bestText(f[1]) == f[2]) best++
            total++
        }
        println("Mozc's evaluation set: best $best of $total")
        assertTrue("$best of $total", best >= 505)
    }

    /** Fast enough for every key: a long reading converts, with every candidate, in milliseconds. */
    @Test
    fun theRealDictionaryConvertsQuickly() {
        val c = real()
        val readings = listOf("わたしはがくせいです", "きょうはいいてんきですね", "か", "あり", "とうきょうとっきょきょかきょく")
        repeat(3) { for (r in readings) c.convert(r) }  // warm up
        val start = System.nanoTime()
        var n = 0
        repeat(20) {
            for (r in readings) {
                for (end in 1..r.length) {
                    c.convert(r.substring(0, end))
                    n++
                }
            }
        }
        val each = (System.nanoTime() - start) / 1e6 / n
        println("conversion: %.2f ms a key".format(each))
        assertTrue("%.2f ms".format(each), each < 20)
    }
}
