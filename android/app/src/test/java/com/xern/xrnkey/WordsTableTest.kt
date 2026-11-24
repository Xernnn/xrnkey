package com.xern.xrnkey

import com.xern.xrnkey.sync.WordsTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The personal words' file as the hub's sync patches it. Made-up words only. */
class WordsTableTest {
    private val file = "# xrnkey: words and word pairs learned on this device\n" +
        "bún\t2\nphở\t3\n<s> phở\t2\năn phở\t2.7\n"

    @Test
    fun aPatchSetsAndDeletesAndLeavesTheRest() {
        val (text, applied) = WordsTable.patch(file, "phở\t5\nbún\t-\nchè\t2\nmissing\t-\n")
        assertEquals(WordsTable.Applied(set = 2, deleted = 1, skipped = 0), applied)
        assertEquals(
            "# xrnkey: words and word pairs learned on this device\n" +
                "chè\t2\nphở\t5\n<s> phở\t2\năn phở\t2.7\n",
            text,
        )
    }

    @Test
    fun linesTheEngineWouldntLearnAreSkipped() {
        val bad = "no tab\n\tonly a count\nphở\t0\nphở\t-1\nphở\tNaN\nphở\t2e9\na b c\t2\n<unk> phở\t2\n" +
            "phở <s>\t2\n${"x".repeat(65)}\t2\nphở\t\n#phở\t2\n"
        val (text, applied) = WordsTable.patch(file, bad)
        assertEquals(0, applied.set + applied.deleted)
        assertEquals(12, applied.skipped)
        assertEquals(WordsTable.parse(file), WordsTable.parse(text))
    }

    @Test
    fun aPairMayFollowASentencesStartAPauseOrANumber() {
        for (key in listOf("<s> phở", "<p> phở", "<num> phở", "ăn phở", "phở")) assertTrue(key, WordsTable.learnable(key))
        for (key in listOf("<s>", "<num>", "phở <p>", "", " phở", "a\u0001b")) assertFalse(key, WordsTable.learnable(key))
    }

    @Test
    fun countsArePrintedAsTheEnginePrintsThem() {
        assertEquals("3", WordsTable.number(3.0))
        assertEquals("2.7", WordsTable.number(2.7))
        assertEquals("0.531441", WordsTable.number(0.531441))
        assertEquals("0.333333", WordsTable.number(1.0 / 3))
        assertEquals("1000000000", WordsTable.number(1e9))
    }

    @Test
    fun windowsLineEndsAndRepeatsReadAsTheEngineReadsThem() {
        assertEquals(mapOf("phở" to 4.0, "bún" to 1.0), WordsTable.parse("phở\t3\r\nbún\t1\r\nphở\t4\n"))
    }
}
