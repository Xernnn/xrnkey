package com.xern.xrnkey

import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.EmojiGrid
import com.xern.xrnkey.keyboard.EmojiPanelView
import com.xern.xrnkey.keyboard.EmojiTab
import com.xern.xrnkey.keyboard.Tool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The strip's emoji in the keyboard service, typing into a real text field: the APK bundles the
 * keyword table beside the emoji index, and a tap edits the field as its keyword says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)  // the panel lists only emoji the font can draw
class EmojiServiceTest {
    private val packed = TestModels.packed()
    private lateinit var frame: ViewGroup

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
        frame = s.onCreateInputView() as ViewGroup
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

    /**
     * The emoji panel opens on smileys and people while there are no recents, and on recents
     * after; a tap types into the field and leaves the panel open for the next.
     */
    @Test
    fun theEmojiPanelOpensOnRecentsOnceThereAreAnyAndATapKeepsItOpen() {
        val (s, edit) = field()
        val panel = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<EmojiPanelView>().single()
        s.onTool(Tool.EMOJI)
        assertEquals(View.VISIBLE, panel.visibility)
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        assertEquals(EmojiGrid.SMILEYS, panel.page)
        val x = panel.cellWidth() / 2
        val y = panel.gridTop() + panel.rowHeight() / 2
        panel.tap(x, y)
        panel.tap(x, y)
        assertEquals("😀😀", edit.text.toString())
        assertEquals(View.VISIBLE, panel.visibility)
        s.onClosePanel()
        s.onTool(Tool.EMOJI)
        assertEquals(EmojiGrid.RECENTS, panel.page)
        assertEquals("😀", panel.pages[EmojiGrid.RECENTS].cells.first().text)
        s.onDestroy()
    }

    /** A kaomoji types into the field and goes first in the kaomoji tab's recents, not the emoji's. */
    @Test
    fun aKaomojiGoesIntoTheFieldAndIntoItsOwnTabsRecents() {
        val (s, edit) = field()
        val panel = (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<EmojiPanelView>().single()
        s.onTool(Tool.EMOJI)
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        panel.tab = EmojiTab.EMOTICONS
        assertEquals(EmojiGrid.SMILEYS, panel.page)
        panel.tap(panel.cellWidth() / 2, panel.gridTop() + panel.rowHeight() / 2)
        assertEquals(":)", edit.text.toString())
        s.onClosePanel()
        s.onTool(Tool.EMOJI)
        assertEquals(EmojiTab.EMOTICONS, panel.tab)
        assertEquals(EmojiGrid.RECENTS, panel.page)
        assertEquals(listOf(":)"), panel.pages[EmojiGrid.RECENTS].cells.map { it.text })
        panel.tab = EmojiTab.EMOJI
        assertEquals(EmojiGrid.SMILEYS, panel.page)
        assertEquals(0, panel.pages[EmojiGrid.RECENTS].cells.size)
        s.onDestroy()
    }
}
