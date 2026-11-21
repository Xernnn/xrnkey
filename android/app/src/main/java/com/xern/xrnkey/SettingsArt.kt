package com.xern.xrnkey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xern.xrnkey.keyboard.EnterAction
import com.xern.xrnkey.keyboard.KeyboardView
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
 * The real keyboard, as a picture: the same view the keyboard shows, drawn off screen at the
 * phone's width with the settings that change how it looks (the theme, the height, the number
 * row), then scaled to fit. With [split], the left half is in [palette] and the right half in
 * [split] (the System theme).
 */
@Composable
fun KeyboardPicture(
    palette: Palette,
    modifier: Modifier = Modifier,
    heightPercent: Int = 100,
    numberRow: Boolean = true,
    split: Palette? = null,
) {
    val context = LocalContext.current
    fun draw(p: Palette): ImageBitmap {
        val v = KeyboardView(context)
        v.palette = p
        v.heightPercent = heightPercent
        v.numberRow = numberRow
        v.enterAction = EnterAction.NEWLINE
        val width = context.resources.displayMetrics.widthPixels
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        return bmp.asImageBitmap()
    }
    val first = remember(palette, heightPercent, numberRow) { draw(palette) }
    val second = remember(split, heightPercent, numberRow) { split?.let { draw(it) } }
    Canvas(modifier.aspectRatio(first.width.toFloat() / first.height)) {
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        if (second == null) {
            drawImage(first, dstSize = dst)
        } else {
            clipRect(right = size.width / 2) { drawImage(first, dstSize = dst) }
            clipRect(left = size.width / 2) { drawImage(second, dstSize = dst) }
        }
    }
}
