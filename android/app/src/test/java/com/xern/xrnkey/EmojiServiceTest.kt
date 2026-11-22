package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.xern.xrnkey.ime.XrnkeyService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The strip's emoji in the keyboard service, typing into a real text field: the APK bundles the
 * keyword table beside the emoji index, and a tap edits the field as its keyword says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmojiServiceTest {
    private val packed = TestModels.packed()

    @After
    fun removePack() = packed.close()

    private fun field(): Pair<XrnkeyService, EditText> {
        XrnkeyService.modelAssets = { name ->
            val (at, length) = packed.places[name] ?: throw java.io.FileNotFoundException(name)
            AssetFileDescriptor(ParcelFileDescriptor.open(packed.file, ParcelFileDescriptor.MODE_READ_ONLY), at, length)
        }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelAssets = null
        val edit = EditText(RuntimeEnvironment.getApplication())
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val ic = edit.onCreateInputConnection(info)!!
        val start = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        start.isAccessible = true
        start.set(s, ic)
        s.onCreateInputView()
        s.onStartInputView(info, false)
        return s to edit
    }

    /** Types `text`, lets the strip follow, and taps its right slot. */
    private fun XrnkeyService.typeAndTapRight(text: String) {
        text.forEach { onText(it.toString()) }
        shadowOf(Looper.getMainLooper()).idle()
        onCandidate(2)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aTapOnTheRightSlotsEmojiEditsTheField() {
        val (s, edit) = field()
        s.typeAndTapRight("cam")
        assertEquals("🍊", edit.text.toString())  // in the word's place
        s.onDestroy()
        val (s2, edit2) = field()
        s2.typeAndTapRight("tooi buoonf")
        assertEquals("tôi buồn 😞", edit2.text.toString())  // after the word
        s2.onDestroy()
        val (s3, edit3) = field()
        s3.typeAndTapRight("ok :)")
        assertEquals("ok 🙂", edit3.text.toString())  // in the emoticon's place
        s3.onDestroy()
        val (s4, edit4) = field()
        s4.typeAndTapRight("uoongs caf phee")
        assertEquals("uống ☕", edit4.text.toString())  // both syllables
        s4.onDestroy()
    }
}
