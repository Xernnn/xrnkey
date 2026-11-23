package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.Flick
import com.xern.xrnkey.keyboard.KeyKind
import com.xern.xrnkey.keyboard.KeyboardView
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
import java.time.Duration

/**
 * Japanese on the 12-key pad in the service, typing into a real text field: chosen as あ from
 * the comma's menu, its kana compose and convert as romaji's do. The dictionary is the made-up
 * test one, opened from a file laid out as the APK's assets are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlickServiceTest {
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
        val edit = EditText(RuntimeEnvironment.getApplication()).also { it.inputType = inputType }
        val info = EditorInfo()
        val ic = edit.onCreateInputConnection(info)!!
        val start = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        start.isAccessible = true
        start.set(s, ic)
        val frame = s.onCreateInputView() as ViewGroup
        s.onStartInputView(info, false)
        val keys = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        // The phone's width, so the space bar has room for its language.
        keys.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        keys.layout(0, 0, 1080, keys.measuredHeight)
        return Field(s, edit, keys)
    }

    /** Reports the selection after one of the keyboard's edits, as the editor does. */
    private fun Field.act(block: () -> Unit) {
        val old = edit.selectionStart
        block()
        val e = edit.text
        service.onUpdateSelection(old, old, edit.selectionStart, edit.selectionEnd, BaseInputConnection.getComposingSpanStart(e), BaseInputConnection.getComposingSpanEnd(e))
    }

    private fun Field.kana(text: String) = act { service.onKana(text) }

    /** A tap on one of the pad's keys, by its label or kind, as the view acts on it. */
    private fun Field.tap(label: String) = act { keys.tap(Flick.rows.flatMap { it.keys }.first { it.label == label || it.kind.name == label }) }

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

    private fun app() = RuntimeEnvironment.getApplication()

    /**
     * The menu offers あ after JA: it switches the letters to the 12-key pad, keeps Japanese
     * typing, and stays chosen; JA is QWERTY again, and Vietnamese its own letters.
     */
    @Test
    fun theMenusKanaChoiceSwitchesToThePadAndBack() {
        val f = field()
        assertEquals(listOf("vi", "en", "ja", Options.FLICK), f.keys.languages)
        assertEquals(Page.LETTERS, f.keys.page)
        f.service.onLanguage(Options.FLICK)
        assertEquals(Page.KANA, f.keys.page)
        assertEquals(Page.KANA, f.keys.home)
        assertTrue(f.keys.kana)
        assertEquals(Options.FLICK, Options.load(Options.prefs(app())).language)
        assertEquals("空白", f.keys.spaceLabel)
        f.service.onLanguage("ja")
        assertEquals(Page.LETTERS, f.keys.page)
        assertTrue(f.keys.kana)
        assertEquals("日本語", f.keys.spaceLabel)
        f.service.onLanguage(Options.FLICK)
        assertEquals(Page.KANA, f.keys.page)
        f.service.onLanguage("vi")
        assertEquals(Page.LETTERS, f.keys.page)
        assertFalse(f.keys.kana)
        // From the symbols: the way back leads to the pad, and the symbols stay for now.
        f.keys.page = Page.SYMBOLS
        f.service.onLanguage(Options.FLICK)
        assertEquals(Page.SYMBOLS, f.keys.page)
        assertEquals(Page.KANA, f.keys.home)
        f.service.onDestroy()
        // A new field starts on the pad.
        val again = field()
        assertEquals(Page.KANA, again.keys.page)
        again.service.onDestroy()
    }

    /** 0.8.0 saved "ja" for Japanese: that stays QWERTY. */
    @Test
    fun aSavedJaStaysQwerty() {
        Options(language = "ja").save(Options.prefs(app()))
        val f = field()
        assertEquals("ja", f.keys.language)
        assertEquals(Page.LETTERS, f.keys.page)
        assertTrue(f.keys.kana)
        f.service.onDestroy()
    }

    /** Without the dictionary, neither Japanese is offered, and あ isn't taken. */
    @Test
    fun noDictionaryNoPad() {
        val f = field(assets = false)
        assertEquals(listOf("vi", "en"), f.keys.languages)
        f.service.onLanguage(Options.FLICK)
        assertEquals("vi", f.keys.language)
        assertEquals(Page.LETTERS, f.keys.page)
        f.service.onDestroy()
    }

    /**
     * わ, た and し (さ flicked left) compose わたし, and the keys say so: 変換 on the space, 確定
     * on Enter. Space converts to 私, Enter commits it with no new line, and the keys say 空白
     * again.
     */
    @Test
    fun flickedKanaComposeConvertAndCommit() {
        val f = field()
        f.service.onLanguage(Options.FLICK)
        f.tap("わ")
        f.tap("た")
        f.kana("し")
        assertEquals("わたし", f.edit.text.toString())
        assertEquals("わたし", f.composing)
        assertTrue(f.keys.composing)
        assertEquals("変換", f.keys.spaceLabel)
        assertEquals("確定", f.keys.enterLabel)
        val s = f.strip() as Strip.Candidates
        assertEquals("私", s.centre)
        f.tap("SPACE")
        assertEquals("私", f.edit.text.toString())
        assertTrue((f.strip() as Strip.Candidates).highlight)
        f.tap("ENTER")
        assertEquals("私", f.edit.text.toString())
        assertEquals("", f.composing)
        assertFalse(f.keys.composing)
        assertEquals("空白", f.keys.spaceLabel)
        assertNull(f.keys.enterLabel)
        // Space with nothing composing: a full-width space.
        f.tap("SPACE")
        assertEquals("私　", f.edit.text.toString())
        f.service.onDestroy()
    }

    /**
     * The same key tapped again steps its kana (あ い う), ↺ steps back, ゛゜小 changes the last
     * kana's form; after a pause the key types a kana of its own.
     */
    @Test
    fun theToggleUndoAndMarkKeysChangeTheLastKana() {
        val f = field()
        f.service.onLanguage(Options.FLICK)
        f.tap("か")
        repeat(2) { f.tap("あ") }
        assertEquals("かい", f.composing)
        f.tap("あ")
        assertEquals("かう", f.composing)
        f.tap("UNDO")
        assertEquals("かい", f.composing)
        f.tap("゛゜小")
        assertEquals("かぃ", f.composing)
        f.tap("゛゜小")
        assertEquals("かい", f.composing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        f.tap("あ")
        assertEquals("かいあ", f.composing)
        // ⌫ takes the last kana; ↺ on one no key's toggle has (が) does nothing.
        repeat(2) { f.act { f.service.onBackspace() } }
        f.tap("゛゜小")
        assertEquals("が", f.composing)
        f.tap("UNDO")
        assertEquals("が", f.composing)
        f.service.onDestroy()
    }

    /** With the toggle off in the settings, each tap is a kana of its own. */
    @Test
    fun theToggleCanBeTurnedOff() {
        Options(language = Options.FLICK, kanaToggle = false).save(Options.prefs(app()))
        val f = field()
        assertEquals(Page.KANA, f.keys.page)
        repeat(2) { f.tap("あ") }
        assertEquals("ああ", f.composing)
        f.service.onDestroy()
    }

    /** The punctuation key's 、 joins the reading and converts with it; 、 then 。 by its toggle. */
    @Test
    fun punctuationConvertsWithTheReading() {
        val f = field()
        f.service.onLanguage(Options.FLICK)
        f.tap("か")
        f.kana("み")
        f.tap("、。?!")
        assertEquals("かみ、", f.composing)
        f.tap("SPACE")
        assertEquals("紙、", f.edit.text.toString())
        f.tap("ENTER")
        f.tap("、。?!")
        f.tap("、。?!")
        assertEquals("。", f.composing)
        f.tap("ENTER")
        assertEquals("紙、。", f.edit.text.toString())
        f.service.onDestroy()
    }

    /**
     * Password fields get plain letters on QWERTY, number fields their pad: the 12-key pad is
     * for text that converts.
     */
    @Test
    fun passwordAndNumberFieldsKeepTheirKeys() {
        Options(language = Options.FLICK).save(Options.prefs(app()))
        val p = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(Page.LETTERS, p.keys.page)
        assertFalse(p.keys.kana)
        p.act { p.service.onText("k") }
        assertEquals("k", p.edit.text.toString())
        p.service.onDestroy()
        val n = field(InputType.TYPE_CLASS_NUMBER)
        assertEquals(Page.NUMBER, n.keys.page)
        n.service.onDestroy()
    }

    /** The pad's ◀ ends the reading as it is, as the space bar's slide does. */
    @Test
    fun leftEndsTheReading() {
        val f = field()
        f.service.onLanguage(Options.FLICK)
        f.tap("か")
        val key = Flick.rows[1].keys.first()
        assertEquals(KeyKind.LEFT, key.kind)
        f.act {
            f.service.onCursorStart()
            f.service.onCursor(-1, 0)
            f.service.onCursorEnd()
        }
        assertEquals("か", f.edit.text.toString())
        assertEquals("", f.composing)
        f.service.onDestroy()
    }
}
