package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The input view: the keys and the panels that take their place. Android 15 draws the keyboard's
 * window edge to edge, so the navigation bar (the hide-keyboard button and the gesture pill)
 * lies over the bottom of it. The frame keeps the keys above the bar and the gesture area, and
 * fills the band behind them with the keyboard's own background, as other keyboards do.
 * Every panel is exactly as tall as the keys it replaces.
 *
 * One-handed ([hand]), the keys and every panel are [ONE_HAND] of the width, against one edge,
 * and the free column on the other side holds the [OneHandBar].
 */
@SuppressLint("ViewConstructor")
class KeyboardFrame(context: Context) : FrameLayout(context) {
    var palette: Palette = Palette.DARK
        set(value) { field = value; setBackgroundColor(value.background); bar.palette = value }

    /** The column beside the one-handed keyboard (gone at full width). */
    val bar = OneHandBar(context).also { it.visibility = GONE }

    /** One-handed, the side the keyboard sits against; null: full width. */
    var hand: Hand? = null
        set(value) {
            if (field == value) return
            field = value
            bar.visibility = if (value == null) GONE else VISIBLE
            if (value != null) bar.hand = value
            requestLayout()
        }

    /**
     * The window's own insets, from the window manager rather than the views: they don't
     * depend on the views being given them. Swappable for the tests.
     */
    var metricsInsets: () -> Insets? = {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = context.getSystemService(WindowManager::class.java)
                val types = WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement() or
                    WindowInsets.Type.mandatorySystemGestures()
                wm?.currentWindowMetrics?.windowInsets?.getInsetsIgnoringVisibility(types)?.let { Insets.toCompatInsets(it) }
            } catch (e: RuntimeException) {
                null
            }
        } else {
            null
        }
    }

    /** The last insets the views were given, so a refresh can weigh them again. */
    private var viewInsets: Insets = Insets.NONE

    init {
        setBackgroundColor(palette.background)
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        // Every dispatch, the first and every change after it.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            viewInsets = insets.getInsets(BAND)
            refreshBand()
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * The keyboard's window puts the input view inside a layout of its own whose top view
     * fits the system windows: it took the insets and padded itself, so the keyboard got none,
     * and the bottom row could sit on the gesture bar (0.3.1). The band is the keyboard's, filled
     * in its colour: those views stop fitting, and the insets come here instead. Called when the
     * frame joins the window, when a field starts and when the keyboard shows.
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
        refreshBand()
        if (changed) ViewCompat.requestApplyInsets(this)
        // Once more after the next layout, when the window has settled.
        post { refreshBand() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        claimInsets()
    }

    /**
     * The band, from everything that knows it: the insets the views were given, the window's
     * own (root) insets and the window manager's. On the phone the views' could be missing
     * (0.4.0: no band until the keyboard was switched away and back); the largest wins.
     */
    fun refreshBand() {
        val root = ViewCompat.getRootWindowInsets(this)?.getInsets(BAND) ?: Insets.NONE
        val metrics = metricsInsets() ?: Insets.NONE
        val band = Insets.max(Insets.max(viewInsets, root), metrics)
        if (band.left != paddingLeft || band.right != paddingRight || band.bottom != paddingBottom || paddingTop != 0) {
            val from = when (band.bottom) {
                viewInsets.bottom -> "view"
                root.bottom -> "root"
                else -> "metrics"
            }
            Log.i("xrnkey", "band ${paddingBottom} -> ${band.bottom} px (view ${viewInsets.bottom}, root ${root.bottom}, metrics ${metrics.bottom}: $from)")
            setPadding(band.left, 0, band.right, band.bottom)
            requestLayout()
        }
    }

    companion object {
        /** The bar (the hide-keyboard arrow, the pill) and the gesture area: the larger of them. */
        private val BAND = WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.mandatorySystemGestures() or
            WindowInsetsCompat.Type.tappableElement()

        /** The one-handed keyboard's share of the width, as Gboard's (about 80 %). */
        const val ONE_HAND = 0.82f

        /** The keys' width in [inner] px: all of it, or [ONE_HAND] of it one-handed. */
        fun keysWidth(inner: Int, hand: Hand?): Int = if (hand == null) inner else Math.round(inner * ONE_HAND)
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
        val all = maxOf(0, width - paddingLeft - paddingRight)
        val keysWidth = keysWidth(all, hand)
        val inner = MeasureSpec.makeMeasureSpec(keysWidth, MeasureSpec.EXACTLY)
        // Measured even while a panel hides them: they are what the panel replaces.
        keys.measure(inner, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = keys.measuredHeight
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === keys || child.visibility == GONE) continue
            val w = if (child === bar) all - keysWidth else keysWidth
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height + paddingTop + paddingBottom)
    }

    /** The keys and the panels against [hand]'s edge (or across), the bar in the free column. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val all = maxOf(0, right - left - paddingLeft - paddingRight)
        val keysLeft = paddingLeft + if (hand == Hand.RIGHT) all - keysWidth(all, hand) else 0
        val barLeft = if (hand == Hand.RIGHT) paddingLeft else paddingLeft + keysWidth(all, hand)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val x = if (child === bar) barLeft else keysLeft
            child.layout(x, paddingTop, x + child.measuredWidth, paddingTop + child.measuredHeight)
        }
    }
}
