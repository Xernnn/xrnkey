package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.xern.xrnkey.R

/**
 * The more-suggestions panel, drawn over the strip: the candidates the strip has no room for, up
 * to seven (fifteen in Japanese, for its homophones: [Strip.Candidates.most]), three to a row
 * under the strip's own slots. The best is nearest the strip and in the middle, then left, then
 * right, as the strip orders its three, so a slide straight up from a slot goes through the
 * candidates of its column. The keyboard view follows a slide onto it; a
 * tap on it, once it stays open, is its own.
 */
@SuppressLint("ViewConstructor")
class MorePanelView(context: Context) : View(context) {
    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }

    var words: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    /** The candidate under the finger, drawn in the accent; -1 for none. */
    var chosen = -1
        set(value) { if (field != value) { field = value; invalidate() } }

    /** A tap took the candidate at this index. */
    var onPick: ((Int) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val font: Typeface = try {
        resources.getFont(R.font.misans_vf)
    } catch (e: Exception) {
        Typeface.DEFAULT
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun cells(): List<RectF> = cells(words.size, width.toFloat(), height.toFloat(), 48 * dp, dp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height(words.size, dp))
    }

    override fun onDraw(canvas: Canvas) {
        val m = MARGIN * dp
        fill.color = palette.preview
        canvas.drawRoundRect(m, m, width - m, height - m, 12 * dp, 12 * dp, fill)
        cells().forEachIndexed { i, r ->
            val text = words[i]
            if (i == chosen) {
                fill.color = palette.accent
                canvas.drawRoundRect(r.left + 3 * dp, r.top + 3 * dp, r.right - 3 * dp, r.bottom - 3 * dp, 8 * dp, 8 * dp, fill)
            }
            // A long candidate (a phrase) shrinks to fit before it is cut short, as in the strip.
            glyph.textSize = 18 * sp
            val room = r.width() - 8 * dp
            val wide = glyph.measureText(text)
            glyph.textSize = if (wide > room) maxOf(13 * sp, 18 * sp * room / wide) else 18 * sp
            var shown = text
            if (glyph.measureText(shown) > room) {
                while (shown.length > 1 && glyph.measureText("$shown…") > room) shown = shown.dropLast(1)
                shown = "$shown…"
            }
            glyph.color = if (i == chosen) palette.onAccent else palette.glyph
            val fm = glyph.fontMetrics
            canvas.drawText(shown, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, glyph)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = cells().indexOfFirst { it.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> chosen = i
            MotionEvent.ACTION_UP -> {
                chosen = -1
                if (i >= 0) onPick?.invoke(i)
            }
            MotionEvent.ACTION_CANCEL -> chosen = -1
        }
        return true
    }

    companion object {
        /** At most this many by default, as the keyboard the owner came from shows. */
        const val MOST = 7

        /** A row's height and the panel's margin, in dp. */
        private const val ROW = 44f
        private const val MARGIN = 6f

        /** Three to a row. */
        fun rows(n: Int) = (n + 2) / 3

        /** The panel's height in px for `n` candidates. */
        fun height(n: Int, dp: Float) = (rows(n) * ROW * dp + 2 * MARGIN * dp).toInt()

        /** The n-th candidate's column (0 left, 1 middle, 2 right) and row (0 nearest the strip). */
        fun place(i: Int): Pair<Int, Int> = intArrayOf(1, 0, 2)[i % 3] to i / 3

        /**
         * Each candidate's cell in a panel `width` by `height` px: columns over the strip's three
         * slots (between its side buttons, `side` wide), the outer two out to the panel's edge.
         */
        fun cells(n: Int, width: Float, height: Float, side: Float, dp: Float): List<RectF> {
            val m = MARGIN * dp
            val slot = (width - 2 * side) / 3
            val edges = floatArrayOf(m, side + slot, side + 2 * slot, width - m)
            return List(n) {
                val (column, row) = place(it)
                val bottom = height - m - row * ROW * dp
                RectF(edges[column], bottom - ROW * dp, edges[column + 1], bottom)
            }
        }
    }
}
