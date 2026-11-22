package com.xern.xrnkey

import com.xern.xrnkey.engine.Candidate
import com.xern.xrnkey.engine.Model
import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.engine.TypingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** The shared core through the JNI bridge, built for this machine (android/host). */
class EngineTest {
    private fun type(s: TypingState, keys: String): String {
        val out = StringBuilder()
        var preedit = ""
        for (c in keys) {
            val r = s.key(c.code)
            out.append(r.commit)
            preedit = r.preedit
            if (!r.consumed) out.append(c)
        }
        return out.toString() + preedit
    }

    @Test
    fun telexComposesThroughTheCore() {
        TypingState().use { s ->
            assertEquals("xin chào ", type(s, "xin chaof "))
            assertEquals("tiếng Việt ", type(s, "tieengs Vieetj "))
            assertEquals("được", type(s, "dduowcj"))
            assertEquals("dduowcj", s.keys())
        }
    }

    @Test
    fun vietnameseTextSurvivesTheBridgeWholeIncludingEmoji() {
        TestModels.test().use { m ->
            val r = m.models.restoreRun(listOf("<s>", "tôi"), listOf("khong"))
            assertEquals(listOf("không"), r)
        }
        TypingState().use { s ->
            s.setContext(listOf("😀", "đây"))  // must not throw or mangle
            assertEquals("đi ", type(s, "ddi "))
        }
    }

    @Test
    fun theWordsBeforeDecideEnglishOrVietnamese() {
        TestModels.test().use { m ->
            TypingState().use { s ->
                s.useModels(m.models)
                assertEquals("my list ", type(s, "my list "))
                s.reset()
                assertEquals("hai lít ", type(s, "hai list "))
            }
        }
    }

    @Test
    fun restorationOffersTheReadingsOfAWord() {
        TestModels.test().use { m ->
            val c = m.models.restoreWord(listOf("<s>", "tôi", "không"), "duoc", 6)
            assertEquals("được", c.first().text)
            assertEquals(Candidate.RESTORED, c.first().type)
            assertTrue(c.any { it.text == "dược" })
            assertEquals(listOf("cố", "gắng"), m.models.restoreRun(listOf("<s>"), listOf("co", "gang")))
            assertNull(m.models.restoreRun(listOf("<s>"), listOf("8h")))
        }
    }

    @Test
    fun aModelOpensFromInsideALargerFileAsFromTheAPK() {
        val model = File(TestModels.testDir, "vi.xklm").readBytes()
        val packed = File.createTempFile("packed", ".bin")
        packed.writeBytes(ByteArray(5000) + model + ByteArray(300))
        RandomAccessFile(packed, "r").use { f ->
            val fd = TestModels.fdOf(f)
            val vi = Model.open(fd, 5000, model.size.toLong())
            assertNotNull(vi)
            Models(vi, null).use { m ->
                assertEquals("không", m.restoreWord(listOf("<s>", "tôi"), "khong").first().text)
            }
            vi!!.close()
        }
        packed.delete()
        assertNull(Model.open("/nonexistent/vi.xklm"))
    }

    /**
     * The phone opens each model by descriptor at the offset AGP gave it in the APK, aligned to
     * 4 bytes only. The reader used to refuse a model that didn't start on a multiple of 8, so
     * the phone's English model (at 4 mod 8) never loaded and "teh" stayed "teh", while every
     * test opened the models by path.
     */
    @Test
    fun theModelsOpenWhereAnApkPutsThem() {
        TestModels.packed().use { p ->
            RandomAccessFile(p.file, "r").use { f ->
                val fd = TestModels.fdOf(f)
                val (viAt, viLength) = p.places.getValue("vi.xklm")
                val (enAt, enLength) = p.places.getValue("en.xklm")
                assertEquals(4L, viAt % 8)
                assertEquals(4L, enAt % 8)
                val vi = Model.open(fd, viAt, viLength)
                val en = Model.open(fd, enAt, enLength)
                assertNotNull(vi)
                assertNotNull(en)
                Models(vi, en).use { m ->
                    assertEquals("the", m.fixWrongWord("teh"))
                    assertEquals("không", m.restoreWord(listOf("<s>", "tôi"), "khong").first().text)
                    assertTrue(m.complete(listOf("<s>"), "duo").any { it.first == "được" })
                }
                vi!!.close()
                en!!.close()
            }
        }
    }
}
