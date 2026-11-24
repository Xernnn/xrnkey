package com.xern.xrnkey.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.xern.xrnkey.R
import com.xern.xrnkey.engine.Emoji
import com.xern.xrnkey.engine.SkinTones
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The emoji panel, in place of the strip and the keys (android/docs/feature-spec.md, "The emoji
 * panel"): the tab row (back to the keys, emoji, kaomoji, symbols, fancy letters), right under
 * it the grid, four rows at a time and one category to a page, a round search button over the
 * grid's corner, and the category bar with Backspace; the bar scrolls sideways when its cells
 * don't fit. A sideways swipe turns the page; only a tap types. Drawn on one Canvas, like the
 * keys.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context) : View(context) {

    interface Listener {
        /** A cell of `tab` was tapped: type it, and put it first in that tab's recents. */
        fun onEmoji(text: String, tab: EmojiTab = EmojiTab.EMOJI) {}
        /** A skin tone was chosen for `base`: show it from now on. */
        fun onSkinTone(base: String, toned: String) {}
        fun onClosePanel() {}
        fun onEmojiSearch() {}
        fun onBackspace() {}
    }

    var listener: Listener? = null
    var palette: Palette = Palette.DARK
        set(value) { field = value; invalidate() }
    /**
     * The tab shown. A tab opens where it was left while the panel stays open, and otherwise on
     * its recents, or its first category when there are none.
     */
    var tab: EmojiTab = EmojiTab.EMOJI
        set(value) {
            if (field == value) return
            places[field] = page
            field = value
            holdRecents = false
            stopBar()
            rebuild()
            jumpTo(places[value] ?: EmojiGrid.firstPage(recentsOf(value)))
        }
    /** The index's emoji, less those this phone's font can't draw (newer than its Android). */
    var emoji: List<Emoji> = emptyList()
        set(value) {
            val probe = Paint()
            field = value.filter { probe.hasGlyph(it.text) }
            rebuild()
        }
    /** The emoji tab's recents: see [setRecents]. */
    var recents: List<String>
        get() = recentsOf(EmojiTab.EMOJI)
        set(value) = setRecents(EmojiTab.EMOJI, value)
    var tones: Map<String, String> = emptyMap()
        set(value) { field = value; rebuild() }

    /** A tab's recents, newest first, as last given. */
    fun recentsOf(tab: EmojiTab): List<String> = latest[tab].orEmpty()

    /**
     * A tab's recents, newest first; each tab keeps its own. After a tap on the recents page the
     * page keeps its order, so what is under the finger stays put; it catches up when the panel
     * opens, the page is turned or the tab changes.
     */
    fun setRecents(tab: EmojiTab, list: List<String>) {
        latest[tab] = list
        if (tab == this.tab && !holdRecents) refreshRecents()
    }

    /** The page shown, an index into [pages]. */
    var page = 0
        private set
    /** The tab's pages: on the emoji tab, recents and then the six categories. */
    var pages: List<GridPage> = emptyList()
        private set
    /** How far the shown page is scrolled down, in px. */
    val gridScroll get() = offset
    /** How far the pages are dragged sideways, in px: the shown page sits this far to the right. */
    val pageShift get() = shift

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val headerHeight = 48 * dp
    private val barHeight = 48 * dp
    /** The tab row's five cells: 64 dp each, or a fifth of a narrower panel (one-handed). */
    private val tabWidth get() = minOf(64 * dp, width / 5f)
    private val font: Typeface = try { resources.getFont(R.font.misans_vf) } catch (e: Exception) { Typeface.DEFAULT }

    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f * dp
    }

    /** Each tab's recents as last given, and as its recents page shows them. */
    private val latest = HashMap<EmojiTab, List<String>>()
    private val shown = HashMap<EmojiTab, List<String>>()
    private var holdRecents = false
    /** The page each tab was left on while the panel is open. */
    private val places = HashMap<EmojiTab, Int>()
    /** Each text tab's pages, less what the phone's fonts can't draw. */
    private val drawable = HashMap<EmojiTab, List<TextPage>>()
    private var offset = 0f
    private var shift = 0f
    /** The page a swipe just turned from, and its scroll, while it slides out. */
    private var leaving = -1
    private var leavingScroll = 0f
    private val scroller = OverScroller(context)
    private val settler = OverScroller(context)

    private fun refreshRecents() {
        holdRecents = false
        val now = recentsOf(tab)
        if (shown[tab] == now) return
        shown[tab] = now
        rebuild()
    }

    private fun rebuild() {
        pages = EmojiGrid.pages(tab, emoji, shown[tab].orEmpty(), tones, textPages(tab))
        page = page.coerceIn(0, maxOf(0, pages.size - 1))
        scrollGrid(offset)
        layoutBar()
        invalidate()
    }

    private val textProbe = Paint().apply { typeface = font }
    /** Whether the fonts draw a character, as found so far. */
    private val drawn = HashMap<Int, Boolean>()

    /**
     * Whether the phone's fonts (MiSans, then the system's) draw every character of `text`, so
     * nothing shows as an empty box.
     */
    private fun canDraw(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!Character.isWhitespace(c) && !drawn.getOrPut(c) { textProbe.hasGlyph(String(Character.toChars(c))) }) return false
            i += Character.charCount(c)
        }
        return true
    }

    private fun textPages(tab: EmojiTab): List<TextPage> = drawable.getOrPut(tab) {
        EmojiGrid.textPages(tab).map { p -> p.copy(items = p.items.filter(::canDraw)) }.filter { it.items.isNotEmpty() }
    }

    /**
     * The panel is shown: the tab it was left on, on that tab's first page (its recents when
     * there are any, else its first category), at the top. Every other tab opens on its first
     * page too.
     */
    fun open() {
        places.clear()
        stopBar()
        refreshRecents()
        jumpTo(EmojiGrid.firstPage(recentsOf(tab)))
    }

    /**
     * Shows page `p` at its top at once, stopping any scroll or slide, and brings its cell on
     * the bar into view: at once, or (`slide`) scrolling there.
     */
    private fun jumpTo(p: Int, slide: Boolean = false) {
        scroller.forceFinished(true)
        settler.forceFinished(true)
        shift = 0f
        leaving = -1
        turnTo(p)
        revealBarCell(page, slide)
    }

    /** Makes `p` the page shown, at its top; the sideways shift is left to the caller. */
    private fun turnTo(p: Int) {
        page = p.coerceIn(0, maxOf(0, pages.size - 1))
        offset = 0f
        refreshRecents()
        invalidate()
    }

    // ---- geometry -----------------------------------------------------------------------------

    /** Where the grid starts: right under the tab row. */
    fun gridTop() = headerHeight
    fun gridBottom() = height - barHeight
    fun cellWidth() = width.toFloat() / EmojiGrid.columns(tab)
    /** Four rows fill the grid exactly, whatever the keyboard's height. */
    fun rowHeight() = (gridBottom() - gridTop()) / EmojiGrid.VISIBLE_ROWS

    private fun columns() = EmojiGrid.columns(tab)
    private fun cells(p: Int = page) = pages.getOrNull(p)?.cells.orEmpty()

    private fun maxScroll(): Float {
        val p = pages.getOrNull(page) ?: return 0f
        val rows = EmojiGrid.rows(tab, p)
        if (rows <= EmojiGrid.VISIBLE_ROWS) return 0f
        // A long page scrolls far enough for its last row to clear the search button.
        val clear = if (tab == EmojiTab.EMOJI) gridBottom() - searchButton().top else 0f
        return maxOf(0f, rows * rowHeight() + clear - (gridBottom() - gridTop()))
    }

    private fun scrollGrid(y: Float) { offset = y.coerceIn(0f, maxScroll()) }

    fun searchButton(): RectF {
        val r = 26 * dp
        val cx = width - 16 * dp - r
        val cy = gridBottom() - 12 * dp - r
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    // ---- the category bar --------------------------------------------------------------------

    /**
     * Where the bar's page cells run, before it is scrolled: cell i from barEdges[i] to
     * barEdges[i + 1].
     */
    private var barEdges = FloatArray(1)
    private var barScroll = 0f
    /** Scrolls the bar: after a fling of the finger ([barFlung]), or to bring a cell into view. */
    private val barScroller = OverScroller(context)
    private var barFlung = false

    /** How far the bar's cells are scrolled to the left, in px. */
    val barScrolled get() = barScroll

    /** Backspace's cell, fixed at the bar's right end: an eighth of the width. */
    private fun backspaceWidth() = width / 8f
    /** The part of the bar the page cells scroll in: all of it left of Backspace. */
    private fun barRoom() = width - backspaceWidth()
    private fun maxBarScroll() = maxOf(0f, barEdges.last() - barRoom())
    private fun barTextSize() = (if (tab == EmojiTab.EMOTICONS) 14 else 16) * sp

    /**
     * The bar's cells: an icon or a sample, at least 40 dp wide, the sample's width and some room
     * beside it. When they fit they share the room left of Backspace, alike if they can be (the
     * emoji tab's seven, as Laban Key's, are an eighth of the width each); when they don't, they
     * keep their own widths and the bar scrolls, the letters' sized so that half a cell shows at
     * its edge.
     */
    private fun layoutBar() {
        val room = barRoom()
        // Icons are narrower than samples: on a narrow panel (one-handed) they keep sharing the
        // room down to 32 dp each, rather than scroll.
        val least = if (pages.isNotEmpty() && pages.all { it.icon != null }) maxOf(32 * dp, minOf(40 * dp, room / pages.size)) else 40 * dp
        glyph.typeface = font
        glyph.textSize = barTextSize()
        val own = pages.map { if (it.icon != null) least else maxOf(least, glyph.measureText(it.sample) + 24 * dp) }
        val sum = own.sum()
        val widths = when {
            own.isEmpty() || room <= 0 -> own
            own.max() * own.size <= room -> own.map { room / own.size }
            sum <= room -> own.map { it * room / sum }
            own.all { it == least } -> own.map { room / (floor(room / least) + 0.5f) }
            else -> own
        }
        barEdges = FloatArray(widths.size + 1)
        widths.forEachIndexed { i, w -> barEdges[i + 1] = barEdges[i] + w }
        barScroll = barScroll.coerceIn(0f, maxBarScroll())
    }

    /** The bar back at its start, stopped. */
    private fun stopBar() {
        barScroller.forceFinished(true)
        barFlung = false
        barScroll = 0f
    }

    /** Page `p`'s cell on the bar, where it is drawn now. Public for the tests. */
    fun barCell(p: Int): RectF {
        val top = gridBottom()
        return RectF(barEdges[p] - barScroll, top, barEdges[p + 1] - barScroll, top + barHeight)
    }

    /** The page whose bar cell is at x, or -1. */
    private fun barPageAt(x: Float): Int {
        if (x < 0 || x >= barRoom()) return -1
        val at = x + barScroll
        return pages.indices.firstOrNull { at >= barEdges[it] && at < barEdges[it + 1] } ?: -1
    }

    /**
     * Scrolls the bar so page p's cell shows whole, with a little of the next one beside it: at
     * once, or (`slide`) gliding there.
     */
    private fun revealBarCell(p: Int, slide: Boolean) {
        if (p !in pages.indices || width == 0) return
        val peek = 20 * dp
        val target = when {
            barEdges[p] - peek < barScroll -> barEdges[p] - peek
            barEdges[p + 1] + peek > barScroll + barRoom() -> barEdges[p + 1] + peek - barRoom()
            else -> return
        }.coerceIn(0f, maxBarScroll())
        barScroller.forceFinished(true)
        barFlung = false
        if (!slide || abs(target - barScroll) < 1) {
            barScroll = target
            invalidate()
            return
        }
        barScroller.startScroll(barScroll.roundToInt(), 0, (target - barScroll).roundToInt(), 0, 200)
        postInvalidateOnAnimation()
    }

    /** The cell of the shown page at (x, y), or -1. */
    private fun cellAt(x: Float, y: Float): Int {
        if (y < gridTop() || y >= gridBottom() || x < 0 || x >= width) return -1
        val r = ((y - gridTop() + offset) / rowHeight()).toInt()
        val c = (x / cellWidth()).toInt().coerceAtMost(columns() - 1)
        val i = r * columns() + c
        return if (i < cells().size) i else -1
    }

    private fun cellRect(i: Int): RectF {
        val cw = cellWidth()
        val rh = rowHeight()
        val left = (i % columns()) * cw
        val top = gridTop() + (i / columns()) * rh - offset
        return RectF(left, top, left + cw, top + rh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollGrid(offset)
        layoutBar()
        revealBarCell(page, slide = false)
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        drawGrid(canvas)
        drawTabs(canvas)
        if (tab == EmojiTab.EMOJI) drawSearchButton(canvas)
        drawBar(canvas)
        drawTonePopup(canvas)
    }

    private fun label(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int, tf: Typeface? = font) {
        glyph.typeface = tf
        glyph.textSize = size
        glyph.color = color
        val m = glyph.fontMetrics
        canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2, glyph)
    }

    /** The text size, at most `size`, at which `text` fits in `room`. */
    private fun fit(text: String, size: Float, room: Float): Float {
        glyph.typeface = font
        glyph.textSize = size
        val wide = glyph.measureText(text)
        return if (wide > room) size * room / wide else size
    }

    private fun stroke(color: Int, width: Float = 1.8f * dp) {
        line.color = color
        line.strokeWidth = width
    }

    private fun drawTabs(canvas: Canvas) {
        fill.color = palette.strip
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, fill)
        val cy = headerHeight / 2
        drawKeyboard(canvas, tabWidth / 2, cy, 10 * dp, palette.dim)
        val tabs = listOf(EmojiTab.EMOJI to "☺", EmojiTab.EMOTICONS to ":-)", EmojiTab.SYMBOLS to "Ω", EmojiTab.LETTERS to "Aa")
        tabs.forEachIndexed { i, (t, text) ->
            val cx = tabWidth * (i + 1) + tabWidth / 2
            val color = if (t == tab) palette.glyph else palette.dim
            if (t == EmojiTab.EMOJI) drawSmile(canvas, cx, cy, 10 * dp, color)
            else label(canvas, text, cx, cy, 18 * sp, color)
            if (t == tab) {
                fill.color = palette.accent
                canvas.drawRoundRect(RectF(cx - 18 * dp, headerHeight - 3 * dp, cx + 18 * dp, headerHeight), 2 * dp, 2 * dp, fill)
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0f, gridTop(), width.toFloat(), gridBottom())
        drawPage(canvas, page, shift, offset)
        // Mid-swipe, the next page shows beside the shown one: at its top, or as it was left.
        val side = if (shift > 0) page - 1 else if (shift < 0) page + 1 else -1
        if (side in pages.indices) {
            drawPage(canvas, side, shift + if (shift > 0) -width else width, if (side == leaving) leavingScroll else 0f)
        }
        canvas.restore()
    }

    private fun drawPage(canvas: Canvas, p: Int, dx: Float, scroll: Float) {
        val cells = cells(p)
        val cols = columns()
        val rh = rowHeight()
        val cw = cellWidth()
        val top = gridTop()
        val first = (scroll / rh).toInt().coerceAtLeast(0) * cols
        val end = minOf(cells.size, (((scroll + gridBottom() - top) / rh).toInt() + 1) * cols)
        val emojiSize = emojiSize()
        for (i in first until end) {
            val left = dx + (i % cols) * cw
            val y = top + (i / cols) * rh - scroll
            if (p == page && i == pressed) {
                fill.color = palette.pressed
                canvas.drawRoundRect(RectF(left + 3 * dp, y + 3 * dp, left + cw - 3 * dp, y + rh - 3 * dp), 10 * dp, 10 * dp, fill)
            }
            val text = cells[i].text
            when (tab) {
                EmojiTab.EMOJI -> label(canvas, text, left + cw / 2, y + rh / 2, emojiSize, palette.glyph, null)
                EmojiTab.EMOTICONS -> label(canvas, text, left + cw / 2, y + rh / 2, fit(text, 17 * sp, cw - 12 * dp), palette.glyph)
                EmojiTab.SYMBOLS, EmojiTab.LETTERS ->
                    label(canvas, text, left + cw / 2, y + rh / 2, fit(text, 24 * sp, cw - 12 * dp), palette.glyph)
            }
        }
    }

    /** How tall the phone's emoji font draws an emoji, for each px of text size. */
    private val emojiHeight: Float by lazy {
        val probe = Paint().apply { textSize = 100f }
        val b = Rect()
        probe.getTextBounds("😀", 0, "😀".length, b)
        (b.height() / 100f).takeIf { it in 0.6f..1.6f } ?: 1.1f
    }

    /**
     * An emoji's text size: as Laban Key's, about 70 px tall in a 157 px row (the font's bounds
     * run a little taller than what it draws), and never wider than its cell allows.
     */
    private fun emojiSize() = minOf(rowHeight() * 0.48f, cellWidth() * 0.62f) / emojiHeight

    private fun drawSearchButton(canvas: Canvas) {
        val b = searchButton()
        fill.color = palette.preview
        canvas.drawCircle(b.centerX(), b.centerY(), b.width() / 2, fill)
        stroke(palette.glyph, 2.4f * dp)
        val s = 8 * dp
        canvas.drawCircle(b.centerX() - s * 0.2f, b.centerY() - s * 0.2f, s * 0.6f, line)
        canvas.drawLine(b.centerX() + s * 0.25f, b.centerY() + s * 0.25f, b.centerX() + s * 0.85f, b.centerY() + s * 0.85f, line)
    }

    private fun drawBar(canvas: Canvas) {
        val top = gridBottom()
        fill.color = palette.strip
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        val cy = top + barHeight / 2
        // The highlight moves on once a swipe is past half way.
        val lit = when {
            shift < -width / 2f && page < pages.size - 1 -> page + 1
            shift > width / 2f && page > 0 -> page - 1
            else -> page
        }
        canvas.save()
        canvas.clipRect(0f, top, barRoom(), height.toFloat())
        pages.forEachIndexed { i, pg ->
            val cell = barCell(i)
            if (cell.right <= 0 || cell.left >= barRoom()) return@forEachIndexed
            if (i == lit) {
                fill.color = palette.space
                canvas.drawRoundRect(RectF(cell.left + 6 * dp, top + 6 * dp, cell.right - 6 * dp, top + barHeight - 6 * dp), 10 * dp, 10 * dp, fill)
            }
            val color = if (i == lit) palette.glyph else palette.dim
            val icon = pg.icon
            if (icon != null) drawIcon(canvas, icon, cell.centerX(), cy, 9 * dp, color)
            else label(canvas, pg.sample, cell.centerX(), cy, fit(pg.sample, barTextSize(), cell.width() - 16 * dp), color)
        }
        canvas.restore()
        drawBackspace(canvas, width - backspaceWidth() / 2, cy, 10 * dp, palette.glyph)
    }

    // ---- icons: lines in the palette's colours, like the keys' --------------------------------

    /** A keyboard: back to the letters. */
    private fun drawKeyboard(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color)
        canvas.drawRoundRect(RectF(cx - s * 1.25f, cy - s * 0.8f, cx + s * 1.25f, cy + s * 0.8f), s * 0.25f, s * 0.25f, line)
        fill.color = color
        for (row in 0..1) for (k in -2..2) {
            canvas.drawCircle(cx + k * s * 0.42f, cy - s * 0.38f + row * s * 0.4f, 1.2f * dp, fill)
        }
        canvas.drawLine(cx - s * 0.5f, cy + s * 0.45f, cx + s * 0.5f, cy + s * 0.45f, line)
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color)
        canvas.drawCircle(cx, cy, s, line)
        fill.color = color
        canvas.drawCircle(cx - s * 0.35f, cy - s * 0.25f, 1.6f * dp, fill)
        canvas.drawCircle(cx + s * 0.35f, cy - s * 0.25f, 1.6f * dp, fill)
        canvas.drawArc(cx - s * 0.5f, cy - s * 0.3f, cx + s * 0.5f, cy + s * 0.55f, 20f, 140f, false, line)
    }

    private fun drawIcon(canvas: Canvas, icon: PageIcon, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color)
        fill.color = color
        when (icon) {
            PageIcon.RECENTS -> {
                // A clock.
                canvas.drawCircle(cx, cy, s, line)
                canvas.drawLine(cx, cy, cx, cy - s * 0.55f, line)
                canvas.drawLine(cx, cy, cx + s * 0.45f, cy + s * 0.25f, line)
            }
            PageIcon.SMILEYS -> drawSmile(canvas, cx, cy, s, color)
            PageIcon.FOOD -> {
                // A burger: the domed top, the filling, the flat bottom.
                val top = Path().apply {
                    arcTo(RectF(cx - s, cy - s * 0.95f, cx + s, cy + s * 0.45f), 180f, 180f, true)
                    close()
                }
                canvas.drawPath(top, line)
                canvas.drawLine(cx - s, cy + s * 0.15f, cx + s, cy + s * 0.15f, line)
                canvas.drawRoundRect(RectF(cx - s, cy + s * 0.5f, cx + s, cy + s * 0.92f), s * 0.2f, s * 0.2f, line)
            }
            PageIcon.ACTIVITY -> {
                // A ball and its seams.
                canvas.drawCircle(cx, cy, s, line)
                canvas.drawLine(cx, cy - s, cx, cy + s, line)
                canvas.drawLine(cx - s, cy, cx + s, cy, line)
                val r = s * 0.95f
                canvas.drawArc(cx - s * 1.3f - r, cy - r, cx - s * 1.3f + r, cy + r, -48f, 96f, false, line)
                canvas.drawArc(cx + s * 1.3f - r, cy - r, cx + s * 1.3f + r, cy + r, 132f, 96f, false, line)
            }
            PageIcon.TRAVEL -> {
                // A car from the front: the roof, the body, two lamps and the wheels.
                val roof = Path().apply {
                    moveTo(cx - s * 0.78f, cy - s * 0.1f)
                    lineTo(cx - s * 0.55f, cy - s * 0.75f)
                    lineTo(cx + s * 0.55f, cy - s * 0.75f)
                    lineTo(cx + s * 0.78f, cy - s * 0.1f)
                }
                canvas.drawPath(roof, line)
                canvas.drawRoundRect(RectF(cx - s, cy - s * 0.1f, cx + s, cy + s * 0.55f), s * 0.22f, s * 0.22f, line)
                canvas.drawCircle(cx - s * 0.55f, cy + s * 0.22f, s * 0.13f, fill)
                canvas.drawCircle(cx + s * 0.55f, cy + s * 0.22f, s * 0.13f, fill)
                canvas.drawRoundRect(RectF(cx - s * 0.85f, cy + s * 0.55f, cx - s * 0.45f, cy + s * 0.92f), s * 0.1f, s * 0.1f, fill)
                canvas.drawRoundRect(RectF(cx + s * 0.45f, cy + s * 0.55f, cx + s * 0.85f, cy + s * 0.92f), s * 0.1f, s * 0.1f, fill)
            }
            PageIcon.ANIMALS -> {
                // A bear's face: head and ears in one outline, the eyes, the muzzle.
                val head = Path().apply { addCircle(cx, cy + s * 0.12f, s * 0.82f, Path.Direction.CW) }
                val ears = Path().apply {
                    addCircle(cx - s * 0.62f, cy - s * 0.55f, s * 0.32f, Path.Direction.CW)
                    addCircle(cx + s * 0.62f, cy - s * 0.55f, s * 0.32f, Path.Direction.CW)
                }
                head.op(ears, Path.Op.UNION)
                canvas.drawPath(head, line)
                canvas.drawCircle(cx - s * 0.32f, cy - s * 0.05f, 1.4f * dp, fill)
                canvas.drawCircle(cx + s * 0.32f, cy - s * 0.05f, 1.4f * dp, fill)
                canvas.drawOval(RectF(cx - s * 0.34f, cy + s * 0.22f, cx + s * 0.34f, cy + s * 0.68f), line)
                canvas.drawCircle(cx, cy + s * 0.36f, s * 0.1f, fill)
            }
            PageIcon.OBJECTS -> {
                // A light bulb.
                val c = cy - s * 0.25f
                val r = s * 0.62f
                val bulb = Path().apply {
                    arcTo(RectF(cx - r, c - r, cx + r, c + r), 135f, 270f, true)
                    lineTo(cx + s * 0.28f, cy + s * 0.5f)
                    lineTo(cx - s * 0.28f, cy + s * 0.5f)
                    close()
                }
                canvas.drawPath(bulb, line)
                canvas.drawLine(cx - s * 0.28f, cy + s * 0.72f, cx + s * 0.28f, cy + s * 0.72f, line)
                canvas.drawLine(cx - s * 0.15f, cy + s * 0.93f, cx + s * 0.15f, cy + s * 0.93f, line)
            }
        }
    }

    private fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color)
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

    // ---- the skin-tone popup -------------------------------------------------------------------

    private var toneBase: String? = null
    private var toneCell: RectF? = null
    private var toneChoice = 0

    private fun toneRects(): List<RectF> {
        val cell = toneCell ?: return emptyList()
        val w = cellWidth()
        val n = 6
        val left = (cell.centerX() - n * w / 2).coerceIn(0f, width - n * w)
        val top = maxOf(0f, cell.top - w * 1.05f)
        return List(n) { RectF(left + it * w, top, left + (it + 1) * w, top + w) }
    }

    private fun drawTonePopup(canvas: Canvas) {
        val base = toneBase ?: return
        val rects = toneRects()
        if (rects.isEmpty()) return
        fill.color = palette.preview
        canvas.drawRoundRect(RectF(rects.first().left, rects.first().top, rects.last().right, rects.last().bottom), 12 * dp, 12 * dp, fill)
        SkinTones.variants(base).forEachIndexed { i, v ->
            if (i == toneChoice) {
                fill.color = palette.space
                canvas.drawRoundRect(RectF(rects[i]).apply { inset(3 * dp, 3 * dp) }, 10 * dp, 10 * dp, fill)
            }
            label(canvas, v, rects[i].centerX(), rects[i].centerY(), cellWidth() * 0.5f, palette.glyph, null)
        }
    }

    // ---- touch ---------------------------------------------------------------------------------

    private enum class Drag { NONE, PAGE, SCROLL, BAR }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** A sideways fling this fast turns the page however short it was (ViewPager's figure). */
    private val pageFling = 400 * dp
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var shiftFrom = 0f
    private var drag = Drag.NONE
    /** The finger has gone further than a tap may. */
    private var moved = false
    /** The finger went down on a grid that was still moving: it stops it, and types nothing. */
    private var stopped = false
    private var inGrid = false
    /** The finger went down on the bar's page cells, which it may drag sideways. */
    private var inBar = false
    /** Where the bar would be scrolled with the finger at x = 0, while it drags the bar. */
    private var barFrom = 0f
    private var velocity: VelocityTracker? = null
    /** The cell of the shown page under a finger that may yet be a tap, or -1. */
    private var pressed = -1

    private val hold = Runnable {
        val cell = cells().getOrNull(pressed) ?: return@Runnable
        if (!cell.skinTones) return@Runnable
        toneBase = baseOf(cell.text)
        toneCell = cellRect(pressed)
        toneChoice = 0
        invalidate()
    }

    /** The base emoji of a toned one (what the index lists). */
    private fun baseOf(text: String): String {
        var t = text
        for (m in SkinTones.modifiers) t = t.replace(m, "")
        return emoji.firstOrNull { it.text == t || it.text.replace("️", "") == t }?.text ?: t
    }

    override fun computeScroll() {
        var more = false
        if (scroller.computeScrollOffset()) {
            scrollGrid(scroller.currY.toFloat())
            more = true
        }
        if (settler.computeScrollOffset()) {
            shift = settler.currX.toFloat()
            if (settler.isFinished) leaving = -1
            more = true
        }
        if (barScroller.computeScrollOffset()) {
            barScroll = barScroller.currX.toFloat().coerceIn(0f, maxBarScroll())
            more = true
        }
        if (more) postInvalidateOnAnimation()
    }

    /** A drag past the first or last page gives a little, then springs back. */
    private fun resisted(dx: Float): Float {
        val edge = (dx > 0 && page == 0) || (dx < 0 && page >= pages.size - 1)
        return if (edge) dx / 3 else dx.coerceIn(-width.toFloat(), width.toFloat())
    }

    /**
     * The finger let go of a sideways drag: a fling, or a drag past a quarter of the width,
     * turns the page; then the pages slide the rest of the way. A cancelled drag goes back.
     */
    private fun settlePage(vx: Float, mayTurn: Boolean = true) {
        val flung = abs(vx) > pageFling && (vx < 0) == (shift < 0)
        val far = mayTurn && abs(shift) > width / 4f
        val next = when {
            shift < 0 && (flung || far) && page < pages.size - 1 -> page + 1
            shift > 0 && (flung || far) && page > 0 -> page - 1
            else -> page
        }
        if (next != page) {
            leaving = page
            leavingScroll = offset
            shift += if (next > page) width else -width
            turnTo(next)
            revealBarCell(next, slide = true)
        }
        val from = shift.roundToInt()
        settler.startScroll(from, 0, -from, 0, (240 * abs(from) / maxOf(1, width)).coerceIn(80, 240))
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Brought up to date first: one whose time has run out isn't moving any more.
                scroller.computeScrollOffset()
                settler.computeScrollOffset()
                barScroller.computeScrollOffset()
                inBar = e.y >= gridBottom() && e.x < barRoom()
                // A finger on the bar stops it where it is; only a fling of it makes this no tap.
                val barMoving = inBar && barFlung && !barScroller.isFinished
                if (inBar) barScroller.forceFinished(true)
                stopped = !scroller.isFinished || !settler.isFinished || barMoving
                scroller.forceFinished(true)
                if (!settler.isFinished) { settler.forceFinished(true); shift = 0f }
                leaving = -1
                downX = e.x; downY = e.y; lastY = e.y
                drag = Drag.NONE
                moved = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                inGrid = e.y >= gridTop() && e.y < gridBottom() &&
                    !(tab == EmojiTab.EMOJI && searchButton().contains(e.x, e.y))
                pressed = if (inGrid && !stopped) cellAt(e.x, e.y) else -1
                if (cells().getOrNull(pressed)?.skinTones == true) postDelayed(hold, 400)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (toneBase != null) {
                    val i = toneRects().indexOfFirst { e.x >= it.left && e.x < it.right }
                    if (i >= 0 && i != toneChoice) { toneChoice = i; invalidate() }
                    return true
                }
                val dx = e.x - downX
                val dy = e.y - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    // No longer a tap: whatever it turns into, it types nothing.
                    moved = true
                    removeCallbacks(hold)
                    pressed = -1
                    if (inGrid) {
                        drag = if (abs(dx) > abs(dy)) Drag.PAGE else Drag.SCROLL
                        shiftFrom = e.x
                        lastY = e.y
                    } else if (inBar && abs(dx) > abs(dy) && maxBarScroll() > 0) {
                        drag = Drag.BAR
                        barFrom = barScroll + e.x
                    }
                    invalidate()
                }
                when (drag) {
                    Drag.PAGE -> { shift = resisted(e.x - shiftFrom); invalidate() }
                    Drag.SCROLL -> { scrollGrid(offset - (e.y - lastY)); invalidate() }
                    Drag.BAR -> { barScroll = (barFrom - e.x).coerceIn(0f, maxBarScroll()); invalidate() }
                    Drag.NONE -> {}
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val base = toneBase
                if (base != null) {
                    val toned = SkinTones.variants(base)[toneChoice]
                    toneBase = null
                    listener?.onSkinTone(base, toned)
                    listener?.onEmoji(toned)
                } else when (drag) {
                    Drag.PAGE -> settlePage(velocity?.xVelocity ?: 0f)
                    Drag.SCROLL -> {
                        val vy = velocity?.yVelocity ?: 0f
                        if (abs(vy) > minFling) {
                            scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                            postInvalidateOnAnimation()
                        }
                    }
                    Drag.BAR -> {
                        val vx = velocity?.xVelocity ?: 0f
                        if (abs(vx) > minFling) {
                            barFlung = true
                            // The scroller counts whole px: rounded up, it reaches the bar's end.
                            barScroller.fling(barScroll.roundToInt(), 0, -vx.toInt(), 0, 0, ceil(maxBarScroll()).toInt(), 0, 0)
                            postInvalidateOnAnimation()
                        }
                    }
                    Drag.NONE -> if (!moved && !stopped) tap(downX, downY)
                }
                velocity?.recycle(); velocity = null
                pressed = -1
                drag = Drag.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                toneBase = null
                pressed = -1
                if (drag == Drag.PAGE) settlePage(0f, mayTurn = false)
                drag = Drag.NONE
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    /** A tap where the finger went down and lifted. Public for the tests. */
    fun tap(x: Float, y: Float) {
        val l = listener
        when {
            y < headerHeight -> when ((x / tabWidth).toInt()) {
                0 -> l?.onClosePanel()
                1 -> tab = EmojiTab.EMOJI
                2 -> tab = EmojiTab.EMOTICONS
                3 -> tab = EmojiTab.SYMBOLS
                4 -> tab = EmojiTab.LETTERS
            }
            y >= gridBottom() -> {
                if (x >= barRoom()) l?.onBackspace()
                else barPageAt(x).takeIf { it >= 0 }?.let { jumpTo(it, slide = true) }
            }
            tab == EmojiTab.EMOJI && searchButton().contains(x, y) -> l?.onEmojiSearch()
            else -> cells().getOrNull(cellAt(x, y))?.let {
                // The recents page stays as it is under the finger until it is left.
                if (page == EmojiGrid.RECENTS) holdRecents = true
                l?.onEmoji(it.text, tab)
            }
        }
    }
}
