package com.xern.xrnkey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.xern.xrnkey.clip.ClipHistory
import com.xern.xrnkey.ime.OnDevice
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun app() = RuntimeEnvironment.getApplication()

    @Test
    fun theDefaultsAreTheOwnersAndSurviveARoundTrip() {
        val d = Options()
        // Full Telex, old-style tones, no underline, suggestions with emoji, personal words,
        // flexible auto-correct, non-Vietnamese words as typed, double space, number row on Auto,
        // digits on the top row, key popup, 400 ms, the space bar and comma gestures, clipboard.
        assertFalse(d.vni || d.modernTones || d.underline)
        assertTrue(d.suggestions && d.emojiSuggestions && d.learn && d.keepForeign && d.doubleSpace)
        assertEquals(1, d.correction)
        assertEquals("auto", d.numberRow)
        assertTrue(d.holdDigits && d.keyPopup && d.spaceSwipe && d.spaceHold && d.commaHold && d.clipboard && d.shortcuts)
        assertEquals(400, d.holdMillis)
        val p = app().getSharedPreferences("o", Context.MODE_PRIVATE)
        assertEquals(d, Options.load(p))  // nothing saved yet: the defaults
        val changed = d.copy(vni = true, correction = 2, numberRow = "off", holdMillis = 300, height = 110, theme = "light")
        changed.save(p)
        assertEquals(changed, Options.load(p))
        p.edit().putString("number_row", "sideways").putInt("correction", 9).putInt("hold_ms", 5).commit()
        val coerced = Options.load(p)
        assertEquals("auto", coerced.numberRow)
        assertEquals(2, coerced.correction)
        assertEquals(150, coerced.holdMillis)
    }

    @Test
    fun theKeyboardTakesTheSettingsWhenAFieldStarts() {
        Options(numberRow = "off", keyPopup = false, holdMillis = 300, holdDigits = false, spaceSwipe = false,
            commaHold = false, height = 120, clipboard = false, theme = "light").save(Options.prefs(app()))
        val s = Robolectric.setupService(XrnkeyService::class.java)
        val frame = s.onCreateInputView() as ViewGroup
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        val keys = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        assertFalse(keys.numberRow)
        assertFalse(keys.keyPreview)
        assertEquals(300L, keys.holdMillis)
        assertFalse(keys.holdDigits)
        assertFalse(keys.spaceSwipe)
        assertTrue(keys.spaceHold)
        assertFalse(keys.commaHold)
        assertEquals(Palette.LIGHT, keys.palette)
        keys.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // 48 dp of strip and four rows of 60 dp at 120 %, at 3 px a dp.
        assertEquals(((48 + 4 * 60 * 1.2) * 3).toInt(), keys.measuredHeight)
        // The clipboard history is off: nothing copied is kept.
        val cm = app().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("", "kept nowhere"))
        assertTrue(ClipHistory(File(s.filesDir, "clips.tsv")).clips.isEmpty())
        s.onDestroy()
    }

    @Test
    fun clearedWordsAreNotSavedBackByTheKeyboard() {
        val prefs = app().getSharedPreferences("w", Context.MODE_PRIVATE)
        OnDevice(tmp.root, prefs).use { od ->
            od.refresh(now = 1_000)
            od.words!!.learn("<s>", "alo")
            od.save()
            od.words!!.learn("alo", "alo")  // learned, not saved yet
            OnDevice.clearWords(tmp.root, prefs, now = 2_000)
            od.save()  // the keyboard leaves a field after the clear
            assertFalse(od.wordsFile.exists())
        }
    }

    @Test
    fun theSettingsScreen() {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2742, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2742)
        val bmp = Bitmap.createBitmap(1080, 2742, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        dir.mkdirs()
        File(dir, "settings-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theNewOptionsRoundTripAndDefaultToTheOwners() {
        val d = Options()
        // The owner's Laban Key: tones after the word, simple Telex (w alone stays w).
        assertTrue(d.freeMarking && !d.wShortcut && d.nextWord)
        val p = app().getSharedPreferences("n", Context.MODE_PRIVATE)
        val changed = d.copy(freeMarking = false, wShortcut = true, nextWord = false)
        changed.save(p)
        assertEquals(changed, Options.load(p))
    }

    @Test
    fun wTypesUOnlyWhenAsked() {
        com.xern.xrnkey.engine.TypingState().use { s ->
            val f = FakeEditor()
            val t = com.xern.xrnkey.ime.Typist(s, f).also { it.reset() }
            t.text("tw ")
            assertEquals("tư ", f.text)
            s.setOption(com.xern.xrnkey.engine.TypingState.OPT_W_SHORTCUT, false)
            t.text("tw ")
            assertEquals("tư tw ", f.text)
        }
    }

    @Test
    fun nextWordsCanBeTurnedOffOnTheirOwn() {
        TestModels.test().use { m ->
            com.xern.xrnkey.engine.TypingState().use { s ->
                s.useModels(m.models)
                val t = com.xern.xrnkey.ime.Typist(s, FakeEditor(), m.models).also { it.tonesOnSpace = com.xern.xrnkey.ime.Typist.TONES_ALWAYS; it.reset() }
                val strip = com.xern.xrnkey.ime.StripMaker(m.models)
                t.text("Tooi khoong ")
                assertTrue(strip.make(t) is com.xern.xrnkey.keyboard.Strip.Candidates)
                strip.nextWords = false
                assertEquals(com.xern.xrnkey.keyboard.Strip.Tools, strip.make(t))
                t.text("ddi")
                assertTrue(strip.make(t) is com.xern.xrnkey.keyboard.Strip.Candidates)  // a word in progress still is
            }
        }
    }

    @Test
    fun spaceAddsNoTonesUntilAsked() {
        // A new setting: every install, new or updated from 0.3, starts with Laban's behaviour.
        val p = app().getSharedPreferences("tones", Context.MODE_PRIVATE)
        assertEquals(com.xern.xrnkey.ime.Typist.TONES_OFF, Options.load(p).tonesOnSpace)
        assertFalse(Options.load(p).autoCap)
        Options().copy(tonesOnSpace = 2, autoCap = true).save(p)
        assertEquals(2, Options.load(p).tonesOnSpace)
        assertTrue(Options.load(p).autoCap)
        p.edit().putInt("tones_on_space", 7).commit()
        assertEquals(2, Options.load(p).tonesOnSpace)
    }

    @Test
    fun autoCapitaliseShiftsTheFirstLetterOfASentence() {
        com.xern.xrnkey.engine.TypingState().use { s ->
            val f = FakeEditor()
            val t = com.xern.xrnkey.ime.Typist(s, f).also { it.reset() }
            assertTrue(t.atSentenceStart())
            t.text("xin")
            assertFalse(t.atSentenceStart())
            t.text(" chao. ")
            assertTrue(t.atSentenceStart())
            t.text("ok ")
            assertFalse(t.atSentenceStart())
        }
    }
}
