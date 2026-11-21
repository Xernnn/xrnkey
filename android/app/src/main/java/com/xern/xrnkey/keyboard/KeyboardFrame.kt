package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The input view: the keys and the panels that take their place. Android 15 draws the keyboard's
 * window edge to edge, so the navigation bar (the hide-keyboard button and the gesture pill)
 * lies over the bottom of it. The frame keeps the keys above the bar and the gesture area, and
 * fills the band behind them with the keyboard's own background, as other keyboards do.
 * Every panel is exactly as tall as the keys it replaces.
 */
@SuppressLint("ViewConstructor")
class KeyboardFrame(context: Context) : FrameLayout(context) {
    var palette: Palette = Palette.DARK
        set(value) { field = value; setBackgroundColor(value.background) }

    init {
        setBackgroundColor(palette.background)
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.mandatorySystemGestures(),
            )
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * The keys set the height, and a panel takes exactly theirs. The keyboard's window offers
     * the input view up to the screen's height; a panel measured against that took all of it,
     * and the window grew to the top of the screen, over the app.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keys = (0 until childCount).map { getChildAt(it) }.firstOrNull { it is KeyboardView }
        if (keys == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = MeasureSpec.makeMeasureSpec(maxOf(0, width - paddingLeft - paddingRight), MeasureSpec.EXACTLY)
        // Measured even while a panel hides them: they are what the panel replaces.
        keys.measure(inner, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = keys.measuredHeight
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === keys || child.visibility == GONE) continue
            child.measure(inner, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height + paddingTop + paddingBottom)
    }
}
