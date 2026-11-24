package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Where the one-handed keyboard sits: against the screen's left edge or its right. */
enum class Hand { LEFT, RIGHT }

/**
 * The free column beside the one-handed keyboard, as Gboard's: full width (|←→|) above, and an
 * arrow to the free side below, which moves the keyboard there. Icons only. The upper half of
 * the column is full width's, the lower half the arrow's, so either is easy to hit.
 */
@SuppressLint("ViewConstructor")
class OneHandBar(context: Context) : View(context) {
    /** The side the keyboard is on (the column is on the other). */
    var hand: Hand = Hand.RIGHT
        set(value) { field = value; invalidate() }

    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    /** Full width again. */
    var onFullWidth: () -> Unit = {}

    /** The keyboard to the other side. */
    var onSwitchSide: () -> Unit = {}

    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * dp
    }

    /** Where the two icons are, down the column: full width, then the arrow. */
    fun fullWidthY() = height * 0.38f
    fun switchY() = height * 0.62f

    override fun onDraw(canvas: Canvas) {
        line.color = palette.dim
        val cx = width / 2f
        val s = 9 * dp
        // Full width: a line with an arrowhead at each end, against a bar at each end.
        var y = fullWidthY()
        canvas.drawLine(cx - s, y, cx + s, y, line)
        for (d in listOf(-1f, 1f)) {
            canvas.drawLine(cx + d * s, y, cx + d * (s - 5 * dp), y - 5 * dp, line)
            canvas.drawLine(cx + d * s, y, cx + d * (s - 5 * dp), y + 5 * dp, line)
            canvas.drawLine(cx + d * (s + 3 * dp), y - 7 * dp, cx + d * (s + 3 * dp), y + 7 * dp, line)
        }
        // The arrow to the free side: where the keyboard goes.
        y = switchY()
        val d = if (hand == Hand.RIGHT) -1f else 1f
        canvas.drawLine(cx - d * 4 * dp, y - 9 * dp, cx + d * 5 * dp, y, line)
        canvas.drawLine(cx + d * 5 * dp, y, cx - d * 4 * dp, y + 9 * dp, line)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) < slop * 2 && Math.abs(e.y - downY) < slop * 2) tap(e.y)
        }
        return true
    }

    /** A tap at [y] down the column (public for the tests). */
    fun tap(y: Float) {
        if (y < height / 2f) onFullWidth() else onSwitchSide()
    }
}
