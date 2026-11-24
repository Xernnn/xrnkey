package com.xern.xrnkey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.xern.xrnkey.ime.JustCopied
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
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
 * The strip's chip of a text just copied, as Gboard's: it shows while the strip has nothing to
 * suggest, for about a minute, until it is pasted or anything is typed; never for a sensitive
 * clip, in a password field, or with the clipboard history off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ClipChipTest {
    private fun app() = RuntimeEnvironment.getApplication()

    private class Ime(val service: XrnkeyService, val keys: KeyboardView, val edit: EditText)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The keyboard on a text field ([type] its input type), typing into a real EditText; with
     * [models], the models the APK bundles, opened as on the phone.
     */
    private fun ime(type: Int = InputType.TYPE_CLASS_TEXT, options: Options = Options(), models: TestModels.Packed? = null): Ime {
        options.save(Options.prefs(app()))
        if (models != null) {
            XrnkeyService.modelAssets = { name ->
                val (at, length) = models.places[name] ?: throw java.io.FileNotFoundException(name)
                android.content.res.AssetFileDescriptor(
                    android.os.ParcelFileDescriptor.open(models.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY), at, length,
                )
            }
        }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelAssets = null
        val frame = s.onCreateInputView() as ViewGroup
        val edit = EditText(app())
        start(s, edit, type)
        val keys = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()
        return Ime(s, keys, edit)
    }

    /** A field takes the keyboard, as the framework starts one. */
    private fun start(s: XrnkeyService, edit: EditText, type: Int) {
        val info = EditorInfo().apply { inputType = type }
        // The EditText fills in the EditorInfo from its own type.
        edit.inputType = type
        val ic = edit.onCreateInputConnection(info)!!
        val started = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        started.isAccessible = true
        started.set(s, ic)
        s.onStartInputView(info, false)
        idle()
    }

    private fun copy(text: String, sensitive: Boolean = false) {
        val cm = app().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(XrnkeyService.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        idle()
    }

    @Test
    fun aCopyShowsAChipThatPastesIt() {
        val ime = ime()
        assertEquals(Strip.Tools, ime.keys.strip)
        copy("Hẹn 7 giờ ở quán cũ")
        assertEquals(Strip.Copied("Hẹn 7 giờ ở quán cũ"), ime.keys.strip)
        ime.service.onCopiedChip()
        idle()
        assertEquals("Hẹn 7 giờ ở quán cũ", ime.edit.text.toString())
        // Pasted: gone.
        assertTrue(ime.keys.strip !is Strip.Copied)
        ime.service.onDestroy()
    }

    /** A tap on the chip pastes; ≡ beside it brings the tool row, as ≡ always does. */
    @Test
    fun theChipIsTappedOnTheStrip() {
        val ime = ime()
        ime.keys.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        ime.keys.layout(0, 0, 1080, ime.keys.measuredHeight)
        copy("made-up order ABC-12345")
        val chip = ime.keys.copiedChip!!
        // After ≡ (48 dp at 3 px a dp), no wider than the room between ≡ and the emoji button.
        assertEquals(144f, chip.left, 1f)
        assertTrue(chip.right < 1080 - 144)
        tapStrip(ime.keys, 40f)
        assertEquals(Strip.Tools, ime.keys.strip)
        // The chip again (still nothing typed), then a tap on it.
        ime.service.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        idle()
        tapStrip(ime.keys, ime.keys.copiedChip!!.centerX())
        idle()
        assertEquals("made-up order ABC-12345", ime.edit.text.toString())
        ime.service.onDestroy()
    }

    private fun tapStrip(v: KeyboardView, x: Float) {
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, 60f, 0)
        val up = android.view.MotionEvent.obtain(0, 10, android.view.MotionEvent.ACTION_UP, x, 60f, 0)
        v.onTouchEvent(down)
        v.onTouchEvent(up)
    }

    @Test
    fun theChipLastsAboutAMinute() {
        val ime = ime()
        copy("một phút thôi")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertEquals(Strip.Copied("một phút thôi"), ime.keys.strip)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(Strip.Tools, ime.keys.strip)
        // A field started later doesn't bring it back.
        start(ime.service, ime.edit, InputType.TYPE_CLASS_TEXT)
        assertEquals(Strip.Tools, ime.keys.strip)
        ime.service.onDestroy()
    }

    @Test
    fun typingEndsTheChip() {
        val ime = ime()
        copy("copied")
        ime.service.onText("a")
        idle()
        ime.service.onBackspace()
        idle()
        assertEquals("", ime.edit.text.toString())
        // The strip has nothing to suggest again, and the chip stays gone.
        assertEquals(Strip.Tools, ime.keys.strip)
        // Nor does the next field bring it back.
        start(ime.service, ime.edit, InputType.TYPE_CLASS_TEXT)
        assertEquals(Strip.Tools, ime.keys.strip)
        ime.service.onDestroy()
    }

    /** It shows in the next field too, where the owner is likely to paste it. */
    @Test
    fun theChipFollowsToTheNextField() {
        val ime = ime()
        copy("địa chỉ: 12 Hoa Sữa")
        start(ime.service, EditText(app()), InputType.TYPE_CLASS_TEXT)
        assertEquals(Strip.Copied("địa chỉ: 12 Hoa Sữa"), ime.keys.strip)
        ime.service.onDestroy()
    }

    /** Folded away, the tool row's place is the bare strip: the chip goes between ≡ and emoji. */
    @Test
    fun theFoldedToolRowShowsTheChipToo() {
        val ime = ime(options = Options(toolsHidden = true))
        assertEquals(XrnkeyService.BARE, ime.keys.strip)
        copy("hello")
        assertEquals(Strip.Copied("hello"), ime.keys.strip)
        ime.service.onDestroy()
    }

    /** A word in progress, or the next words after it, are better: the chip waits its turn. */
    @Test
    fun suggestionsComeFirst() {
        TestModels.packed().use { models ->
            val ime = ime(models = models)
            ime.service.onText("k")
            idle()
            assertTrue(ime.keys.strip is Strip.Candidates)
            val typing = ime.keys.strip
            copy("later")
            assertEquals(typing, ime.keys.strip)
            "hong ".forEach { ime.service.onText(it.toString()) }
            idle()
            copy("later")
            assertTrue("next words: ${ime.keys.strip}", ime.keys.strip is Strip.Candidates)
            ime.service.onDestroy()
        }
        // A word in progress with nothing to suggest for it: still no chip.
        val ime = ime(options = Options(suggestions = false))
        ime.service.onText("k")
        idle()
        copy("later")
        assertEquals(Strip.Tools, ime.keys.strip)
        ime.service.onDestroy()
    }

    @Test
    fun noChipInPasswordFields() {
        val ime = ime()
        copy("copied before")
        start(ime.service, ime.edit, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(Strip.Tools, ime.keys.strip)
        // Nor for what is copied while one has the keyboard, which also ends the chip before:
        // that text isn't on the clipboard any more.
        copy("copied in a password field")
        assertEquals(Strip.Tools, ime.keys.strip)
        start(ime.service, ime.edit, InputType.TYPE_CLASS_TEXT)
        assertEquals(Strip.Tools, ime.keys.strip)
        ime.service.onDestroy()
    }

    @Test
    fun noChipForSensitiveClips() {
        val ime = ime()
        copy("copied before")
        copy("hunter2-made-up", sensitive = true)
        assertEquals(Strip.Tools, ime.keys.strip)
        ime.service.onDestroy()
    }

    @Test
    fun noChipWithTheHistoryOff() {
        val ime = ime(options = Options(clipboard = false))
        copy("not kept")
        assertEquals(Strip.Tools, ime.keys.strip)
        ime.service.onDestroy()
    }

    /** The rule's clock on its own: a minute from the copy, or until cleared. */
    @Test
    fun theMinuteOnItsOwn() {
        var now = 5_000L
        val c = JustCopied { now }
        assertNull(c.current())
        c.copied("x")
        now += JustCopied.LIFE - 1
        assertEquals("x", c.current())
        now += 1
        assertNull(c.current())
        c.copied("y")
        c.clear()
        assertNull(c.current())
    }
}
