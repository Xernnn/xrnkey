package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
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

    /**
     * A connection that applies a key only after the batch of edits it was sent in, as Compose's
     * text fields do; EditText's applies it at once.
     */
    private class LateKeys(target: InputConnection) : InputConnectionWrapper(target, true) {
        private var depth = 0
        private val keys = ArrayList<KeyEvent>()

        override fun beginBatchEdit(): Boolean {
            depth++
            return super.beginBatchEdit()
        }

        override fun endBatchEdit(): Boolean {
            val r = super.endBatchEdit()
            if (--depth == 0) {
                val late = keys.toList()
                keys.clear()
                late.forEach { super.sendKeyEvent(it) }
            }
            return r
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (depth > 0) {
                keys += event
                return true
            }
            return super.sendKeyEvent(event)
        }
    }

    private fun field(lateKeys: Boolean = false, app: String? = null): Pair<XrnkeyService, EditText> {
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
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = app
        }
        val ic = edit.onCreateInputConnection(info)!!.let { if (lateKeys) LateKeys(it) else it }
        // What the framework does when a field takes the keyboard.
        val start = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        start.isAccessible = true
        start.set(s, ic)
        s.onCreateInputView()
        s.onStartInputView(info, false)
        return s to edit
    }

    private fun XrnkeyService.type(text: String) = text.forEach { onText(it.toString()) }

    /** Reports the field's selection and composing region now, as the editor does after an edit. */
    private fun XrnkeyService.report(edit: EditText, old: Int) {
        val e = edit.text
        onUpdateSelection(old, old, edit.selectionStart, edit.selectionEnd, BaseInputConnection.getComposingSpanStart(e), BaseInputConnection.getComposingSpanEnd(e))
    }

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
     * A tap that puts the cursor at the end of a word opens it again: it is the composing word,
     * and Telex marks it ("chao" + s is "cháo"). A tap inside a word opens its part before the
     * cursor, as on Laban Key ("ch|ao": "ch").
     */
    @Test
    fun aTapAtTheEndOfAWordOpensItAgain() {
        val (s, edit) = field()
        s.typeReported(edit, "xin chao ban ")
        edit.setSelection(6)
        s.onUpdateSelection(13, 13, 6, 6, -1, -1)
        s.report(edit, 6)
        assertEquals("xin chao ban ", edit.text.toString())
        assertEquals(4, BaseInputConnection.getComposingSpanStart(edit.text))
        assertEquals(6, BaseInputConnection.getComposingSpanEnd(edit.text))
        assertEquals("ch", s.typist.shown)
        edit.setSelection(8)
        s.onUpdateSelection(6, 6, 8, 8, 4, 6)
        assertEquals("xin chao ban ", edit.text.toString())
        assertEquals(4, BaseInputConnection.getComposingSpanStart(edit.text))
        assertEquals(8, BaseInputConnection.getComposingSpanEnd(edit.text))
        assertEquals("chao", s.typist.shown)
        s.onText("s")
        assertEquals("xin cháo ban ", edit.text.toString())
        s.onDestroy()
    }

    /**
     * Backspace after a Space opens the word again in any field: in one that applies a key after
     * the edits sent with it (Compose's), the Backspace key came after the word was opened, and
     * "việt␣⌫" gave "vviệ".
     */
    @Test
    fun backspaceOpensTheWordAgainWhereKeysComeLate() {
        for (late in listOf(false, true)) {
            val (s, edit) = field(lateKeys = late)
            s.typeReported(edit, "vieetj ")
            s.onBackspace()
            s.report(edit, 5)
            assertEquals("late $late", "việt", edit.text.toString())
            assertEquals("late $late", 0, BaseInputConnection.getComposingSpanStart(edit.text))
            assertEquals("late $late", 4, BaseInputConnection.getComposingSpanEnd(edit.text))
            s.onText("s")
            s.report(edit, 4)
            assertEquals("late $late", "viết", edit.text.toString())
            // An emoji goes whole, skin tone and all.
            s.typeReported(edit, " ")
            s.onEmoji("👍🏽")
            s.report(edit, 5)
            s.onBackspace()
            s.report(edit, 9)
            assertEquals("late $late", "viết ", edit.text.toString())
            s.onDestroy()
        }
    }

    /**
     * With a selection, Backspace goes to the field as the key, which deletes the selection; the
     * connection takes nothing before it (the space before "chao" would go). (The test's field
     * doesn't apply keys, so the selection stays.)
     */
    @Test
    fun backspaceWithASelectionGoesAsTheKey() {
        val (s, edit) = field()
        s.typeReported(edit, "xin chao ban ")
        edit.setSelection(4, 9)
        s.onUpdateSelection(13, 13, 4, 9, -1, -1)
        s.onBackspace()
        assertEquals("xin chao ban ", edit.text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(edit.text))
        s.onDestroy()
    }

    /**
     * Nothing typed in xrnkey's own fields is learned: trying the keys in its Try-it field
     * taught the personal words what the trying typed. Other apps' fields learn.
     */
    @Test
    fun xrnkeysOwnFieldsLearnNothing() {
        val own = field(app = RuntimeEnvironment.getApplication().packageName).first
        assertEquals(false, own.typist.learnHere)
        own.onDestroy()
        val other = field(app = "com.example.chat").first
        assertEquals(true, other.typist.learnHere)
        other.onDestroy()
    }

    /**
     * The space bar moving the cursor opens no word on the way, only the one where it stops:
     * each step used to open (and rewrite) the word it passed.
     */
    @Test
    fun theSpaceBarOpensOnlyTheWordWhereTheCursorStops() {
        val (s, edit) = field()
        s.typeReported(edit, "xin chao ban ")
        s.onCursorStart()
        edit.setSelection(8)
        s.onUpdateSelection(13, 13, 8, 8, -1, -1)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(edit.text))
        edit.setSelection(3)
        s.onUpdateSelection(8, 8, 3, 3, -1, -1)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(edit.text))
        s.onCursorEnd()
        assertEquals("xin", s.typist.shown)
        assertEquals(0, BaseInputConnection.getComposingSpanStart(edit.text))
        assertEquals(3, BaseInputConnection.getComposingSpanEnd(edit.text))
        assertEquals("xin chao ban ", edit.text.toString())
        s.onDestroy()
    }

    /**
     * The English keyboard types letters as they are, no Telex ("vieetj" stays), and fixes
     * English typos on Space ("teh"); back on Vietnamese, Telex marks again.
     */
    @Test
    fun theEnglishKeyboardTypesWithoutTelex() {
        val (s, edit) = field()
        s.onLanguage("en")
        s.typeReported(edit, "vieetj teh ")
        assertEquals("vieetj the ", edit.text.toString())
        s.onLanguage("vi")
        s.typeReported(edit, "vieetj ")
        assertEquals("vieetj the việt ", edit.text.toString())
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

    /**
     * A swipe left from Backspace goes through the typist: the field loses the word, the typist's
     * copy of the field follows (its cursor is the field's), and the words before stay its context.
     */
    @Test
    fun aSwipedWordDeleteKeepsTheContextRight() {
        val (s, edit) = field()
        s.typeReported(edit, "toi khong biet ")
        val old = edit.selectionStart
        s.onDeleteWord()
        assertEquals("toi khong ", edit.text.toString())
        s.onUpdateSelection(old, old, edit.selectionStart, edit.selectionStart, -1, -1)
        assertEquals(listOf("<s>", "toi", "khong"), s.typist.context())
        assertEquals(edit.selectionStart, s.typist.cursor())
        // A word in progress goes whole.
        s.type("bie")
        s.onDeleteWord()
        assertEquals("toi khong ", edit.text.toString())
        assertEquals("", s.typist.shown)
        assertEquals(edit.selectionStart, s.typist.cursor())
        s.onDestroy()
    }

    /** A candidate from the more-suggestions panel replaces the word in progress, with a space. */
    @Test
    fun aCandidateFromThePanelIsTakenWithASpace() {
        val (s, edit) = field()
        s.type("xin cha")
        s.onMorePick("chào")
        assertEquals("xin chào ", edit.text.toString())
        assertEquals(listOf("<s>", "xin", "chào"), s.typist.context())
        s.onDestroy()
    }

    /** An emoticon from the number row's second page goes in whole: its D starts no word. */
    @Test
    fun anEmoticonFromTheNumberRowGoesInWhole() {
        val (s, edit) = field()
        s.type("ok ")
        s.onEmoticon(":D")
        assertEquals("ok :D", edit.text.toString())
        assertEquals("", s.typist.shown)
        s.onDestroy()
    }

    /**
     * A strip slot's remove mark forgets the word from the personal words, as the settings' list
     * does, and hides it with the personal data, so no model offers it either; a phrase is hidden.
     */
    @Test
    fun aRemoveMarkForgetsAndHides() {
        val (s, _) = field()
        val words = s.typist.personal!!
        repeat(3) { words.learn("<s>", "quocc") }
        assertTrue(words.count("<s>", "quocc") > 0)
        s.onRemove("quocc")
        assertEquals(0.0, words.count("<s>", "quocc"), 0.0)
        s.onRemove("cảm ơn")
        val hidden = com.xern.xrnkey.ime.HiddenWords(java.io.File(s.filesDir, com.xern.xrnkey.ime.OnDevice.HIDDEN))
        assertEquals(listOf("quocc", "cảm ơn"), hidden.all)
        s.onDestroy()
    }

    /**
     * A letter tapped reaches the typist with where it landed on its key: Space weighs it to fix
     * a word that is no word, and a word that ends as typed teaches where the taps land.
     */
    @Test
    fun aLettersTouchReachesTheTypist() {
        val (s, edit) = field(app = "com.example.chat")
        s.type("tooi ")
        "kjong".forEachIndexed { i, c -> s.onLetter(c.toString(), if (i == 1) -0.45f else 0f, 0f, 1.4f) }
        assertEquals(-0.45f, s.typist.touchesNow()[1]?.dx)
        s.onText(" ")
        assertEquals("tôi không ", edit.text.toString())
        val spots = s.typist.touchSpots!!
        "khoong".forEach { c -> s.onLetter(c.toString(), 0.1f, 0f, 1.4f) }
        s.onText(" ")
        assertEquals(1, spots.count('k'))
        assertEquals(2, spots.count('o'))
        s.onDestroy()
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
