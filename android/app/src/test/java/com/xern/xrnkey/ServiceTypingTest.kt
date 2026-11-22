package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.inputmethod.BaseInputConnection
import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.ime.XrnkeyService
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The keyboard service end to end, as on the phone: its own models (the ones the APK bundles),
 * its settings, its typist, typing into a real text field through an InputConnection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceTypingTest {
    private val packed = TestModels.packed()

    @After
    fun removePack() = packed.close()

    private fun field(): Pair<XrnkeyService, EditText> {
        // The models the APK bundles (the release build copies them from this folder), opened
        // as on the phone: by descriptor, each at its offset in a file laid out as AGP lays out
        // the APK's assets.
        XrnkeyService.modelAssets = { name ->
            val (at, length) = packed.places[name] ?: throw java.io.FileNotFoundException(name)
            AssetFileDescriptor(ParcelFileDescriptor.open(packed.file, ParcelFileDescriptor.MODE_READ_ONLY), at, length)
        }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelAssets = null
        val edit = EditText(RuntimeEnvironment.getApplication())
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val ic = edit.onCreateInputConnection(info)!!
        // What the framework does when a field takes the keyboard.
        val start = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        start.isAccessible = true
        start.set(s, ic)
        s.onCreateInputView()
        s.onStartInputView(info, false)
        return s to edit
    }

    private fun XrnkeyService.type(text: String) = text.forEach { onText(it.toString()) }

    /** Types `text` and reports each key's selection update, as the editor does, `late` keys late. */
    private fun XrnkeyService.typeReported(edit: EditText, text: String, late: Int = 0) {
        val updates = ArrayDeque<IntArray>()
        for (c in text) {
            val old = edit.selectionStart
            onText(c.toString())
            val e = edit.text
            updates += intArrayOf(old, edit.selectionStart, BaseInputConnection.getComposingSpanStart(e), BaseInputConnection.getComposingSpanEnd(e))
            while (updates.size > late) updates.removeFirst().let { onUpdateSelection(it[0], it[0], it[1], it[1], it[2], it[3]) }
        }
        while (updates.isNotEmpty()) updates.removeFirst().let { onUpdateSelection(it[0], it[0], it[1], it[1], it[2], it[3]) }
    }

    /**
     * An app clears its box (after Send) without starting the input again: the words before are
     * gone, so "cam" is typed at a sentence's start. The typist kept "meet" as the word before,
     * and the strip read "cam" after an English word.
     */
    @Test
    fun aFieldClearedUnderTheKeyboardIsReadAgain() {
        val (s, edit) = field()
        s.type("meet ")
        assertEquals(listOf("<s>", "meet"), s.typist.context())
        edit.setText("")
        s.onUpdateSelection(5, 5, 0, 0, -1, -1)
        s.type("cam")
        assertEquals(listOf("<s>"), s.typist.context())
        TestModels.best().use { m ->
            val fresh = Typist(TypingState().also { it.useModels(m.models) }, FakeEditor(), m.models).also { it.reset() }
            fresh.text("cam")
            assertEquals(StripMaker(m.models).make(fresh), StripMaker(m.models).make(s.typist))
        }
        s.onDestroy()
    }

    /** The cursor moved to the field's start: no words are before it there. */
    @Test
    fun aCursorMovedToTheStartHasNoWordsBeforeIt() {
        val (s, edit) = field()
        s.type("toi ")
        assertEquals(listOf("<s>", "toi"), s.typist.context())
        edit.setSelection(0)
        s.onUpdateSelection(4, 4, 0, 0, -1, -1)
        assertEquals(listOf("<s>"), s.typist.context())
        assertTrue(s.typist.atSentenceStart())
        s.onDestroy()
    }

    /**
     * The keyboard's own edits, reported on time or late, read nothing again: the words typed
     * without marks are still one run ("toi khong biet"), which a re-read would have ended.
     */
    @Test
    fun theKeyboardsOwnEditsReadNothingAgain() {
        for (late in listOf(0, 3)) {
            val (s, edit) = field()
            s.typeReported(edit, "toi khong biet", late)
            assertEquals("toi khong biet", edit.text.toString())
            assertEquals(listOf("<s>", "toi", "khong"), s.typist.context())
            assertTrue("late $late", s.typist.phrase() != null)
            s.onDestroy()
        }
    }

    @Test
    fun aKnownTypoIsFixedOnSpace() {
        // Only with the bundled models: CI has the small test ones, which the APK doesn't ship.
        assumeTrue(TestModels.realAvailable)
        val (s, edit) = field()
        assertEquals(true, s.hasModels())
        s.type("teh ")
        assertEquals("the ", edit.text.toString())
        s.type("ong ")
        assertEquals("the ong ", edit.text.toString())
        s.onDestroy()
    }

    /**
     * Both models open the way the phone opens them, and the log says so. The English one, at an
     * offset of 4 mod 8 in the APK, used to be refused without a word: "teh" stayed "teh".
     */
    @Test
    fun bothModelsOpenFromTheApkAndTheLogSaysSo() {
        ShadowLog.clear()
        val (s, edit) = field()
        assertEquals(true, s.hasModels())
        val line = ShadowLog.getLogsForTag("xrnkey").map { it.msg }.single { it.startsWith("models: ") }
        assertTrue(line, Regex("vi\\.xklm \\d+ words at \\d+ \\(\\d+ bytes\\); en\\.xklm \\d+ words at").containsMatchIn(line))
        s.type("teh ")
        assertEquals("the ", edit.text.toString())
        s.onDestroy()
    }

    @Test
    fun aModelThatIsntThereIsLogged() {
        ShadowLog.clear()
        XrnkeyService.modelAssets = { name -> throw java.io.FileNotFoundException(name) }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelAssets = null
        assertEquals(false, s.hasModels())
        val line = ShadowLog.getLogsForTag("xrnkey").map { it.msg }.single { it.startsWith("models: ") }
        assertEquals("models: vi.xklm MISSING (vi.xklm); en.xklm MISSING (en.xklm)", line)
        s.onDestroy()
    }

    @Test
    fun anUpdateWithoutTheComposingRegionDoesntEndTheWord() {
        assumeTrue(TestModels.realAvailable)
        val (s, edit) = field()
        s.type("te")
        s.onUpdateSelection(0, 0, 2, 2, 0, 2)
        s.type("h")
        // The editor reports the cursor at the word's end, without the composing region.
        s.onUpdateSelection(2, 2, 3, 3, -1, -1)
        s.type(" ")
        assertEquals("the ", edit.text.toString())
        // A real move still ends the word where it is.
        s.type("teh")
        s.onUpdateSelection(7, 7, 1, 1, -1, -1)
        assertEquals("the teh", edit.text.toString())
        s.onDestroy()
    }
}
