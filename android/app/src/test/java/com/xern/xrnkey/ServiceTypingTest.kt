package com.xern.xrnkey

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.xern.xrnkey.ime.XrnkeyService
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The keyboard service end to end, as on the phone: its own models (the ones the APK bundles),
 * its settings, its typist, typing into a real text field through an InputConnection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceTypingTest {
    private fun field(): Pair<XrnkeyService, EditText> {
        // The models the APK bundles (the release build copies them from this folder).
        XrnkeyService.modelFiles = java.io.File(TestModels.realDir, "vi-phone.xklm").path to
            java.io.File(TestModels.realDir, "en.xklm").path
        val s = Robolectric.setupService(XrnkeyService::class.java)
        XrnkeyService.modelFiles = null
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
