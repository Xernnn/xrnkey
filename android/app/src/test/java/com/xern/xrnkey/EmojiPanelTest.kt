package com.xern.xrnkey

import android.content.Context
import android.view.MotionEvent
import android.view.View.MeasureSpec
import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.ime.EmojiMemory
import com.xern.xrnkey.keyboard.EmojiPanelView
import com.xern.xrnkey.keyboard.EmojiTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class EmojiPanelTest {
    private val typed = mutableListOf<String>()
    private var closed = 0
    private var searched = 0
    private var deleted = 0

    private fun panel(): EmojiPanelView {
        val p = EmojiPanelView(RuntimeEnvironment.getApplication())
        EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!.use { p.emoji = it.all }
        p.recents = listOf("🎂", "☕")
        p.suggestions = listOf("🥳", "🎉")
        p.listener = object : EmojiPanelView.Listener {
            override fun onEmoji(text: String) { typed += text }
            override fun onClosePanel() { closed++ }
            override fun onEmojiSearch() { searched++ }
            override fun onBackspace() { deleted++ }
        }
        p.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(954, MeasureSpec.EXACTLY))
        p.layout(0, 0, 1080, 954)
        return p
    }

    @Test
    fun tapsTypeSwitchTabsAndClose() {
        val p = panel()
        val dp = 3f
        p.tap(450f, 220f)                      // the first suggestion (two, centred)
        p.tap(10f, 340f)                       // the first recent
        p.tap(1060f, 940f)                     // Backspace
        p.tap(30f, 20f)                        // ABC
        p.tap(940f, 680f)                      // the search button
        assertEquals(listOf("🥳", "🎂"), typed)
        assertEquals(1, deleted)
        assertEquals(1, closed)
        assertEquals(1, searched)
        p.tap(64 * dp * 2 + 10, 20f)
        assertEquals(EmojiTab.EMOTICONS, p.tab)
        p.tap(30f, 200f)
        assertEquals(":)", typed.last())
    }

    /** A swipe across the grid used to type the emoji where the finger lifted (😘 on the phone). */
    @Test
    fun aSwipeOrAScrollTypesNothing() {
        val p = panel()
        fun touch(action: Int, x: Float, y: Float, t: Long) {
            val e = MotionEvent.obtain(0, t, action, x, y, 0)
            p.onTouchEvent(e)
            e.recycle()
        }
        // Sideways, over the recents row.
        touch(MotionEvent.ACTION_DOWN, 900f, 340f, 0)
        touch(MotionEvent.ACTION_MOVE, 550f, 340f, 16)
        touch(MotionEvent.ACTION_MOVE, 200f, 340f, 32)
        touch(MotionEvent.ACTION_UP, 200f, 340f, 48)
        // Up and down, ending where it began.
        touch(MotionEvent.ACTION_DOWN, 10f, 340f, 100)
        touch(MotionEvent.ACTION_MOVE, 10f, 600f, 116)
        touch(MotionEvent.ACTION_MOVE, 10f, 340f, 132)
        touch(MotionEvent.ACTION_UP, 10f, 340f, 148)
        assertEquals(emptyList<String>(), typed)
        // A tap still types.
        touch(MotionEvent.ACTION_DOWN, 10f, 340f, 200)
        touch(MotionEvent.ACTION_UP, 10f, 340f, 260)
        assertEquals(listOf("🎂"), typed)
    }

    @Test
    fun recentsComeFirstWithoutRepeatsAndTonesAreKept() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("t", Context.MODE_PRIVATE)
        val m = EmojiMemory(prefs)
        m.addRecent("😀"); m.addRecent("🎂"); m.addRecent("😀")
        assertEquals(listOf("😀", "🎂"), m.recents())
        repeat(60) { m.addRecent("x$it") }
        assertEquals(EmojiMemory.RECENTS, m.recents().size)
        m.setTone("👍", "👍🏽")
        m.setTone("👋", "👋🏿")
        assertEquals(mapOf("👍" to "👍🏽", "👋" to "👋🏿"), EmojiMemory(prefs).tones())
        assertTrue(EmojiMemory(prefs).recents().first() == "x59")
    }
}
