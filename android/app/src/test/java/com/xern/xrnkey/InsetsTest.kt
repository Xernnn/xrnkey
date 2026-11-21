package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.xern.xrnkey.ime.XrnkeyService
import com.xern.xrnkey.keyboard.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Android 15 draws the keyboard's window edge to edge: the navigation bar (its hide-keyboard
 * button and the gesture pill) lies over the bottom of the input view. The keys must stay above
 * it. The phone that showed it: 1080 px wide at 2.5 px a dp, a 54 dp navigation band.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsetsTest {
    private val nav = 135  // px: the phone's navigation band under a keyboard

    private fun insets(bottom: Int, gestures: Int = bottom / 2, top: Int = 0) = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
        .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, top, 0, gestures))
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
        .build()

    private fun inputView(): Pair<XrnkeyService, ViewGroup> {
        val s = Robolectric.setupService(XrnkeyService::class.java)
        val frame = s.onCreateInputView() as ViewGroup
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        ViewCompat.dispatchApplyWindowInsets(frame, insets(nav))
        val width = RuntimeEnvironment.getApplication().resources.displayMetrics.widthPixels
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        return s to frame
    }

    private fun keys(frame: ViewGroup) =
        (0 until frame.childCount).map { frame.getChildAt(it) }.filterIsInstance<KeyboardView>().single()

    @Test
    fun theKeysStayAboveTheNavigationBar() {
        val (s, frame) = inputView()
        val keys = keys(frame)
        // Every key lies inside the keyboard view, so its bottom edge bounds them all.
        assertTrue("keys end at ${keys.bottom}, the bar starts at ${frame.height - nav}", keys.bottom <= frame.height - nav)
        // The bottom row is where the finger finds it, just above the bar: Space types a space.
        val typed = mutableListOf<String>()
        keys.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed += text }
        }
        val y = (frame.height - nav - 20).toFloat() - keys.top
        val t = SystemClock.uptimeMillis()
        keys.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, frame.width / 2f, y, 0))
        keys.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, frame.width / 2f, y, 0))
        assertEquals(listOf(" "), typed)
        s.onDestroy()
    }

    @Test
    fun theKeyboardsBackgroundFillsTheBandBehindTheBar() {
        val (s, frame) = inputView()
        val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bmp))
        val band = bmp.getPixel(frame.width / 2, frame.height - nav / 2)
        val keyboard = bmp.getPixel(4, keys(frame).top + 4)  // the strip's edge
        assertTrue("the band is drawn", Color.alpha(band) == 255)
        assertTrue(keyboard != 0)
        shotWithBar("keyboard-dark", bmp)
        s.onDestroy()
    }

    /** The phone's navigation band drawn over the shot: the hide-keyboard arrow and the pill. */
    private fun shotWithBar(name: String, bmp: Bitmap) {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 240, 240) }
        val top = bmp.height - nav
        val tri = Path().apply {
            moveTo(140f, top + 55f); lineTo(180f, top + 55f); lineTo(160f, top + 85f); close()
        }
        c.drawPath(tri, p)
        c.drawRoundRect(bmp.width / 2f - 190f, bmp.height - 30f, bmp.width / 2f + 190f, bmp.height - 20f, 5f, 5f, p)
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun settingsKeepClearOfTheStatusBar() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        // As Android 15 has it for an app built for it: the window is edge to edge.
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        val root = activity.window.decorView
        val w = 1080
        val h = 2400
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            looper.idle()
        }
        layout()
        ViewCompat.dispatchApplyWindowInsets(root, insets(nav, top = 110))
        looper.idle()
        layout()
        // Scroll the list up, as on the phone where "Swipe space" ran into the clock.
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540f, 1900f, 0))
        for (i in 1..20) {
            looper.idleFor(java.time.Duration.ofMillis(16))
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + 16L * i, MotionEvent.ACTION_MOVE, 540f, 1900f - 60f * i, 0))
        }
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 16L * 21, MotionEvent.ACTION_UP, 540f, 700f, 0))
        looper.idleFor(java.time.Duration.ofMillis(2000))
        layout()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        // Nothing but the background in the status bar's 110 px.
        val bg = bmp.getPixel(w - 4, 4)
        var stray = 0
        for (y in 0 until 110 step 2) for (x in 0 until w step 2) if (bmp.getPixel(x, y) != bg) stray++
        val dir = System.getProperty("xrnkey.shots")
        if (dir != null) {
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f }
            c.drawText("23:52", 60f, 70f, p)
            File(dir).mkdirs()
            File(dir, "settings-scrolled-dark.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals("pixels drawn under the status bar", 0, stray)
    }
}
