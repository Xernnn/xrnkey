package com.xern.xrnkey

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.keyboard.ClipboardPanelView
import com.xern.xrnkey.keyboard.EmojiPanelView
import com.xern.xrnkey.keyboard.EmojiTab
import com.xern.xrnkey.keyboard.Hand
import com.xern.xrnkey.keyboard.KeyboardFrame
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Strip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The one-handed keyboard drawn by the real views, against each edge, dark and light: the
 * letters, the symbols and the number pad, the 12-key pad, the comma's menu, the cursor pad, and
 * the emoji and clipboard panels. Saved as PNGs to -Pxrnkey.shots=<dir> (else build/shots).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OneHandShotsTest {
    private val out = File(System.getProperty("xrnkey.shots") ?: "build/shots")

    private class Keyboard(val frame: KeyboardFrame, val keys: KeyboardView, val emoji: EmojiPanelView, val clips: ClipboardPanelView)

    /** The frame as the service builds it, in a window, one-handed against [hand]. */
    private fun keyboard(palette: Palette, hand: Hand): Keyboard {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = KeyboardFrame(activity)
        frame.palette = palette
        val keys = KeyboardView(activity).also { it.palette = palette }
        val emoji = EmojiPanelView(activity).also { it.palette = palette; it.visibility = View.GONE }
        val clips = ClipboardPanelView(activity).also { it.palette = palette; it.visibility = View.GONE }
        frame.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (v in listOf(emoji, clips)) frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.hand = hand
        activity.setContentView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        settle()
        assertEquals(1080, frame.width)
        assertEquals(886, keys.width)
        return Keyboard(frame, keys, emoji, clips)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun shot(name: String, k: Keyboard) {
        settle()
        val v = k.frame
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val colours = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) colours += bmp.getPixel(x, y)
        assertTrue("$name looks blank", colours.size > 8)
        out.mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun each(body: (Keyboard, String) -> Unit) {
        for ((p, theme) in listOf(Palette.DARK to "dark", Palette.LIGHT to "light")) {
            for ((hand, side) in listOf(Hand.LEFT to "left", Hand.RIGHT to "right")) body(keyboard(p, hand), "$side-$theme")
        }
    }

    @Test
    fun lettersAndPads() = each { k, name ->
        shot("onehand-letters-$name", k)
        k.keys.page = Page.SYMBOLS
        shot("onehand-symbols-$name", k)
        k.keys.page = Page.NUMPAD
        shot("onehand-numpad-$name", k)
        k.keys.kana = true
        k.keys.page = Page.KANA
        shot("onehand-kana-$name", k)
    }

    @Test
    fun theStripsChipAndTheMenus() = each { k, name ->
        k.keys.strip = Strip.Copied("Hẹn 7 giờ tối thứ Sáu ở quán cà phê cũ nhé")
        shot("onehand-chip-$name", k)
        k.keys.strip = Strip.Tools
        // The comma held: its menu, inside the narrowed keys.
        k.keys.languages = listOf("vi", "en", "ja", "ja-flick")
        val unit = 886 / 10f
        val y = 144f + 4 * 180f + 90f
        val x = 1.5f * unit + 0.75f * unit  // the comma, after ?123 (1.5 keys)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        k.keys.onTouchEvent(down)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        shot("onehand-comma-menu-$name", k)
        k.keys.onTouchEvent(MotionEvent.obtain(0, 500, MotionEvent.ACTION_CANCEL, x, y, 0))
        k.keys.openCursorPad()
        shot("onehand-cursor-pad-$name", k)
    }

    @Test
    fun thePanels() {
        val index = EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!
        val all = index.all
        index.close()
        each { k, name ->
            k.emoji.emoji = all
            k.emoji.recents = listOf("😂", "❤️", "👍", "🙏", "🥰", "😭", "🎂", "☕", "🔥", "😅")
            k.emoji.open()
            k.emoji.tab = EmojiTab.EMOJI
            k.keys.visibility = View.INVISIBLE
            k.emoji.visibility = View.VISIBLE
            shot("onehand-emoji-$name", k)
            k.emoji.visibility = View.GONE
            k.clips.clips = ClipShots.clips
            k.clips.thumbnail = ClipShots::thumbnail
            k.clips.visibility = View.VISIBLE
            shot("onehand-clipboard-$name", k)
        }
    }
}
