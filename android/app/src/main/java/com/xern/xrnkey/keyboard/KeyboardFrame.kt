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
}
