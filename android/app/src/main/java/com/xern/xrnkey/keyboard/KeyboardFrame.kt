package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
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
        // Every dispatch, the first and every change after it: on the phone the first show
        // could come before the window's insets, and the band has to follow when they arrive.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            setBand(insets)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * The keyboard's window puts the input view inside a layout of its own whose top view
     * fits the system windows: it took the insets and padded itself, so the keyboard got none,
     * and the bottom row could sit on the gesture bar (0.3.1). The band is the keyboard's, filled
     * in its colour: those views stop fitting, and the insets come here instead. Called when the
     * frame joins the window and when a field starts.
     */
    fun claimInsets() {
        var p = parent
        var changed = false
        while (p is View) {
            if (p.fitsSystemWindows) {
                p.fitsSystemWindows = false
                p.setPadding(p.paddingLeft, p.paddingTop, p.paddingRight, 0)
                changed = true
            }
            p = p.parent
        }
        // What the window has now, in case it came before this frame could take it.
        ViewCompat.getRootWindowInsets(this)?.let { setBand(it) }
        if (changed) ViewCompat.requestApplyInsets(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        claimInsets()
    }

    private fun setBand(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(BAND)
        if (bars.left != paddingLeft || bars.right != paddingRight || bars.bottom != paddingBottom || paddingTop != 0) {
            setPadding(bars.left, 0, bars.right, bars.bottom)
            requestLayout()
        }
    }

    private companion object {
        /** The bar (the hide-keyboard arrow, the pill) and the gesture area: the larger of them. */
        val BAND = WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.mandatorySystemGestures() or
            WindowInsetsCompat.Type.tappableElement()
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
