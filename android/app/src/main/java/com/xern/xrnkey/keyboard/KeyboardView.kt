package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.PopupWindow
import com.xern.xrnkey.R

/**
 * The keyboard: the strip (or the tool row) and the keys, drawn on one Canvas, with no view per
 * key, so a key costs one hit test and one redraw. Labels are borderless glyphs in MiSans.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String) {}
        fun onBackspace() {}
        fun onEnter() {}
        fun onCandidate(slot: Int) {}
        fun onTool(tool: Tool) {}
        /** Holding the comma, then ⌨: Android's keyboard menu. */
        fun onKeyboardMenu() {}
        /** Holding the comma, then a language ("vi", "en", ...). */
        fun onLanguage(code: String) {}
        fun onStripMenu() {}
        /** The space bar became a cursor pad: the word in progress ends. */
        fun onCursorStart() {}
        /** Move the cursor by characters (dx) and lines (dy). */
        fun onCursor(dx: Int, dy: Int) {}
        fun onCursorEnd() {}
        fun onSearchClose() {}
        fun onSearchPick(emoji: String) {}
        /** A swipe left from Backspace: the word before the cursor goes, one per swipe. */
        fun onDeleteWord() {}
        /** An emoticon from the number row's second page. */
        fun onEmoticon(text: String) {}
        /** A candidate taken from the more-suggestions panel. */
        fun onMorePick(text: String) {}
        /** A strip slot's remove mark, while the panel is open: the word or phrase is offered no more. */
        fun onRemove(text: String) {}
    }

    var listener: Listener? = null

    var palette: Palette = Palette.DARK
        set(value) { field = value; panel?.palette = value; invalidate() }
    var page: Page = Page.LETTERS
        set(value) { if (field != value) { field = value; relayout() } }
    /** Where the symbols' way back leads: the letters, or the field's pad. */
    var home: Page = Page.LETTERS
        set(value) { if (field != value) { field = value; relayout() } }
    /** What a number pad adds to its digits (a separator, a minus). */
    var pad: Pad = Pad()
        set(value) { if (field != value) { field = value; relayout() } }
    var shift: Shift = Shift.OFF
        set(value) { if (field != value) { field = value; relayout() } }
    var numberRow: Boolean = true
        set(value) { if (field != value) { field = value; requestLayout(); relayout() } }
    var strip: Strip = Strip.Tools
        set(value) {
            field = value
            // What the panel showed is gone; a slide still under way takes nothing.
            if (stripPointer >= 0) stripLeft = true
            closeMore()
            invalidate()
        }
    var enterAction: EnterAction = EnterAction.NEWLINE
        set(value) { field = value; invalidate() }
    var keyPreview = true
    var holdMillis = 400L
    /** The keys' height, in percent of the usual. */
    var heightPercent = 100
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** Holding a top-row letter gives its digit, where there is no number row. */
    var holdDigits = true
        set(value) { if (field != value) { field = value; relayout() } }

    /** The number row shows the emoticons: swiped left to them, right back to the digits. */
    var emoticonRow = false
        private set(value) { if (field != value) { field = value; relayout() } }
    /** Sliding along the space bar moves the cursor. */
    var spaceSwipe = true
    /**
     * Holding the comma opens a menu to slide to: ⌨ (Android's keyboard menu), then each of
     * [languages], as on Laban Key's globe.
     */
    var commaHold = true

    /** The keyboard's language ("vi", "en", ...): the space bar says which. */
    var language = "vi"
        set(value) { if (field != value) { field = value; relayout() } }

    /** The languages the comma's menu offers, in its order. */
    var languages = listOf("vi", "en")

    /** The Japanese keyboard types here: the letters' comma and full stop are 、 and 。. */
    var kana = false
        set(value) { if (field != value) { field = value; relayout() } }

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val stripHeight = 48 * dp
    /** As tall as Laban Key's rows on the same phone (152 px at 2.5 px a dp). */
    private val rowHeight get() = 60 * dp * heightPercent / 100f
    private val font: Typeface = try {
        resources.getFont(R.font.misans_vf)
    } catch (e: Exception) {
        Typeface.DEFAULT
    }
    private val fontStrong: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(font, 630, false) else font

    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private class Hit(val key: Key, val rect: RectF)
    private var hits: List<Hit> = emptyList()

    /** The letter page's row count sets the height; the other pages share it out. */
    private fun keyRows(): Int = Layouts.rows(Page.LETTERS, numberRow = numberRow).size

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (stripHeight + keyRows() * rowHeight).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    private fun relayout() {
        val rows = Layouts.rows(page, shift, numberRow, holdDigits, pad, home, emoticons = emoticonRow, kana = kana)
        val w = width.toFloat()
        // A pad has no strip: its rows share the whole height.
        val first = if (page.isPad) 0f else stripHeight
        val rowH = (height - first) / rows.size
        val out = ArrayList<Hit>()
        rows.forEachIndexed { r, row ->
            val unit = w / 10f
            var x = row.inset * unit
            val top = first + r * rowH
            for (k in row.keys) {
                out += Hit(k, RectF(x, top, x + k.width * unit, top + rowH))
                x += k.width * unit
            }
        }
        hits = out
        // A swipe from Backspace deletes a word. Backspace touches the right edge, where the
        // system's Back gesture would take a swipe that starts on its outer part and close the
        // keyboard instead. HyperOS ignores this (its own edge window takes the swipe), so
        // there only a swipe from the key's left part, or one that rests first, deletes.
        if (Build.VERSION.SDK_INT >= 29) {
            systemGestureExclusionRects = out.filter { it.key.kind == KeyKind.BACKSPACE }.map { h ->
                Rect().also { h.rect.roundOut(it) }
            }
        }
        out.firstOrNull { it.key.kind == KeyKind.SPACE }?.let { spaceLabel = spaceLabelFor(spaceBar(it.rect).width()) }
        invalidate()
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        if (!page.isPad) {
            fill.color = palette.strip
            canvas.drawRect(0f, 0f, width.toFloat(), stripHeight, fill)
            when (val s = strip) {
                Strip.Tools -> drawTools(canvas)
                is Strip.Candidates -> drawCandidates(canvas, s)
                is Strip.Search -> drawSearch(canvas, s)
            }
        }
        for (h in hits) drawKey(canvas, h, h === pressed && !holdFired)
        // A pad's keys are big enough to need no popup (and a PIN's shouldn't show one).
        pressed?.let { if (keyPreview && !page.isPad && it.key.kind == KeyKind.CHAR && !holdFired) drawPreview(canvas, it) }
        if (cursor != Cursor.OFF) drawCursorPad(canvas)
        popup?.let { drawPopup(canvas, it) }
    }

    /** The cursor pad: the keys fade, the space bar shows the arrows. */
    private fun drawCursorPad(canvas: Canvas) {
        fill.color = palette.background
        fill.alpha = 225
        canvas.drawRect(0f, stripHeight, width.toFloat(), height.toFloat(), fill)
        fill.alpha = 255
        val space = hits.firstOrNull { it.key.kind == KeyKind.SPACE } ?: return
        val cx = space.rect.centerX()
        val cy = space.rect.centerY()
        val s = 7 * dp
        stroke(palette.glyph, 2.2f * dp)
        for (dir in listOf(-1f, 1f)) {
            val x = cx + dir * 22 * dp
            canvas.drawLine(x, cy - s, x + dir * s, cy, line)
            canvas.drawLine(x + dir * s, cy, x, cy + s, line)
        }
        if (cursor == Cursor.PAD) {
            canvas.drawLine(cx - s, cy - 14 * dp, cx, cy - 14 * dp - s, line)
            canvas.drawLine(cx, cy - 14 * dp - s, cx + s, cy - 14 * dp, line)
            canvas.drawLine(cx - s, cy + 14 * dp, cx, cy + 14 * dp + s, line)
            canvas.drawLine(cx, cy + 14 * dp + s, cx + s, cy + 14 * dp, line)
        }
    }

    /**
     * A held key's popup: its forms in their cells ([PopupGrid]), and how far the first form's
     * centre was from the finger when it opened. The finger chooses relative to that, so it starts
     * on the first form even where the popup had to move to stay inside the keyboard.
     */
    private class Popup(
        val forms: List<String>, val cells: List<RectF>, val dx: Float, val dy: Float,
        /** The comma's menu: its forms are choices, not text. */
        val menu: Boolean = false,
    ) {
        val bounds: List<FloatArray> = cells.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }
    }

    private var popup: Popup? = null
    private var popupChoice = 0

    /** What the held key's popup offers, and the one the finger is on (for the tests). */
    val popupForms: List<String> get() = popup?.forms.orEmpty()
    val popupChosen: String? get() = popup?.forms?.getOrNull(popupChoice)

    /**
     * The popup over a held key: its bottom row on the key's row, the first form under the
     * finger, the cells a key wide and a little shorter than a row, so a slide to any form is short.
     */
    private fun openPopup(h: Hit, forms: List<String> = h.key.variants, menu: Boolean = false) {
        val n = forms.size
        val columns = PopupGrid.columns(n)
        val rows = PopupGrid.rows(n)
        val cw = width / 10f
        val ch = minOf(h.rect.height(), 44 * dp)
        val home = PopupGrid.home(n, h.rect.left, h.rect.right, cw, width.toFloat())
        val left = (h.rect.left - home * cw).coerceIn(0f, maxOf(0f, width - columns * cw))
        val top = (h.rect.centerY() - ch / 2 - (rows - 1) * ch).coerceIn(0f, maxOf(0f, height - rows * ch))
        val cells = if (menu) {
            // The comma's menu: one row from over the key, to the right, in its order.
            val start = h.rect.centerX() - cw / 2
            val first = start.coerceIn(0f, maxOf(0f, width - n * cw))
            val y = (h.rect.centerY() - ch / 2).coerceIn(0f, maxOf(0f, height - ch))
            List(n) { i -> RectF(first + i * cw, y, first + (i + 1) * cw, y + ch) }
        } else PopupGrid.cells(n, home).map { (c, r) ->
            val x = left + c * cw
            val y = top + (rows - 1 - r) * ch
            RectF(x, y, x + cw, y + ch)
        }
        popup = Popup(forms, cells, cells[0].centerX() - fingerX, cells[0].centerY() - fingerY, menu)
        popupChoice = 0
    }

    private fun drawPopup(canvas: Canvas, p: Popup) {
        val box = RectF(p.cells.minOf { it.left }, p.cells.minOf { it.top }, p.cells.maxOf { it.right }, p.cells.maxOf { it.bottom })
        fill.color = palette.preview
        canvas.drawRoundRect(box, 10 * dp, 10 * dp, fill)
        p.forms.forEachIndexed { i, v ->
            val r = RectF(p.cells[i]).apply { inset(3 * dp, 3 * dp) }
            if (i == popupChoice) {
                fill.color = palette.accent
                canvas.drawRoundRect(r, 8 * dp, 8 * dp, fill)
            }
            val color = if (i == popupChoice) palette.onAccent else palette.glyph
            when {
                p.menu && v == KEYBOARD_MENU -> drawKeyboardIcon(canvas, r.centerX(), r.centerY(), 9 * dp, color)
                p.menu -> {
                    drawLabel(canvas, v, r.centerX(), r.centerY(), 17 * sp, color)
                    // The language in use: a short bar under it.
                    if (v == LANGUAGE_LABELS[language]) {
                        stroke(color, 2 * dp)
                        canvas.drawLine(r.centerX() - 7 * dp, r.bottom - 7 * dp, r.centerX() + 7 * dp, r.bottom - 7 * dp, line)
                    }
                }
                else -> drawLabel(canvas, v, r.centerX(), r.centerY(), 24 * sp, color)
            }
        }
    }

    private fun drawKeyboardIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color, 1.8f * dp)
        canvas.drawRoundRect(RectF(cx - s * 1.25f, cy - s * 0.8f, cx + s * 1.25f, cy + s * 0.8f), s * 0.25f, s * 0.25f, line)
        fill.color = color
        for (row in 0..1) for (k in -2..2) canvas.drawCircle(cx + k * s * 0.42f, cy - s * 0.38f + row * s * 0.4f, 1.2f * dp, fill)
        canvas.drawLine(cx - s * 0.5f, cy + s * 0.45f, cx + s * 0.5f, cy + s * 0.45f, line)
    }

    /** A choice of the comma's menu: Android's keyboard menu, or a language. */
    private fun menuChoice(form: String) {
        if (form == KEYBOARD_MENU) {
            listener?.onKeyboardMenu()
            return
        }
        val code = LANGUAGE_LABELS.entries.firstOrNull { it.value == form }?.key ?: return
        if (code != language) listener?.onLanguage(code)
    }

    /** Letters as large as Laban Key's (their "o" is 41 px tall at 2.5 px a dp, ours 30 sp). */
    private fun textSize(k: Key): Float = when {
        page.isPad && k.kind == KeyKind.CHAR -> if (k.label.length > 1) 24 * sp else 36 * sp
        page.isPad -> 20 * sp
        else -> keyTextSize(k)
    }

    private fun keyTextSize(k: Key): Float = when (k.kind) {
        KeyKind.CHAR -> if (k.label.length > 1) 19 * sp else 30 * sp
        KeyKind.COMMA, KeyKind.PERIOD -> 32 * sp
        else -> 18 * sp
    }

    /** What the space bar says: the language, or its code where the bar is too narrow. */
    var spaceLabel: String = ""
        private set

    private fun spaceLabelFor(barWidth: Float): String {
        val full = when (language) {
            "en" -> "English"
            "ja" -> "日本語"
            else -> resources.getString(R.string.subtype)
        }
        glyph.textSize = 14 * sp
        glyph.typeface = font
        return if (glyph.measureText(full) <= barWidth - 24 * dp) full else LANGUAGE_LABELS[language] ?: language.uppercase()
    }

    private fun spaceBar(r: RectF): RectF {
        val half = minOf(17 * dp, r.height() * 0.3f)
        return RectF(r.left + 4 * dp, r.centerY() - half, r.right - 4 * dp, r.centerY() + half)
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int, tf: Typeface = font) {
        glyph.typeface = tf
        glyph.textSize = size
        glyph.color = color
        val m = glyph.fontMetrics
        canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2, glyph)
    }

    private fun drawKey(canvas: Canvas, h: Hit, down: Boolean) {
        val r = h.rect
        val cx = r.centerX()
        val cy = r.centerY()
        val k = h.key
        if (page.isPad && k.kind != KeyKind.ENTER && k.kind != KeyKind.BLANK) {
            // A pad's keys are tiles, as a dialer's are: big targets for a thumb.
            fill.color = if (down) palette.preview else palette.space
            canvas.drawRoundRect(r.insetBy(4 * dp, 4 * dp), 12 * dp, 12 * dp, fill)
        } else if (down && k.kind != KeyKind.ENTER && k.kind != KeyKind.SPACE && k.kind != KeyKind.BLANK) {
            fill.color = palette.pressed
            canvas.drawRoundRect(r.insetBy(3 * dp, 4 * dp), 8 * dp, 8 * dp, fill)
        }
        when (k.kind) {
            KeyKind.BLANK -> Unit
            KeyKind.CHAR -> drawLabel(canvas, k.label, cx, cy, textSize(k), palette.glyph)
            KeyKind.SYMBOLS, KeyKind.MORE_SYMBOLS, KeyKind.LETTERS ->
                drawLabel(canvas, k.label, cx, cy, textSize(k), palette.glyph)
            KeyKind.COMMA -> {
                drawMark(canvas, k.label, cx, cy + 2 * dp, textSize(k))
                drawGlobe(canvas, cx, r.top + 13 * dp, 6.5f * dp, palette.dim)
            }
            KeyKind.PERIOD -> drawMark(canvas, k.label, cx, cy, textSize(k))
            KeyKind.EMOTICON -> drawLabel(canvas, k.label, cx, cy, 17 * sp, palette.glyph)
            KeyKind.NUMPAD -> {
                // The number pad's key: its digits in a small square.
                drawLabel(canvas, "12", cx, cy - 7 * dp, 12 * sp, palette.glyph)
                drawLabel(canvas, "34", cx, cy + 7 * dp, 12 * sp, palette.glyph)
            }
            KeyKind.SPACE -> if (page.isPad) {
                // On a pad's tile: the space mark.
                stroke(palette.glyph, 2f * dp)
                val s = 10 * dp
                canvas.drawLine(cx - s, cy - s * 0.2f, cx - s, cy + s * 0.3f, line)
                canvas.drawLine(cx - s, cy + s * 0.3f, cx + s, cy + s * 0.3f, line)
                canvas.drawLine(cx + s, cy + s * 0.3f, cx + s, cy - s * 0.2f, line)
            } else {
                // A pill that reads as the space bar, with the language in a quiet colour.
                fill.color = if (down) palette.preview else palette.space
                val bar = spaceBar(r)
                canvas.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, fill)
                drawLabel(canvas, spaceLabel, cx, cy, 14 * sp, palette.spaceLabel)
            }
            KeyKind.SHIFT -> drawShift(canvas, cx, cy, 11 * dp)
            KeyKind.BACKSPACE -> drawBackspace(canvas, cx, cy, 12 * dp)
            KeyKind.ENTER -> {
                // A bright disc in the tile's plum, with the field's action as its icon.
                fill.color = if (down) palette.accentPressed else palette.accent
                canvas.drawCircle(cx, cy, minOf(r.height(), r.width()) * 0.36f, fill)
                drawEnter(canvas, cx, cy, 10 * dp, palette.onAccent)
            }
        }
    }

    /**
     * The comma's or full stop's mark. Japanese 、 and 。 sit low on the left of their square, as
     * they follow a character: moved to the key's middle.
     */
    private fun drawMark(canvas: Canvas, mark: String, cx: Float, cy: Float, size: Float) {
        val wide = mark == "、" || mark == "。"
        val dx = if (wide) 0.25f * size else 0f
        val dy = if (wide) -0.25f * size else 0f
        drawLabel(canvas, mark, cx + dx, cy + dy, size, palette.glyph)
    }

    private fun drawPreview(canvas: Canvas, h: Hit) {
        val r = h.rect
        val w = maxOf(r.width() * 1.1f, 44 * dp)
        val bubble = RectF(r.centerX() - w / 2, r.top - 66 * dp, r.centerX() + w / 2, r.top - 4 * dp)
        if (bubble.left < 0) bubble.offset(-bubble.left, 0f)
        if (bubble.right > width) bubble.offset(width - bubble.right, 0f)
        if (bubble.top < 0) bubble.offset(0f, -bubble.top)
        fill.color = palette.preview
        canvas.drawRoundRect(bubble, 10 * dp, 10 * dp, fill)
        drawLabel(canvas, h.key.label, bubble.centerX(), bubble.centerY(), 38 * sp, palette.glyph)
    }

    private fun stroke(color: Int, width: Float = 1.8f * dp) {
        line.color = color
        line.strokeWidth = width
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Path().apply {
            moveTo(cx, cy - s)
            lineTo(cx + s, cy)
            lineTo(cx + s * 0.45f, cy)
            lineTo(cx + s * 0.45f, cy + s * 0.75f)
            lineTo(cx - s * 0.45f, cy + s * 0.75f)
            lineTo(cx - s * 0.45f, cy)
            lineTo(cx - s, cy)
            close()
        }
        // Off: an outline. Once: filled. Caps lock: filled in the accent, with a bar under it.
        when (shift) {
            Shift.OFF -> {
                stroke(palette.glyph)
                canvas.drawPath(p, line)
            }
            Shift.ONCE -> {
                fill.color = palette.glyph
                canvas.drawPath(p, fill)
            }
            Shift.LOCKED -> {
                fill.color = palette.accent
                canvas.drawPath(p, fill)
                stroke(palette.accent, 2.4f * dp)
                canvas.drawLine(cx - s * 0.5f, cy + s * 1.2f, cx + s * 0.5f, cy + s * 1.2f, line)
            }
        }
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        stroke(palette.glyph)
        val p = Path().apply {
            moveTo(cx - s * 1.15f, cy)
            lineTo(cx - s * 0.5f, cy - s * 0.7f)
            lineTo(cx + s, cy - s * 0.7f)
            lineTo(cx + s, cy + s * 0.7f)
            lineTo(cx - s * 0.5f, cy + s * 0.7f)
            close()
        }
        canvas.drawPath(p, line)
        val x0 = cx + s * 0.25f
        val d = s * 0.3f
        canvas.drawLine(x0 - d, cy - d, x0 + d, cy + d, line)
        canvas.drawLine(x0 - d, cy + d, x0 + d, cy - d, line)
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color, 2.2f * dp)
        when (enterAction) {
            EnterAction.NEWLINE -> {
                canvas.drawLine(cx + s * 0.8f, cy - s * 0.7f, cx + s * 0.8f, cy + s * 0.2f, line)
                canvas.drawLine(cx + s * 0.8f, cy + s * 0.2f, cx - s * 0.8f, cy + s * 0.2f, line)
                canvas.drawLine(cx - s * 0.8f, cy + s * 0.2f, cx - s * 0.35f, cy - s * 0.25f, line)
                canvas.drawLine(cx - s * 0.8f, cy + s * 0.2f, cx - s * 0.35f, cy + s * 0.65f, line)
            }
            EnterAction.SEND -> {
                // A paper plane.
                val plane = Path().apply {
                    moveTo(cx - s * 0.85f, cy - s * 0.75f)
                    lineTo(cx + s * 0.9f, cy)
                    lineTo(cx - s * 0.85f, cy + s * 0.75f)
                    lineTo(cx - s * 0.5f, cy)
                    close()
                }
                fill.color = color
                canvas.drawPath(plane, fill)
            }
            EnterAction.GO -> {
                canvas.drawLine(cx - s * 0.8f, cy, cx + s * 0.8f, cy, line)
                canvas.drawLine(cx + s * 0.8f, cy, cx + s * 0.25f, cy - s * 0.55f, line)
                canvas.drawLine(cx + s * 0.8f, cy, cx + s * 0.25f, cy + s * 0.55f, line)
            }
            EnterAction.NEXT -> {
                canvas.drawLine(cx - s * 0.8f, cy, cx + s * 0.5f, cy, line)
                canvas.drawLine(cx + s * 0.5f, cy, cx - s * 0.05f, cy - s * 0.55f, line)
                canvas.drawLine(cx + s * 0.5f, cy, cx - s * 0.05f, cy + s * 0.55f, line)
                canvas.drawLine(cx + s * 0.85f, cy - s * 0.6f, cx + s * 0.85f, cy + s * 0.6f, line)
            }
            EnterAction.SEARCH -> {
                canvas.drawCircle(cx - s * 0.15f, cy - s * 0.15f, s * 0.55f, line)
                canvas.drawLine(cx + s * 0.25f, cy + s * 0.25f, cx + s * 0.8f, cy + s * 0.8f, line)
            }
            EnterAction.DONE -> {
                canvas.drawLine(cx - s * 0.75f, cy, cx - s * 0.2f, cy + s * 0.55f, line)
                canvas.drawLine(cx - s * 0.2f, cy + s * 0.55f, cx + s * 0.8f, cy - s * 0.55f, line)
            }
        }
    }

    private fun drawGlobe(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        stroke(color, 1.2f * dp)
        canvas.drawCircle(cx, cy, r, line)
        canvas.drawOval(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r, line)
        canvas.drawLine(cx - r, cy, cx + r, cy, line)
    }

    // The strip -------------------------------------------------------------------------------

    private fun toolSlots(): Int = Tool.entries.size

    private fun drawTools(canvas: Canvas) {
        val w = width.toFloat() / toolSlots()
        Tool.entries.forEachIndexed { i, t -> drawTool(canvas, t, w * i + w / 2, stripHeight / 2, 10 * dp) }
    }

    private fun drawTool(canvas: Canvas, t: Tool, cx: Float, cy: Float, s: Float) {
        stroke(palette.dim, 1.8f * dp)
        when (t) {
            // The strip's own ≡, where it sits on the strip: the tools give way to it.
            Tool.HIDE -> for (dy in listOf(-5f, 0f, 5f)) canvas.drawLine(cx - 8 * dp, cy + dy * dp, cx + 8 * dp, cy + dy * dp, line)
            Tool.SETTINGS -> {
                // A cog: eight flat teeth around a ring.
                val cog = Path()
                for (i in 0 until 32) {
                    val a = Math.toRadians(i * 360.0 / 32 - 90)
                    val tooth = (i / 2) % 2 == 0
                    val rr = if (tooth) s else s * 0.74f
                    val px = cx + (Math.cos(a) * rr).toFloat()
                    val py = cy + (Math.sin(a) * rr).toFloat()
                    if (i == 0) cog.moveTo(px, py) else cog.lineTo(px, py)
                }
                cog.close()
                canvas.drawPath(cog, line)
                canvas.drawCircle(cx, cy, s * 0.32f, line)
            }
            Tool.CLIPBOARD -> {
                canvas.drawRoundRect(RectF(cx - s * 0.75f, cy - s * 0.85f, cx + s * 0.75f, cy + s), 3 * dp, 3 * dp, line)
                canvas.drawRoundRect(RectF(cx - s * 0.35f, cy - s * 1.1f, cx + s * 0.35f, cy - s * 0.65f), 2 * dp, 2 * dp, line)
            }
            Tool.CURSOR -> {
                canvas.drawLine(cx, cy - s, cx, cy + s, line)
                canvas.drawLine(cx - s * 0.3f, cy - s, cx + s * 0.3f, cy - s, line)
                canvas.drawLine(cx - s * 0.3f, cy + s, cx + s * 0.3f, cy + s, line)
                canvas.drawLine(cx - s * 0.65f, cy, cx - s * 1.0f, cy - s * 0.35f, line)
                canvas.drawLine(cx - s * 0.65f, cy, cx - s * 1.0f, cy + s * 0.35f, line)
                canvas.drawLine(cx + s * 0.65f, cy, cx + s * 1.0f, cy - s * 0.35f, line)
                canvas.drawLine(cx + s * 0.65f, cy, cx + s * 1.0f, cy + s * 0.35f, line)
            }
            Tool.EMOJI -> drawSmile(canvas, cx, cy, s)
        }
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        stroke(palette.dim, 1.8f * dp)
        canvas.drawCircle(cx, cy, s, line)
        fill.color = palette.dim
        canvas.drawCircle(cx - s * 0.35f, cy - s * 0.25f, 1.6f * dp, fill)
        canvas.drawCircle(cx + s * 0.35f, cy - s * 0.25f, 1.6f * dp, fill)
        canvas.drawArc(cx - s * 0.5f, cy - s * 0.3f, cx + s * 0.5f, cy + s * 0.55f, 20f, 140f, false, line)
    }

    /**
     * Where the strip's slots are, between the menu and the emoji button: three, or the places
     * of a row of emoji ([Strip.Candidates.row]), evenly.
     */
    private fun stripSlots(s: Strip.Candidates? = null): List<RectF> {
        val side = 48 * dp
        val n = s?.row?.size?.takeIf { it > 0 } ?: 3
        val w = (width - 2 * side) / n
        return List(n) { RectF(side + it * w, 0f, side + (it + 1) * w, stripHeight) }
    }

    /** A slot's text as drawn: shrunk to fit (a phrase) before it is cut short, and its width. */
    private fun fitted(text: String, r: RectF): Triple<String, Float, Float> {
        glyph.typeface = font
        glyph.textSize = 18 * sp
        val room = r.width() - 8 * dp
        val wide = glyph.measureText(text)
        val size = if (wide > room) maxOf(13 * sp, 18 * sp * room / wide) else 18 * sp
        glyph.textSize = size
        val shown = ellipsize(text, room)
        return Triple(shown, size, glyph.measureText(shown))
    }

    private fun drawCandidates(canvas: Canvas, s: Strip.Candidates) {
        val side = 48 * dp
        val cy = stripHeight / 2
        stroke(palette.dim, 1.8f * dp)
        for (dy in listOf(-5f, 0f, 5f)) canvas.drawLine(side / 2 - 8 * dp, cy + dy * dp, side / 2 + 8 * dp, cy + dy * dp, line)
        drawSmile(canvas, width - side / 2, cy, 10 * dp)
        if (s.row.isNotEmpty()) {
            // A row of emoji: each in its place, none above the others.
            stripSlots(s).forEachIndexed { i, r ->
                s.row[i]?.let { drawLabel(canvas, it, r.centerX(), cy, 18 * sp, palette.glyph) }
            }
            return
        }
        val slots = stripSlots()
        listOf(s.left, s.centre, s.right).forEachIndexed { i, text ->
            if (text == null) return@forEachIndexed
            val r = slots[i]
            val (shown, size, wide) = fitted(text, r)
            val y = cy - if (i == 1 && s.more) 2 * dp else 0f
            // The candidate the field shows (Japanese, after Space): on the accent, as the panel
            // shows the one under the finger.
            val lit = i == 1 && s.highlight
            if (lit) {
                val half = minOf(r.width() / 2 - 2 * dp, maxOf(wide / 2 + 16 * dp, 30 * dp))
                fill.color = palette.accent
                canvas.drawRoundRect(r.centerX() - half, 7 * dp, r.centerX() + half, stripHeight - 7 * dp, 8 * dp, 8 * dp, fill)
            }
            val colour = if (lit) palette.onAccent else if (i == 1) palette.glyph else palette.dim
            val tf = if (i == 1 && s.bold) fontStrong else font
            val grey = minOf(s.grey.getOrElse(i) { 0 }, shown.length)
            if (grey > 0) drawTwoTone(canvas, shown, grey, r.centerX(), y, size, colour, tf)
            else drawLabel(canvas, shown, r.centerX(), y, size, colour, tf)
        }
        if (s.more) {
            fill.color = if (s.highlight) palette.onAccent else palette.dim
            val r = slots[1]
            for (dx in listOf(-6f, 0f, 6f)) canvas.drawCircle(r.centerX() + dx * dp, stripHeight - 8 * dp, 1.5f * dp, fill)
        }
        // While the panel is open, each word gets a small mark that forgets it.
        if (more != More.CLOSED) for ((_, at) in removeMarks(s)) {
            fill.color = palette.dim
            canvas.drawCircle(at.x, at.y, 6 * dp, fill)
            stroke(palette.strip, 1.4f * dp)
            val d = 2.4f * dp
            canvas.drawLine(at.x - d, at.y - d, at.x + d, at.y + d, line)
            canvas.drawLine(at.x - d, at.y + d, at.x + d, at.y - d, line)
        }
    }

    /** Where each slot's remove mark goes, after its word or phrase and a little above; none on an emoji. */
    private fun removeMarks(s: Strip.Candidates): List<Pair<String, android.graphics.PointF>> {
        val slots = stripSlots()
        return listOf(s.left, s.centre, s.right).mapIndexedNotNull { i, text ->
            if (text == null || !s.removable || text.none { it.isLetter() }) return@mapIndexedNotNull null
            val r = slots[i]
            val (_, _, w) = fitted(text, r)
            val x = minOf(r.centerX() + w / 2 + 8 * dp, r.right - 7 * dp)
            text to android.graphics.PointF(x, stripHeight / 2 - 9 * dp)
        }
    }

    /**
     * A candidate whose first `grey` characters are already in the field ("bạn" of "bạn bè"):
     * those in grey, the rest in the slot's colour, the whole centred on `cx`.
     */
    private fun drawTwoTone(canvas: Canvas, text: String, grey: Int, cx: Float, cy: Float, size: Float, colour: Int, tf: Typeface) {
        glyph.typeface = tf
        glyph.textSize = size
        val head = text.substring(0, grey)
        val tail = text.substring(grey)
        val wHead = glyph.measureText(head)
        val wTail = glyph.measureText(tail)
        val x0 = cx - (wHead + wTail) / 2
        // The side slots are dim already: their typed part is fainter still (dim at 70 %).
        val faint = if (colour == palette.glyph) palette.dim else (palette.dim and 0x00FFFFFF) or (0xB3 shl 24)
        drawLabel(canvas, head, x0 + wHead / 2, cy, size, faint, tf)
        if (tail.isNotEmpty()) drawLabel(canvas, tail, x0 + wHead + wTail / 2, cy, size, colour, tf)
    }

    /** The emoji search: a back arrow, the query with its cursor, then what it finds. */
    private fun drawSearch(canvas: Canvas, s: Strip.Search) {
        val side = 48 * dp
        val cy = stripHeight / 2
        stroke(palette.dim, 2f * dp)
        canvas.drawLine(side / 2 - 8 * dp, cy, side / 2 + 8 * dp, cy, line)
        canvas.drawLine(side / 2 - 8 * dp, cy, side / 2 - 2 * dp, cy - 6 * dp, line)
        canvas.drawLine(side / 2 - 8 * dp, cy, side / 2 - 2 * dp, cy + 6 * dp, line)
        val box = RectF(side, 7 * dp, side + width * 0.32f, stripHeight - 7 * dp)
        fill.color = palette.space
        canvas.drawRoundRect(box, 8 * dp, 8 * dp, fill)
        glyph.textSize = 16 * sp
        glyph.typeface = font
        val q = ellipsize(s.query, box.width() - 24 * dp)
        glyph.textAlign = Paint.Align.LEFT
        glyph.color = palette.glyph
        val m = glyph.fontMetrics
        canvas.drawText(q, box.left + 10 * dp, cy - (m.ascent + m.descent) / 2, glyph)
        glyph.textAlign = Paint.Align.CENTER
        val caret = box.left + 10 * dp + glyph.measureText(q) + 2 * dp
        fill.color = palette.accent
        canvas.drawRect(caret, box.top + 8 * dp, caret + 2 * dp, box.bottom - 8 * dp, fill)
        val w = 44 * dp
        searchResults(s).forEachIndexed { i, e ->
            drawLabel(canvas, e, box.right + 8 * dp + i * w + w / 2, cy, 22 * sp, palette.glyph, Typeface.DEFAULT)
        }
    }

    private fun searchResults(s: Strip.Search): List<String> {
        val room = ((width - (48 * dp + width * 0.32f + 8 * dp)) / (44 * dp)).toInt()
        return s.results.take(maxOf(0, room))
    }

    private fun ellipsize(text: String, max: Float): String {
        if (glyph.measureText(text) <= max) return text
        var t = text
        while (t.length > 1 && glyph.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    // ---- touch --------------------------------------------------------------------------------

    private var pressed: Hit? = null
    private var pressedPointer = -1
    private var holdFired = false
    private var lastShiftTap = 0L

    private enum class Cursor { OFF, SWIPE, PAD }

    private var cursor = Cursor.OFF
    private var anchorX = 0f
    private var anchorY = 0f
    private val cursorStep get() = 10 * dp
    private val lineStep get() = 28 * dp
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Where the pressed key's finger is now (where a popup opens around). */
    private var fingerX = 0f
    private var fingerY = 0f

    /** The finger went down on the number row (a swipe there turns its page). */
    private var onNumberRow = false

    /** How far a finger must slide to swipe: from Backspace, and along the number row. */
    private val deleteSwipe get() = 1.5f * width / 10f
    private val rowSwipe get() = 2f * width / 10f

    private val hold = Runnable {
        val h = pressed ?: return@Runnable
        when {
            h.key.kind == KeyKind.COMMA -> if (commaHold) {
                holdFired = true
                openPopup(h, listOf(KEYBOARD_MENU) + languages.map { LANGUAGE_LABELS[it] ?: it }, menu = true)
            }
            h.key.variants.isNotEmpty() -> { holdFired = true; openPopup(h) }
        }
        invalidate()
    }

    private fun startCursor(mode: Cursor) {
        cursor = mode
        holdFired = true
        listener?.onCursorStart()
        invalidate()
    }

    private fun typeChar(text: String) {
        listener?.onText(text)
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    /** The finger moved while the space bar is held or swiped. */
    private fun moveCursor(x: Float, y: Float) {
        if (cursor == Cursor.OFF) {
            if (!spaceSwipe || Math.abs(x - anchorX) < slop) return
            removeCallbacks(hold)
            startCursor(Cursor.SWIPE)
            anchorX += if (x > anchorX) slop.toFloat() else -slop.toFloat()
        }
        val dx = ((x - anchorX) / cursorStep).toInt()
        val dy = if (cursor == Cursor.PAD) ((y - anchorY) / lineStep).toInt() else 0
        if (dx != 0 || dy != 0) {
            listener?.onCursor(dx, dy)
            anchorX += dx * cursorStep
            anchorY += dy * lineStep
        }
    }

    private val repeat = object : Runnable {
        override fun run() {
            if (pressed?.key?.kind != KeyKind.BACKSPACE) return
            listener?.onBackspace()
            postDelayed(this, 50)
        }
    }

    private fun hitAt(x: Float, y: Float): Hit? = hits.firstOrNull { it.rect.contains(x, y) }

    /** The cursor pad from the tool row: it stays until a tap that doesn't drag. */
    private var padOpen = false
    private var padMoved = false

    fun openCursorPad() {
        if (padOpen) return
        padOpen = true
        startCursor(Cursor.PAD)
    }

    private fun closeCursorPad() {
        padOpen = false
        cursor = Cursor.OFF
        holdFired = false
        listener?.onCursorEnd()
        invalidate()
    }

    /** Touches while the pad is open: drags move the cursor, a tap closes it. */
    private fun padTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anchorX = e.x
                anchorY = e.y
                padMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!padMoved && Math.abs(e.x - anchorX) < slop && Math.abs(e.y - anchorY) < slop) return
                padMoved = true
                moveCursor(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> if (!padMoved) closeCursorPad()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (padOpen) {
            padTouch(e)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger lands before the first lifts: the first key (or slot) counts.
                if (stripPointer >= 0) finishStrip(true)
                if (pressed != null) release(true)
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (!page.isPad && y < stripHeight) {
                    if (strip is Strip.Candidates) {
                        // Taken when it lifts: a slide up opens the panel instead.
                        stripPointer = e.getPointerId(i)
                        stripX = x
                        stripY = y
                        stripLeft = false
                    } else {
                        tapStrip(x)
                    }
                    return true
                }
                // A key closes the panel, and types as it would.
                closeMore()
                press(hitAt(x, y), e.getPointerId(i), x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val si = if (stripPointer >= 0) e.findPointerIndex(stripPointer) else -1
                if (si >= 0) moveStrip(e.getX(si), e.getY(si))
                val i = if (pressedPointer >= 0) e.findPointerIndex(pressedPointer) else -1
                if (i >= 0) movePressed(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == stripPointer) finishStrip(true)
                if (id == pressedPointer) release(true)
            }
            MotionEvent.ACTION_CANCEL -> {
                finishStrip(false)
                release(false)
            }
        }
        return true
    }

    /** The finger on a key moved. */
    private fun movePressed(x: Float, y: Float) {
        val p = pressed
        fingerX = x
        fingerY = y
        val pop = popup
        when {
            p?.key?.kind == KeyKind.SPACE -> moveCursor(x, y)
            pop != null -> {
                val c = PopupGrid.nearest(pop.bounds, x + pop.dx, y + pop.dy)
                if (c != popupChoice) { popupChoice = c; invalidate() }
            }
            // Slid left from Backspace: the word before the cursor, once a swipe.
            p?.key?.kind == KeyKind.BACKSPACE -> if (!holdFired && anchorX - x > deleteSwipe) {
                holdFired = true
                removeCallbacks(repeat)
                listener?.onDeleteWord()
                invalidate()
            }
            // Swiped along the number row: left to the emoticons, right back to the digits.
            onNumberRow && !holdFired && Math.abs(x - anchorX) > rowSwipe && Math.abs(x - anchorX) > Math.abs(y - anchorY) -> {
                removeCallbacks(hold)
                holdFired = true
                emoticonRow = x < anchorX
                invalidate()
            }
            else -> {
                val h = hitAt(x, y)
                val kind = p?.key?.kind
                if (h != null && h !== p && (kind == KeyKind.CHAR || kind == KeyKind.EMOTICON) && !holdFired) {
                    removeCallbacks(hold)
                    pressed = h
                    if (h.key.hold != null) postDelayed(hold, holdMillis)
                    invalidate()
                }
            }
        }
    }

    private fun press(h: Hit?, pointer: Int, x: Float = 0f, y: Float = 0f) {
        pressed = h
        pressedPointer = pointer
        holdFired = false
        anchorX = x
        anchorY = y
        fingerX = x
        fingerY = y
        onNumberRow = h != null && page == Page.LETTERS && numberRow && h.rect.top < stripHeight + 1f
        if (h == null) return
        when (h.key.kind) {
            KeyKind.SPACE -> postDelayed(hold, holdMillis)
            KeyKind.BACKSPACE -> {
                listener?.onBackspace()
                postDelayed(repeat, holdMillis)
            }
            KeyKind.COMMA -> postDelayed(hold, holdMillis)
            else -> if (h.key.hold != null) postDelayed(hold, holdMillis)
        }
        invalidate()
    }

    private fun release(act: Boolean) {
        val h = pressed
        removeCallbacks(hold)
        removeCallbacks(repeat)
        pressed = null
        pressedPointer = -1
        val shown = popup
        popup = null
        when {
            cursor != Cursor.OFF -> {
                cursor = Cursor.OFF
                listener?.onCursorEnd()
            }
            shown != null -> if (act) {
                if (shown.menu) menuChoice(shown.forms[popupChoice]) else typeChar(shown.forms[popupChoice])
            }
            h != null && act && !holdFired -> tap(h.key)
        }
        holdFired = false
        invalidate()
    }

    /** A tap, as the keyboard acts on it (public for the tests and the service). */
    fun tap(k: Key) {
        val l = listener
        when (k.kind) {
            KeyKind.CHAR -> {
                l?.onText(k.label)
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            KeyKind.COMMA, KeyKind.PERIOD -> l?.onText(k.text)
            KeyKind.EMOTICON -> l?.onEmoticon(k.label)
            KeyKind.NUMPAD -> page = Page.NUMPAD
            KeyKind.SPACE -> {
                l?.onText(" ")
                // After a symbol and a space, the letters (or the field's pad) come back on their own.
                if (page == Page.SYMBOLS || page == Page.MORE_SYMBOLS) page = home
            }
            KeyKind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.ONCE && now - lastShiftTap < 300 -> Shift.LOCKED
                    shift == Shift.OFF -> Shift.ONCE
                    else -> Shift.OFF
                }
                lastShiftTap = now
            }
            KeyKind.BACKSPACE -> Unit
            KeyKind.SYMBOLS -> page = Page.SYMBOLS
            KeyKind.MORE_SYMBOLS -> page = Page.MORE_SYMBOLS
            KeyKind.LETTERS -> page = home
            KeyKind.ENTER -> l?.onEnter()
            KeyKind.BLANK -> Unit
        }
    }

    private fun tapStrip(x: Float) {
        when (strip) {
            Strip.Tools -> {
                val i = (x / (width.toFloat() / toolSlots())).toInt().coerceIn(0, toolSlots() - 1)
                listener?.onTool(Tool.entries[i])
            }
            is Strip.Search -> {
                val s = strip as Strip.Search
                val side = 48 * dp
                val start = side + width * 0.32f + 8 * dp
                val i = ((x - start) / (44 * dp)).toInt()
                val shown = searchResults(s)
                when {
                    x < side -> listener?.onSearchClose()
                    x >= start && i in shown.indices -> listener?.onSearchPick(shown[i])
                }
            }
            is Strip.Candidates -> {
                val side = 48 * dp
                when {
                    x < side -> listener?.onStripMenu()
                    x > width - side -> listener?.onTool(Tool.EMOJI)
                    else -> stripSlots(strip as Strip.Candidates).indexOfFirst { x >= it.left && x < it.right }.let {
                        if (it >= 0) listener?.onCandidate(it)
                    }
                }
            }
        }
    }

    // ---- the more-suggestions panel ----------------------------------------------------------

    /**
     * The candidates after the strip's three, in a panel over the strip, as the keyboard the
     * owner came from has them. Slide up from the strip to open it ([More.SLIDING]) and lift on a
     * candidate to take it; slide back down past the strip to close it. Lifting anywhere else
     * leaves it open ([More.OPEN]): then a tap takes a candidate, a word's mark in the strip hides
     * that word, and any key closes it. The dots under the middle only say there are more: a tap
     * there takes the middle word, as anywhere on its slot.
     */
    private enum class More { CLOSED, SLIDING, OPEN }

    private var more = More.CLOSED
    private var panel: MorePanelView? = null
    private var panelWindow: PopupWindow? = null

    /** The finger on the strip of candidates: where it went down, and whether it left the strip. */
    private var stripPointer = -1
    private var stripX = 0f
    private var stripY = 0f
    private var stripLeft = false

    /** How far up from where it went down a finger slides to open the panel. */
    private val slideUp get() = maxOf(slop.toFloat(), 12 * dp)

    /** What the panel offers while it is open, else nothing (for the tests). */
    val moreShown: List<String> get() = if (more == More.CLOSED) emptyList() else panel?.words.orEmpty()

    /** The panel's candidate under the sliding finger, or -1 (for the tests). */
    val moreChosen: Int get() = if (more == More.CLOSED) -1 else panel?.chosen ?: -1

    /** Where a strip word's remove mark is while the panel is open, else null (for the tests). */
    fun removeMark(word: String): android.graphics.PointF? {
        val s = strip as? Strip.Candidates ?: return null
        if (more == More.CLOSED) return null
        return removeMarks(s).firstOrNull { it.first == word }?.second
    }

    private fun extra(): List<String> = (strip as? Strip.Candidates)?.let { it.extra.take(it.most) }.orEmpty()

    private fun panelHeight(n: Int) = MorePanelView.height(n, dp)

    /** The panel's candidate at a point in this view's coordinates (the panel is above it), or -1. */
    private fun panelAt(x: Float, y: Float): Int {
        val n = panel?.words?.size ?: return -1
        val h = panelHeight(n)
        return MorePanelView.cells(n, width.toFloat(), h.toFloat(), 48 * dp, dp).indexOfFirst { it.contains(x, y + h) }
    }

    private fun openMore(mode: More) {
        val words = extra()
        if (words.isEmpty()) return
        val p = panel ?: MorePanelView(context).also { v ->
            v.onPick = { pickMore(it) }
            panel = v
        }
        p.palette = palette
        p.words = words
        p.chosen = -1
        more = mode
        showPanel(p, panelHeight(words.size))
        invalidate()
    }

    /**
     * The panel goes in a window of its own over the app, right above the keyboard (the keyboard's
     * own window ends at the strip). A finger that slid onto it from the strip is still this
     * view's, so the slide is followed here; once it is open, taps on it are the panel's.
     */
    private fun showPanel(p: MorePanelView, h: Int) {
        if (windowToken == null || !isAttachedToWindow) return
        val w = panelWindow ?: PopupWindow(p, width, h, false).also {
            it.isClippingEnabled = false
            it.isTouchable = true
            it.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            it.setBackgroundDrawable(null)
            panelWindow = it
        }
        val at = IntArray(2)
        getLocationInWindow(at)
        if (w.isShowing) {
            w.update(at[0], at[1] - h, width, h)
        } else {
            w.width = width
            w.height = h
            w.showAtLocation(this, Gravity.NO_GRAVITY, at[0], at[1] - h)
        }
    }

    private fun closeMore() {
        if (more == More.CLOSED) return
        more = More.CLOSED
        panel?.chosen = -1
        panelWindow?.dismiss()
        invalidate()
    }

    private fun pickMore(i: Int) {
        val word = panel?.words?.getOrNull(i)
        closeMore()
        if (word != null) listener?.onMorePick(word)
    }

    private fun moveStrip(x: Float, y: Float) {
        if (more == More.OPEN && y < 0) more = More.SLIDING
        when {
            more == More.SLIDING -> {
                if (y > stripHeight) {
                    // Slid back down past the strip: nothing is taken.
                    closeMore()
                    stripLeft = true
                } else {
                    panel?.chosen = panelAt(x, y)
                }
            }
            more == More.CLOSED && !stripLeft && stripY - y > slideUp && extra().isNotEmpty() -> {
                openMore(More.SLIDING)
                panel?.chosen = panelAt(x, y)
            }
            y > stripHeight || y < 0 -> {
                stripLeft = true
                if (more == More.OPEN) closeMore()
            }
        }
    }

    /** The finger on the strip lifted ([act]) or the touch was cancelled. */
    private fun finishStrip(act: Boolean) {
        if (stripPointer < 0) return
        stripPointer = -1
        if (!act) {
            if (more == More.SLIDING) closeMore()
            return
        }
        when (more) {
            More.SLIDING -> {
                val c = panel?.chosen ?: -1
                if (c >= 0) {
                    pickMore(c)
                } else {
                    more = More.OPEN
                    invalidate()
                }
            }
            More.OPEN -> if (!stripLeft) {
                val s = strip as? Strip.Candidates
                val mark = s?.let { removeMarks(it) }?.firstOrNull { (_, at) ->
                    Math.hypot((stripX - at.x).toDouble(), (stripY - at.y).toDouble()) <= 18 * dp
                }
                closeMore()
                if (mark != null) listener?.onRemove(mark.first) else tapStrip(stripX)
            }
            More.CLOSED -> if (!stripLeft) tapStrip(stripX)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) closeMore()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) closeMore()
    }

    override fun onDetachedFromWindow() {
        closeMore()
        super.onDetachedFromWindow()
    }

    private fun RectF.insetBy(dx: Float, dy: Float) = RectF(left + dx, top + dy, right - dx, bottom - dy)

    companion object {
        /** The comma menu's first choice: Android's keyboard menu (drawn as a keyboard). */
        const val KEYBOARD_MENU = "⌨"

        /** The comma menu's label of each language. */
        val LANGUAGE_LABELS = mapOf("vi" to "VI", "en" to "EN", "ja" to "JA")
    }
}

/**
 * The keyboard's colours. Dark slate is the default. The accent is the xrnkey tile's plum
 * (#983A78), lightened on the dark keyboard so Enter stands out from it (3.3:1, and 4:1 for
 * its icon).
 */
data class Palette(
    val background: Int,
    val strip: Int,
    val glyph: Int,
    val dim: Int,
    val space: Int,
    val spaceLabel: Int,
    val pressed: Int,
    val preview: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
) {
    companion object {
        val DARK = Palette(
            background = 0xFF263238.toInt(),
            strip = 0xFF1E272C.toInt(),
            glyph = 0xFFECEFF1.toInt(),
            dim = 0xFFB0BEC5.toInt(),
            space = 0xFF3E4C53.toInt(),
            spaceLabel = 0xFF95A5A6.toInt(),
            pressed = 0xFF37474F.toInt(),
            preview = 0xFF4F626B.toInt(),
            accent = 0xFFC4579D.toInt(),
            accentPressed = 0xFFA9468A.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
        )
        val LIGHT = Palette(
            background = 0xFFECEFF1.toInt(),
            strip = 0xFFE3E7EA.toInt(),
            glyph = 0xFF1F2428.toInt(),
            dim = 0xFF5F6B73.toInt(),
            space = 0xFFCFD8DC.toInt(),
            spaceLabel = 0xFF607078.toInt(),
            pressed = 0xFFC5CED3.toInt(),
            preview = 0xFFFFFFFF.toInt(),
            accent = 0xFF983A78.toInt(),
            accentPressed = 0xFF7E2F63.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
        )
    }
}
