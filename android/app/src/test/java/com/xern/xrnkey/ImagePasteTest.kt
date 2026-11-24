package com.xern.xrnkey

import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.View.MeasureSpec
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import com.xern.xrnkey.clip.Clip
import com.xern.xrnkey.clip.ClipHistory
import com.xern.xrnkey.clip.ClipImages
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.ClipboardPanelView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * An image from the clipboard history goes into a field through commitContent, as a FileProvider
 * URI the app is granted to read, and only where the field takes that type
 * (EditorInfo.contentMimeTypes); elsewhere a tap on its card does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePasteTest {
    private fun app() = RuntimeEnvironment.getApplication()

    /** What the field was given: each commitContent's URI, type and flags. */
    private val given = mutableListOf<String>()

    private fun connection(target: InputConnection) = object : InputConnectionWrapper(target, true) {
        override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
            given += "${info.contentUri} ${info.description.getMimeType(0)} $flags"
            return true
        }
    }

    /** The keyboard on a field that takes [accepts] (none: text only). */
    private fun keyboardOn(vararg accepts: String): XrnkeyService {
        val s = Robolectric.setupService(XrnkeyService::class.java)
        s.onCreateInputView()
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        if (accepts.isNotEmpty()) EditorInfoCompat.setContentMimeTypes(info, arrayOf(*accepts))
        val ic = connection(BaseInputConnection(EditText(app()), true))
        val started = android.inputmethodservice.InputMethodService::class.java.getDeclaredField("mStartedInputConnection")
        started.isAccessible = true
        started.set(s, ic)
        s.onStartInputView(info, false)
        return s
    }

    /** A screenshot in the history, as a copy leaves it. */
    private fun screenshot(s: XrnkeyService): Clip {
        val b = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val images = ClipImages(File(s.filesDir, ClipImages.DIR))
        val history = ClipHistory(File(s.filesDir, XrnkeyService.CLIPS), images)
        val kept = images.store(ByteArrayInputStream(png), "image/png")!!
        history.addImage(kept.name, kept.mime, 1)
        return Clip("", 1, image = kept.name, mime = kept.mime)
    }

    @Test
    fun aFieldThatTakesTheTypeIsGivenTheImage() {
        for (accepts in listOf("image/png", "image/*")) {
            given.clear()
            val s = keyboardOn(accepts, "text/plain")
            val clip = screenshot(s)
            s.onPasteImage(clip)
            assertEquals(1, given.size)
            val (uri, type, flags) = given.single().split(' ')
            assertEquals("content://com.xern.xrnkey.clips/images/${clip.image}", uri)
            assertEquals("image/png", type)
            assertEquals(InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION.toString(), flags)
            s.onDestroy()
        }
    }

    @Test
    fun aFieldThatDoesntTakeItIsGivenNothing() {
        for (accepts in listOf(arrayOf("image/gif"), arrayOf("video/*", "text/plain"), emptyArray())) {
            given.clear()
            val s = keyboardOn(*accepts)
            s.onPasteImage(screenshot(s))
            assertTrue(accepts.joinToString(), given.isEmpty())
            s.onDestroy()
        }
    }

    @Test
    fun whatAFieldTakes() {
        val info = EditorInfo()
        assertFalse(XrnkeyService.canInsert(info, "image/png"))
        EditorInfoCompat.setContentMimeTypes(info, arrayOf("image/gif", "image/png"))
        assertTrue(XrnkeyService.canInsert(info, "image/png"))
        assertFalse(XrnkeyService.canInsert(info, "image/jpeg"))
        EditorInfoCompat.setContentMimeTypes(info, arrayOf("image/*"))
        assertTrue(XrnkeyService.canInsert(info, "image/jpeg"))
        assertFalse(XrnkeyService.canInsert(null, "image/jpeg"))
    }

    /** The panel: an image's card pastes it where it can, and does nothing where it can't. */
    @Test
    fun thePanelOffersAnImageOnlyWhereItCanGo() {
        val events = mutableListOf<String>()
        val p = ClipboardPanelView(app())
        val image = Clip("", 3, image = "0a1b.png", mime = "image/png")
        p.clips = listOf(image, Clip("hai", 2))
        p.listener = object : ClipboardPanelView.Listener {
            override fun onPaste(text: String) { events += "paste $text" }
            override fun onPasteImage(clip: Clip) { events += "image ${clip.image}" }
            override fun onPin(key: String, pinned: Boolean) { events += "pin ${key == image.key} $pinned" }
            override fun onDelete(key: String) { events += "delete ${key == image.key}" }
        }
        p.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(954, MeasureSpec.EXACTLY))
        p.layout(0, 0, 1080, 954)
        // dp = 3: header 144 px, gap 24, cards 252 px tall, two to a row.
        p.tap(200f, 250f)
        p.canPaste = { it.image == null }
        p.tap(200f, 250f)
        p.tap(800f, 250f)
        p.hold(0)
        p.tap(270f - 90f, 168f + 126f)  // pin, left of the card's centre
        p.hold(0)
        p.tap(270f + 90f, 168f + 126f)  // delete, right of it
        assertEquals(listOf("image 0a1b.png", "paste hai", "pin true true", "delete true"), events)
    }
}
