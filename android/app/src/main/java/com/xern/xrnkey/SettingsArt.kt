package com.xern.xrnkey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.xern.xrnkey.keyboard.Palette

/** The settings' row icons, drawn like the keyboard's own tool icons. */
enum class Icon { KEYBOARD, TYPING, SUGGEST, WORDS, SHORTCUTS, CLIPBOARD, THEME, ABOUT, LICENCES }

/** A row's icon: the glyph in the accent, on a disc of the accent, faint. */
@Composable
fun RowIcon(icon: Icon, accent: Color) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) { drawIcon(icon, accent) }
    }
}

private fun DrawScope.drawIcon(icon: Icon, c: Color) {
    val w = size.width
    val sw = 1.8.dp.toPx()
    val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        drawLine(c, Offset(w * x0, w * y0), Offset(w * x1, w * y1), sw, StrokeCap.Round)
    fun box(l: Float, t: Float, r: Float, b: Float, radius: Float) = drawRoundRect(
        c, Offset(w * l, w * t), Size(w * (r - l), w * (b - t)), CornerRadius(w * radius), style = stroke,
    )
    when (icon) {
        Icon.KEYBOARD -> {
            box(0.05f, 0.22f, 0.95f, 0.78f, 0.12f)
            for (x in listOf(0.25f, 0.42f, 0.58f, 0.75f)) drawCircle(c, sw * 0.7f, Offset(w * x, w * 0.4f))
            line(0.3f, 0.6f, 0.7f, 0.6f)
        }
        Icon.TYPING -> {
            // An "a" with a breve over it, as the tile's keycap had.
            drawCircle(c, w * 0.22f, Offset(w * 0.45f, w * 0.62f), style = stroke)
            line(0.67f, 0.42f, 0.67f, 0.86f)
            drawArc(c, 0f, 180f, false, Offset(w * 0.3f, w * 0.06f), Size(w * 0.32f, w * 0.22f), style = stroke)
        }
        Icon.SUGGEST -> {
            // Three slots of the strip, the middle one bold.
            line(0.08f, 0.5f, 0.26f, 0.5f)
            drawLine(c, Offset(w * 0.38f, w * 0.5f), Offset(w * 0.62f, w * 0.5f), sw * 1.8f, StrokeCap.Round)
            line(0.74f, 0.5f, 0.92f, 0.5f)
            line(0.5f, 0.15f, 0.5f, 0.28f)
            line(0.5f, 0.72f, 0.5f, 0.85f)
        }
        Icon.WORDS -> {
            // An open book.
            val p = Path().apply {
                moveTo(w * 0.5f, w * 0.25f)
                lineTo(w * 0.5f, w * 0.85f)
                moveTo(w * 0.5f, w * 0.25f)
                quadraticTo(w * 0.3f, w * 0.12f, w * 0.06f, w * 0.2f)
                lineTo(w * 0.06f, w * 0.78f)
                quadraticTo(w * 0.3f, w * 0.7f, w * 0.5f, w * 0.85f)
                quadraticTo(w * 0.7f, w * 0.7f, w * 0.94f, w * 0.78f)
                lineTo(w * 0.94f, w * 0.2f)
                quadraticTo(w * 0.7f, w * 0.12f, w * 0.5f, w * 0.25f)
            }
            drawPath(p, c, style = stroke)
        }
        Icon.SHORTCUTS -> {
            // A short word that becomes a long one: two bars and an arrow.
            line(0.08f, 0.3f, 0.32f, 0.3f)
            line(0.08f, 0.7f, 0.92f, 0.7f)
            line(0.45f, 0.3f, 0.85f, 0.3f)
            line(0.85f, 0.3f, 0.72f, 0.18f)
            line(0.85f, 0.3f, 0.72f, 0.42f)
        }
        Icon.CLIPBOARD -> {
            box(0.18f, 0.14f, 0.82f, 0.92f, 0.1f)
            box(0.36f, 0.06f, 0.64f, 0.22f, 0.05f)
            line(0.32f, 0.5f, 0.68f, 0.5f)
            line(0.32f, 0.68f, 0.58f, 0.68f)
        }
        Icon.THEME -> {
            drawCircle(c, w * 0.4f, Offset(w / 2, w / 2), style = stroke)
            drawArc(c, 90f, 180f, true, Offset(w * 0.1f, w * 0.1f), Size(w * 0.8f, w * 0.8f))
        }
        Icon.ABOUT -> {
            drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = stroke)
            drawCircle(c, sw * 0.8f, Offset(w / 2, w * 0.3f))
            line(0.5f, 0.45f, 0.5f, 0.72f)
        }
        Icon.LICENCES -> {
            box(0.16f, 0.08f, 0.84f, 0.92f, 0.08f)
            line(0.3f, 0.3f, 0.7f, 0.3f)
            line(0.3f, 0.5f, 0.7f, 0.5f)
            line(0.3f, 0.7f, 0.55f, 0.7f)
        }
    }
}

/**
 * A small keyboard in a theme's colours: the strip, the number row, three rows of letters and
 * the bottom row with the space bar and Enter. `heightPercent` stretches the rows as the
 * Height setting does; `split` draws the dark one on the left and the light one on the right.
 */
@Composable
fun MiniKeyboard(palette: Palette, modifier: Modifier = Modifier, heightPercent: Int = 100, split: Palette? = null) {
    Canvas(modifier) {
        if (split == null) {
            drawMini(palette, heightPercent)
        } else {
            clipRect(right = size.width / 2) { drawMini(palette, heightPercent) }
            clipRect(left = size.width / 2) { drawMini(split, heightPercent) }
        }
    }
}

/** The mini keyboard's height for a width, at a Height setting: as the real one is. */
fun miniKeyboardRatio(heightPercent: Int): Float = (48f + 5 * 60f * heightPercent / 100f) / 400f

private fun DrawScope.drawMini(p: Palette, heightPercent: Int) {
    val w = size.width
    val h = size.height
    val unit = w / 400f  // the real keyboard is about 400 dp wide
    val strip = 48 * unit
    val row = (h - strip) / 5
    drawRect(Color(p.background))
    drawRect(Color(p.strip), size = Size(w, strip))
    val glyph = Color(p.glyph).copy(alpha = 0.55f)
    val key = w / 10
    // The number row and the letters: the letters themselves where there is room, a mark for
    // each key where there isn't.
    val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
    val letters = key >= 24.dp.toPx()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = minOf(row * 0.42f, key * 0.6f)
        color = p.glyph
    }
    for ((r, keys) in rows.withIndex()) {
        val inset = (10 - keys.length) * key / 2
        val cy = strip + row * r + row / 2
        for ((i, c) in keys.withIndex()) {
            val cx = inset + key * i + key / 2
            if (letters) {
                drawContext.canvas.nativeCanvas.drawText(c.toString(), cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
            } else {
                drawRoundRect(glyph, Offset(cx - key * 0.16f, cy - row * 0.14f), Size(key * 0.32f, row * 0.28f), CornerRadius(key * 0.08f))
            }
        }
    }
    // The bottom row: the space bar pill and the Enter disc.
    val cy = strip + row * 4 + row / 2
    val bar = minOf(row * 0.3f, 17 * unit)
    drawRoundRect(Color(p.space), Offset(key * 2.5f + 4 * unit, cy - bar), Size(key * 5 - 8 * unit, bar * 2), CornerRadius(bar))
    drawCircle(Color(p.accent), minOf(row, key * 1.5f) * 0.36f, Offset(w - key * 0.75f, cy))
}
