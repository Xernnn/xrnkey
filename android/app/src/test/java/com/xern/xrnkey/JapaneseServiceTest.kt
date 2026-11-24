package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.xern.xrnkey.ime.OnDevice
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.KeyKind
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Layouts
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Strip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * The Japanese keyboard in the service, typing into a real text field as on the phone: chosen
 * from the comma's menu, it converts, and Vietnamese comes back as it was. The dictionary is the
 * made-up test one, opened from a file laid out as the APK's assets are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JapaneseServiceTest {
    private val packed = TestModels.packed(TestModels.bundled + ("ja.xkdic" to TestModels.testJapanese))

    @After
    fun removePack() = packed.close()

    private class Field(val service: XrnkeyService, val edit: EditText, val keys: KeyboardView)

    private fun field(inputType: Int = InputType.TYPE_CLASS_TEXT, assets: Boolean = true, imeOptions: Int = 0): Field {
        XrnkeyService.modelAssets = { name ->
            val (at, length) = (if (assets) packed.places[name] else null) ?: throw java.io.FileNotFoundException(name)
            AssetFileDescriptor(ParcelFileDescriptor.open(packed.file, ParcelFileDescriptor.MODE_READ_ONLY), at, length)
        }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelAssets = null
        // The field's own type: its connection fills the EditorInfo from it.
        val edit = EditText(RuntimeEnvironment.getApplication()).also {
            it.inputType = inputType
            it.imeOptions = imeOptions
        }
        val info = EditorInfo()
        val ic = edit.onCreateInputConnection(info)!!
        val start = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        start.isAccessible = true
        start.set(s, ic)
        val frame = s.onCreateInputView() as ViewGroup
        s.onStartInputView(info, false)
        val keys = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        return Field(s, edit, keys)
    }

    /** Types `text` and reports each key's selection update, as the editor does. */
    private fun Field.type(text: String) {
        for (c in text) {
            val old = edit.selectionStart
            service.onText(c.toString())
            report(old)
        }
    }

    private fun Field.report(old: Int) {
        val e = edit.text
        service.onUpdateSelection(old, old, edit.selectionStart, edit.selectionEnd, BaseInputConnection.getComposingSpanStart(e), BaseInputConnection.getComposingSpanEnd(e))
    }

    private fun Field.strip(): Strip {
        shadowOf(Looper.getMainLooper()).idle()
        return keys.strip
    }

    private val Field.composing: String
        get() {
            val e = edit.text
            val a = BaseInputConnection.getComposingSpanStart(e)
            return if (a < 0) "" else e.substring(a, BaseInputConnection.getComposingSpanEnd(e))
        }

    @Test
    fun japaneseIsOfferedWithItsDictionaryAndTheLogSaysSo() {
        ShadowLog.clear()
        val f = field()
        val line = ShadowLog.getLogsForTag("xrnkey").map { it.msg }.single { it.startsWith("dictionary: ") }
        assertTrue(line, Regex("dictionary: ja\\.xkdic at \\d+ \\(\\d+ bytes\\)").matches(line))
        assertEquals(listOf("vi", "en", "ja", Options.FLICK), f.keys.languages)
        f.service.onDestroy()
        val none = field(assets = false)
        assertEquals(listOf("vi", "en"), none.keys.languages)
        none.service.onLanguage("ja")
        assertEquals("vi", none.keys.language)
        none.service.onDestroy()
    }

    /**
     * Switched to Japanese from the comma's menu: romaji composes as hiragana, Space converts,
     * Enter commits with no new line, 、 and 。 are the keys' own; back on Vietnamese, Telex marks
     * again. The choice is kept.
     */
    @Test
    fun theJapaneseKeyboardConvertsAndVietnameseComesBack() {
        val f = field()
        f.service.onLanguage("ja")
        assertEquals("ja", f.keys.language)
        assertEquals("ja", Options.load(Options.prefs(RuntimeEnvironment.getApplication())).language)
        assertTrue(f.keys.kana)
        f.type("watashihagakuseidesu")
        assertEquals("わたしはがくせいです", f.edit.text.toString())
        assertEquals("わたしはがくせいです", f.composing)
        val s = f.strip() as Strip.Candidates
        assertEquals(listOf("わたしはがくせいです", "私は学生です", "ワタシハガクセイデス"), listOf(s.left, s.centre, s.right))
        f.type(" ")
        assertEquals("私は学生です", f.edit.text.toString())
        assertTrue((f.strip() as Strip.Candidates).highlight)
        val old = f.edit.selectionStart
        f.service.onEnter()
        f.report(old)
        assertEquals("私は学生です", f.edit.text.toString())
        assertEquals("", f.composing)
        f.type("。 ")
        assertEquals("私は学生です。　", f.edit.text.toString())
        f.service.onLanguage("vi")
        assertFalse(f.keys.kana)
        f.type("vieetj ")
        assertEquals("私は学生です。　việt ", f.edit.text.toString())
        f.service.onDestroy()
    }

    /** The field's span on the focused phrase: where it is, as the field shows it. */
    private fun Field.focused(): String {
        val e = edit.text
        val span = e.getSpans(0, e.length, android.text.style.BackgroundColorSpan::class.java).singleOrNull() ?: return ""
        return e.substring(e.getSpanStart(span), e.getSpanEnd(span))
    }

    /**
     * Space splits the reading into phrases in the field, the focused one on a tint: the space
     * bar's slide (or ◀ ▶) moves the focus without ending the conversion, the strip's ⇤ shrinks
     * the focused phrase, a tap on the last phrase's candidate commits it all and the tint goes.
     */
    @Test
    fun spaceConvertsInPhrasesAndTheFieldMarksTheFocusedOne() {
        val f = field()
        f.service.onLanguage("ja")
        f.type("watashihagakuseidesu ")
        assertEquals("私は学生です", f.composing)
        assertEquals("私は", f.focused())
        val s = f.strip() as Strip.Candidates
        assertTrue(s.resize && s.highlight)
        assertTrue(f.keys.converting)
        val old = f.edit.selectionStart
        f.service.onCursorStart()
        f.service.onCursor(1, 0)
        f.service.onCursorEnd()
        f.report(old)
        assertEquals("私は学生です", f.composing)
        assertEquals("学生です", f.focused())
        assertEquals("学生です", (f.strip() as Strip.Candidates).centre)
        f.service.onResize(-1)
        f.report(old)
        assertEquals("学生で", f.focused())
        f.strip()
        f.service.onCandidate(1)
        f.strip()
        f.service.onCandidate(1)
        assertEquals("私は学生です", f.edit.text.toString())
        assertEquals("", f.composing)
        assertEquals("", f.focused())
        assertFalse(f.keys.converting)
        f.service.onDestroy()
    }

    /**
     * A candidate from the strip, or from the panel, commits it in place of the part of the reading
     * it stands for: a piece leaves the rest composing.
     */
    @Test
    fun aCandidateCommitsAndAPieceLeavesTheRest() {
        val f = field()
        f.service.onLanguage("ja")
        f.type("kami")
        f.strip()
        f.service.onCandidate(1)
        assertEquals("紙", f.edit.text.toString())
        assertEquals("", f.composing)
        f.type("watashiha")
        assertTrue((f.strip() as Strip.Candidates).extra.contains("私"))
        f.service.onMorePick("私")
        assertEquals("紙私は", f.edit.text.toString())
        assertEquals("は", f.composing)
        f.service.onDestroy()
    }

    /**
     * Type, pick: the strip then offers what follows the word, and a tap puts it in with no space,
     * the strip going on from it. What was picked is learned, comes first next time, and is kept
     * in the phone's storage when the field is left.
     */
    @Test
    fun aPickIsFollowedByTheNextWordsAndLearned() {
        val f = field()
        f.service.onLanguage("ja")
        f.type("watashi")
        assertEquals("私", (f.strip() as Strip.Candidates).centre)
        f.service.onCandidate(1)
        assertEquals("私", f.edit.text.toString())
        val next = f.strip() as Strip.Candidates
        assertEquals("は", next.centre)
        assertFalse(next.bold)
        f.service.onCandidate(1)
        assertEquals("私は", f.edit.text.toString())
        assertEquals("", f.composing)
        assertTrue(f.strip() is Strip.Candidates)
        // 髪 from the panel; next time it comes first.
        f.type("kami")
        assertEquals("紙", (f.strip() as Strip.Candidates).centre)
        f.service.onMorePick("髪")
        assertEquals("私は髪", f.edit.text.toString())
        f.type("kami")
        assertEquals("髪", (f.strip() as Strip.Candidates).centre)
        f.service.onFinishInputView(true)
        val kept = File(f.service.filesDir, OnDevice.JAPANESE).readText()
        assertTrue(kept, kept.contains("w\tかみ\t髪\t"))
        assertTrue(kept, kept.contains("p\t私\tは\tは\t"))
        f.service.onDestroy()
    }

    /**
     * The owner's own words, from the settings' file: the keyboard converts them for their reading
     * (with learning off too, and in an incognito field: they are the owner's, not learned), and
     * reads the file again at the next field once the settings changed it.
     */
    @Test
    fun theOwnersWordsConvert() {
        val file = File(RuntimeEnvironment.getApplication().filesDir, OnDevice.JAPANESE_WORDS)
        com.xern.xrnkey.ja.UserDictionary().apply { add("tanaka", "田仲"); save(file) }
        Options(learn = false).save(Options.prefs(RuntimeEnvironment.getApplication()))
        val f = field(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        f.service.onLanguage("ja")
        f.type("tanaka")
        assertEquals("田仲", (f.strip() as Strip.Candidates).centre)
        f.type(" ")
        assertEquals("田仲", f.edit.text.toString())
        f.service.onFinishInputView(true)
        com.xern.xrnkey.ja.UserDictionary().apply { load(file); add("tanaka", "多奈加"); save(file) }
        OnDevice.japaneseWordsChanged(Options.prefs(RuntimeEnvironment.getApplication()))
        f.service.onStartInputView(EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }, false)
        f.type("tanaka")
        assertEquals("多奈加", (f.strip() as Strip.Candidates).centre)
        f.service.onDestroy()
    }

    /**
     * The strip offers a reading's emoji from the APK's Japanese table: あめ (雨) ☔ on the right; a
     * tap puts it in place of the reading and keeps it among the emoji panel's recents. A
     * kaomoji goes in as well, but is no recent.
     */
    @Test
    fun aReadingsEmojiIsOfferedAndGoesIn() {
        val f = field()
        f.service.onLanguage("ja")
        f.type("ame")
        assertEquals("☔", (f.strip() as Strip.Candidates).right)
        f.service.onCandidate(2)
        assertEquals("☔", f.edit.text.toString())
        assertEquals("", f.composing)
        val memory = com.xern.xrnkey.ime.EmojiMemory(Options.prefs(RuntimeEnvironment.getApplication()))
        assertEquals("☔", memory.recents().first())
        f.type("kao")
        val s = f.strip() as Strip.Candidates
        assertTrue(s.toString(), "(^_^)" in listOfNotNull(s.right) + s.extra)
        f.service.onMorePick("(´・ω・`)")
        assertEquals("☔(´・ω・`)", f.edit.text.toString())
        assertFalse("(´・ω・`)" in memory.recents())
        f.service.onDestroy()
    }

    /** In an incognito field nothing chosen is learned, and nothing is written. */
    @Test
    fun anIncognitoFieldLearnsNothing() {
        val f = field(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        f.service.onLanguage("ja")
        f.type("kami")
        f.strip()
        f.service.onMorePick("髪")
        f.type("kami")
        assertEquals("紙", (f.strip() as Strip.Candidates).centre)
        f.service.onFinishInputView(true)
        assertFalse(File(f.service.filesDir, OnDevice.JAPANESE).exists())
        f.service.onDestroy()
    }

    /** The cursor moved elsewhere: the reading is committed as it is shown. */
    @Test
    fun aMovedCursorCommitsTheReading() {
        val f = field()
        f.service.onLanguage("ja")
        f.type("nihon")
        assertEquals("にほn", f.edit.text.toString())
        f.edit.setSelection(0)
        f.service.onUpdateSelection(3, 3, 0, 0, 0, 3)
        assertEquals("にほん", f.edit.text.toString())
        assertEquals("", f.composing)
        assertEquals(Strip.Tools, f.strip())
        f.service.onDestroy()
    }

    /** In a password field the Japanese keyboard types plain letters, and its keys say , and . again. */
    @Test
    fun aPasswordFieldGetsPlainLetters() {
        val f = field()
        f.service.onLanguage("ja")
        f.service.onDestroy()
        val p = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals("ja", p.keys.language)
        assertFalse(p.keys.kana)
        p.type("kami ")
        assertEquals("kami ", p.edit.text.toString())
        p.service.onDestroy()
    }

    /** The letters' comma and full stop are 、 and 。 in Japanese, holding 。 gives Japanese punctuation. */
    @Test
    fun theKeysSayJapanesePunctuation() {
        val bottom = Layouts.rows(Page.LETTERS, kana = true).last().keys
        val comma = bottom.single { it.kind == KeyKind.COMMA }
        val stop = bottom.single { it.kind == KeyKind.PERIOD }
        assertEquals("、" to "、", comma.label to comma.text)
        assertEquals("。" to "。", stop.label to stop.text)
        assertEquals("、", stop.hold)
        assertTrue(stop.variants.containsAll(listOf("？", "！", "「", "」", "ー")))
        val latin = Layouts.rows(Page.LETTERS).last().keys
        assertEquals(",", latin.single { it.kind == KeyKind.COMMA }.text)
        // The symbols keep theirs.
        assertEquals(",", Layouts.rows(Page.SYMBOLS, kana = true).last().keys.single { it.kind == KeyKind.COMMA }.text)
        assertNull(Layouts.rows(Page.SYMBOLS, kana = true).last().keys.firstOrNull { it.text == "、" })
    }

    /**
     * A held letter on the Japanese keyboard gave Vietnamese's toned vowels (ắ ằ ẳ): now a vowel
     * with its macron, the letter full-width and its capital; with Shift, the same as capitals and
     * the small letter last. The top row still gives its digit first without the number row; the
     * Vietnamese keyboard keeps its marks.
     */
    @Test
    fun aHeldLetterGivesLatinFormsNotVietnamese() {
        fun variants(rows: List<com.xern.xrnkey.keyboard.Row>, label: String) =
            rows.flatMap { it.keys }.single { it.kind == KeyKind.CHAR && it.label == label }.variants
        val ja = Layouts.rows(Page.LETTERS, kana = true)
        assertEquals(listOf("ā", "ａ", "A"), variants(ja, "a"))
        assertEquals(listOf("ō", "ｏ", "O"), variants(ja, "o"))
        assertEquals(listOf("ｋ", "K"), variants(ja, "k"))
        assertEquals(listOf("ｄ", "D"), variants(ja, "d"))
        val shifted = Layouts.rows(Page.LETTERS, com.xern.xrnkey.keyboard.Shift.ONCE, kana = true)
        assertEquals(listOf("Ā", "Ａ", "a"), variants(shifted, "A"))
        val noRow = Layouts.rows(Page.LETTERS, numberRow = false, kana = true)
        assertEquals(listOf("3", "ē", "ｅ", "E"), variants(noRow, "e"))
        assertTrue(ja.flatMap { it.keys }.flatMap { it.variants }.none { it in listOf("ắ", "à", "đ", "ư") })
        assertTrue("ắ" in variants(Layouts.rows(Page.LETTERS), "a"))
    }
}
