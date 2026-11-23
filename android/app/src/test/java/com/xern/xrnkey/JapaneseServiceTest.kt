package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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

    private fun field(inputType: Int = InputType.TYPE_CLASS_TEXT, assets: Boolean = true): Field {
        XrnkeyService.modelAssets = { name ->
            val (at, length) = (if (assets) packed.places[name] else null) ?: throw java.io.FileNotFoundException(name)
            AssetFileDescriptor(ParcelFileDescriptor.open(packed.file, ParcelFileDescriptor.MODE_READ_ONLY), at, length)
        }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelAssets = null
        // The field's own type: its connection fills the EditorInfo from it.
        val edit = EditText(RuntimeEnvironment.getApplication()).also { it.inputType = inputType }
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
        assertEquals(listOf("vi", "en", "ja"), f.keys.languages)
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
}
