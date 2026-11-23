package com.xern.xrnkey

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.xern.xrnkey.engine.Emoji
import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.ime.EmojiMemory
import com.xern.xrnkey.keyboard.EmojiGrid
import com.xern.xrnkey.keyboard.EmojiPanelView
import com.xern.xrnkey.keyboard.EmojiTab
import com.xern.xrnkey.keyboard.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The emoji panel at the owner's phone's size (1080 px wide, 2.5 px a dp, as tall as the
 * letters' 870 px), laid out as Laban Key's: the grid right under the tabs, four rows of eight,
 * one category to a page; a sideways swipe turns the page and only a tap types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmojiPanelTest {
    private val typed = mutableListOf<String>()
    /** The tab each of [typed] came from. */
    private val typedOn = mutableListOf<EmojiTab>()
    private var closed = 0
    private var searched = 0
    private var deleted = 0
    private val toned = mutableListOf<Pair<String, String>>()
    private var t = 0L
    private val all: List<Emoji> = EmojiIndex.load(File(System.getProperty("xrnkey.emojiIndex")!!).readBytes())!!.use { it.all }
    private val groupOf = all.associate { it.text to it.group }

    // The phone's geometry: 48 dp tabs and bar, and between them the grid's four rows of eight.
    private val height = 870
    private val header = 120f
    private val row = (height - 240) / 4f
    private val cell = 135f
    private val bar = height - 60f

    private fun panel(recents: List<String> = emptyList(), palette: Palette = Palette.DARK): EmojiPanelView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val p = EmojiPanelView(activity)
        p.palette = palette
        p.emoji = all
        p.recents = recents
        p.listener = object : EmojiPanelView.Listener {
            override fun onEmoji(text: String, tab: EmojiTab) { typed += text; typedOn += tab }
            override fun onClosePanel() { closed++ }
            override fun onEmojiSearch() { searched++ }
            override fun onBackspace() { deleted++ }
            override fun onSkinTone(base: String, toned: String) { this@EmojiPanelTest.toned += base to toned }
        }
        val frame = FrameLayout(activity)
        frame.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        activity.setContentView(frame)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1080, p.width)
        assertEquals(height, p.height)
        p.open()
        return p
    }

    private fun touch(p: EmojiPanelView, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0, t, action, x, y, 0)
        p.onTouchEvent(e)
        e.recycle()
    }

    /** Lets the clock run until every slide and fling has stopped. */
    private fun settle(p: EmojiPanelView) {
        repeat(10) {
            t += 500
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            p.computeScroll()
        }
    }

    /** A finger from (x0, y0) to (x1, y1) in `steps` moves `ms` apart, lifted, and let settle. */
    private fun drag(p: EmojiPanelView, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 10, ms: Long = 16, settle: Boolean = true) {
        touch(p, MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1..steps) {
            t += ms
            touch(p, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
        }
        t += ms
        touch(p, MotionEvent.ACTION_UP, x1, y1)
        if (settle) settle(p)
    }

    private fun tapAt(p: EmojiPanelView, x: Float, y: Float) {
        touch(p, MotionEvent.ACTION_DOWN, x, y)
        t += 60
        touch(p, MotionEvent.ACTION_UP, x, y)
    }

    private fun barCell(i: Int) = i * cell + cell / 2

    @Test
    fun theGridStartsRightUnderTheTabsInFourRowsOfEight() {
        val p = panel()
        assertEquals(header, p.gridTop(), 0f)  // no band between the tabs and the grid
        assertEquals(row, p.rowHeight(), 0f)
        assertEquals(cell, p.cellWidth(), 0f)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        val smileys = p.pages[EmojiGrid.SMILEYS].cells.map { it.text }
        tapAt(p, 2 * cell + 10, header + 2)    // the first row, right under the tabs
        tapAt(p, 10f, header + 4 * row - 2)    // the fourth row, the last above the bar
        assertEquals(listOf(smileys[2], smileys[24]), typed)
        // Below it is the bar: its first cell turns to recents, and types nothing.
        tapAt(p, 10f, header + 4 * row + 2)
        assertEquals(EmojiGrid.RECENTS, p.page)
        assertEquals(2, typed.size)
    }

    @Test
    fun theSevenPagesHoldTheirOwnGroups() {
        val p = panel(listOf("🎂", "☕"))
        assertEquals(
            listOf("Recents", "Smileys & people", "Food & drink", "Activity", "Travel & places", "Animals & nature", "Objects"),
            p.pages.map { it.name },
        )
        assertEquals(listOf("🎂", "☕"), p.pages[0].cells.map { it.text })
        assertEquals(
            listOf(
                setOf("Smileys & Emotion", "People & Body"), setOf("Food & Drink"), setOf("Activities"),
                setOf("Travel & Places"), setOf("Animals & Nature"), setOf("Objects", "Symbols", "Flags"),
            ),
            p.pages.drop(1).map { pg -> pg.cells.map { groupOf[it.text] }.toSet() },
        )
        assertEquals("😀", p.pages[1].cells.first().text)
    }

    @Test
    fun aSidewaysSwipeTurnsThePageAndNeverTypes() {
        val p = panel()
        assertEquals(EmojiGrid.SMILEYS, p.page)
        // The swipe that typed the emoji where it lifted (😘 on the phone).
        drag(p, 900f, 400f, 200f, 400f)
        assertEquals(EmojiGrid.SMILEYS + 1, p.page)
        assertEquals(0f, p.pageShift, 0f)
        drag(p, 200f, 400f, 900f, 400f)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(emptyList<String>(), typed)

        // The page follows the finger, and a short, slow drag lets it go back.
        touch(p, MotionEvent.ACTION_DOWN, 900f, 400f)
        t += 100; touch(p, MotionEvent.ACTION_MOVE, 850f, 400f)  // past the touch slop
        t += 100; touch(p, MotionEvent.ACTION_MOVE, 750f, 400f)
        assertEquals(-100f, p.pageShift, 0f)
        t += 300; touch(p, MotionEvent.ACTION_MOVE, 750f, 400f)
        t += 300; touch(p, MotionEvent.ACTION_UP, 750f, 400f)
        settle(p)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(0f, p.pageShift, 0f)

        // A quick flick turns it however short.
        drag(p, 700f, 400f, 560f, 400f, steps = 4, ms = 8)
        assertEquals(EmojiGrid.SMILEYS + 1, p.page)

        // Before the first page there is nothing to turn to.
        drag(p, 300f, 400f, 1000f, 400f)
        drag(p, 300f, 400f, 1000f, 400f)
        assertEquals(EmojiGrid.RECENTS, p.page)
        drag(p, 300f, 400f, 1000f, 400f)
        assertEquals(EmojiGrid.RECENTS, p.page)
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun aPageScrollsOnlyWithinItsCategoryAndEachPageStartsAtItsTop() {
        val p = panel()
        val page = p.pages[EmojiGrid.SMILEYS].cells
        repeat(40) { drag(p, 100f, 700f, 100f, 150f) }
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertTrue(p.gridScroll > 0)
        assertEquals(emptyList<String>(), typed)
        // At the end the last row sits above the search button, and nothing comes after it.
        val lastRow = (page.size - 1) / 8
        val bottom = p.searchButton().top
        tapAt(p, 10f, bottom - row / 2)
        tapAt(p, 10f, bottom + 10)
        assertEquals(listOf(page[lastRow * 8].text), typed)
        assertEquals("People & Body", groupOf[typed.single()])

        // The next page opens at its top, and so does this one when it comes back.
        drag(p, 900f, 400f, 200f, 400f)
        assertEquals(0f, p.gridScroll, 0f)
        drag(p, 200f, 400f, 900f, 400f)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(0f, p.gridScroll, 0f)
    }

    @Test
    fun aTapOnAMovingGridStopsItAndTypesNothing() {
        val p = panel()
        drag(p, 100f, 700f, 100f, 150f, steps = 5, ms = 10, settle = false)  // a fling
        val at = p.gridScroll
        tapAt(p, 10f, header + 10)
        assertEquals(emptyList<String>(), typed)
        settle(p)
        assertEquals(at, p.gridScroll, 0f)
        // The same after a swipe, while the pages still slide.
        drag(p, 900f, 400f, 200f, 400f, settle = false)
        tapAt(p, 10f, header + 10)
        assertEquals(emptyList<String>(), typed)
        assertEquals(EmojiGrid.SMILEYS + 1, p.page)
        assertEquals(0f, p.pageShift, 0f)
        // Once it is still, a tap types.
        tapAt(p, 10f, header + 10)
        assertEquals(listOf(p.pages[EmojiGrid.SMILEYS + 1].cells[0].text), typed)
    }

    @Test
    fun theBarJumpsToEachPageAndBackspaceDeletes() {
        val p = panel()
        tapAt(p, barCell(2), bar)
        assertEquals(2, p.page)
        tapAt(p, 10f, header + 10)
        tapAt(p, barCell(6), bar)
        assertEquals(6, p.page)
        tapAt(p, 10f, header + 10)
        assertEquals(listOf(p.pages[2].cells[0].text, p.pages[6].cells[0].text), typed)
        assertEquals("Food & Drink", groupOf[typed[0]])
        assertEquals("Objects", groupOf[typed[1]])
        tapAt(p, barCell(7), bar)
        assertEquals(1, deleted)
        assertEquals(6, p.page)
    }

    @Test
    fun itOpensOnRecentsWhenThereAreAnyAndATapKeepsThemInPlace() {
        val p = panel(listOf("🎂", "☕", "🔥"))
        assertEquals(EmojiGrid.RECENTS, p.page)
        // A tap types, and the panel stays: nothing closes it.
        tapAt(p, cell + 60, header + 60)
        assertEquals(listOf("☕"), typed)
        assertEquals(0, closed)
        // The service moves it to the front of recents; the page doesn't move under the finger.
        p.recents = listOf("☕", "🎂", "🔥")
        tapAt(p, cell + 60, header + 60)
        assertEquals(listOf("☕", "☕"), typed)
        // Opened again, recents are in their new order.
        p.open()
        assertEquals(listOf("☕", "🎂", "🔥"), p.pages[0].cells.map { it.text })
        // No recents: it opens on smileys and people.
        p.recents = emptyList()
        p.open()
        assertEquals(EmojiGrid.SMILEYS, p.page)
    }

    @Test
    fun theTabsTheKeyboardButtonAndSearch() {
        val p = panel()
        val tab = 64 * 2.5f
        tapAt(p, 975f, 655f)  // the search button: 26 dp round, 16 dp from the side, 12 dp over the bar
        assertEquals(1, searched)
        tapAt(p, 30f, 20f)    // the keyboard
        assertEquals(1, closed)
        tapAt(p, tab * 2 + 10, 20f)
        assertEquals(EmojiTab.EMOTICONS, p.tab)
        assertEquals(270f, p.cellWidth(), 0f)  // kaomoji are wide: four to a row
        assertEquals(
            listOf("", ":-)", "(^▽^)", "(♡‿♡)", "(T_T)", "(`Д´)", "(⊙_⊙)", "(^o^)/", "ʕ•ᴥ•ʔ", "(っ˘ω˘ς)"),
            p.pages.map { it.sample },
        )
        assertEquals(EmojiGrid.SMILEYS, p.page)  // no kaomoji recents yet: the first faces
        tapAt(p, 30f, header + 10)
        drag(p, 900f, 400f, 200f, 400f)
        tapAt(p, 30f, header + 10)
        assertEquals(listOf(":)", "(^▽^)"), typed)
        assertEquals(listOf(EmojiTab.EMOTICONS, EmojiTab.EMOTICONS), typedOn)
        tapAt(p, tab * 3 + 10, 20f)
        assertEquals(EmojiTab.SYMBOLS, p.tab)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(listOf("", "♡", "ツ", "A", "⑨", "Σ", "⟨⟩", "→", "★"), p.pages.map { it.sample })
        // The bar's nine pages share the room left of Backspace.
        assertEquals(105f, p.barCell(3).width(), 0.01f)
        tapAt(p, p.barCell(3).centerX(), bar)
        tapAt(p, 10f, header + 10)
        assertEquals("₫", typed.last())
        tapAt(p, tab * 4 + 10, 20f)
        assertEquals(EmojiTab.LETTERS, p.tab)
        assertEquals("A", p.pages[EmojiGrid.SMILEYS].name)
        tapAt(p, 10f, header + 10)
        assertEquals("🅰", typed.last())
        assertEquals(EmojiTab.LETTERS, typedOn.last())
        // Back to a tab left earlier: where it was left.
        tapAt(p, tab * 3 + 10, 20f)
        assertEquals(3, p.page)
        tapAt(p, tab + 10, 20f)
        assertEquals(EmojiTab.EMOJI, p.tab)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        // Opened again, each tab is on its first page.
        p.tab = EmojiTab.SYMBOLS
        p.open()
        assertEquals(EmojiGrid.SMILEYS, p.page)
    }

    @Test
    fun holdingAPersonOffersSkinTonesAndHoldingAnythingElseTypesItOnLetGo() {
        val p = panel()
        val cells = p.pages[EmojiGrid.SMILEYS].cells
        val i = cells.indexOfFirst { it.text == "👋" }
        assertTrue(cells[i].skinTones)
        // Scroll slowly (no fling) until the waving hand's row is in the grid.
        fun y() = header + (i / 8) * row - p.gridScroll + row / 2
        while (y() > header + 3 * row) drag(p, 100f, 700f, 100f, 400f, steps = 6, ms = 100)
        assertEquals(emptyList<String>(), typed)
        val x = (i % 8) * cell + cell / 2
        touch(p, MotionEvent.ACTION_DOWN, x, y())
        t += 500
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        shot("emoji-panel-tones-dark", p)
        // The popup's six cells sit over the finger; slide to the fourth, the medium tone.
        val popupLeft = (x - 3 * cell).coerceIn(0f, 1080 - 6 * cell)
        touch(p, MotionEvent.ACTION_MOVE, popupLeft + 3 * cell + cell / 2, y() - row)
        touch(p, MotionEvent.ACTION_UP, popupLeft + 3 * cell + cell / 2, y() - row)
        assertEquals(listOf("👋" to "👋🏽"), toned)
        assertEquals(listOf("👋🏽"), typed)
        // Held without moving, an emoji without tones types once, when the finger lifts.
        val j = cells.indexOfFirst { !it.skinTones && (it.text != "👋") && cells.indexOf(it) / 8 == i / 8 }
        touch(p, MotionEvent.ACTION_DOWN, (j % 8) * cell + cell / 2, y())
        t += 800
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(1, typed.size)
        touch(p, MotionEvent.ACTION_UP, (j % 8) * cell + cell / 2, y())
        assertEquals(listOf("👋🏽", cells[j].text), typed)
    }

    private fun shot(name: String, p: EmojiPanelView) {
        val out = File(System.getProperty("xrnkey.shots") ?: "build/shots")
        val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        p.draw(Canvas(bmp))
        val colours = HashSet<Int>()
        for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) colours += bmp.getPixel(x, y)
        assertTrue("$name looks blank", colours.size > 8)
        out.mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The panel as drawn, saved to -Pxrnkey.shots=<dir> (else build/shots) to look at. */
    @Test
    fun shots() {
        val recents = listOf("😂", "❤️", "👍", "🙏", "🥰", "😭", "🎂", "☕", "🔥", "😅")
        for ((name, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            val p = panel(recents, palette)
            shot("emoji-panel-recents-$name", p)
            for (i in 1 until p.pages.size) {
                tapAt(p, barCell(i), bar)
                shot("emoji-panel-page$i-$name", p)
            }
            tapAt(p, barCell(1), bar)
            touch(p, MotionEvent.ACTION_DOWN, 900f, 400f)
            t += 50; touch(p, MotionEvent.ACTION_MOVE, 880f, 400f)
            t += 50; touch(p, MotionEvent.ACTION_MOVE, 800f, 400f)  // past the touch slop
            t += 50; touch(p, MotionEvent.ACTION_MOVE, 450f, 400f)
            shot("emoji-panel-swipe-$name", p)
            touch(p, MotionEvent.ACTION_CANCEL, 500f, 400f)
            settle(p)
            for (tab in listOf(EmojiTab.EMOTICONS, EmojiTab.SYMBOLS, EmojiTab.LETTERS)) {
                p.tab = tab
                val tag = tab.name.lowercase()
                shot("emoji-panel-$tag-$name", p)
                if (name == "light") continue
                // Every page of the dark panel, and each long one scrolled to its end.
                for (i in 1 until p.pages.size) {
                    tapAt(p, p.barCell(i).centerX().coerceIn(1f, 944f), bar)
                    settle(p)
                    shot("emoji-panel-$tag-page$i-$name", p)
                    if (EmojiGrid.rows(tab, p.pages[i]) > EmojiGrid.VISIBLE_ROWS) {
                        repeat(4) { drag(p, 100f, 700f, 100f, 150f) }
                        shot("emoji-panel-$tag-page$i-end-$name", p)
                    }
                }
            }
        }
        // Recents on each text tab.
        val p = panel()
        p.setRecents(EmojiTab.EMOTICONS, listOf("(T_T)", ":v", "ʕ•ᴥ•ʔ", "(╯°□°)╯︵ ┻━┻", "¯\\_(ツ)_/¯"))
        p.setRecents(EmojiTab.SYMBOLS, listOf("♡", "₫", "→", "✔"))
        p.setRecents(EmojiTab.LETTERS, listOf("𝓐", "𝕭", "ⓒ"))
        for (tab in listOf(EmojiTab.EMOTICONS, EmojiTab.SYMBOLS, EmojiTab.LETTERS)) {
            p.tab = tab
            shot("emoji-panel-${tab.name.lowercase()}-recents-dark", p)
        }
    }

    /** The text tabs list all they hold: Android's own fonts draw every character of it. */
    @Test
    fun theTextTabsListAllTheyHoldNothingShowsAsAnEmptyBox() {
        val p = panel()
        for (tab in listOf(EmojiTab.EMOTICONS, EmojiTab.SYMBOLS, EmojiTab.LETTERS)) {
            p.tab = tab
            assertEquals(tab.name, EmojiGrid.textPages(tab).map { it.items }, p.pages.drop(1).map { pg -> pg.cells.map { it.text } })
        }
    }

    /**
     * The text tabs draw as text, in the panel's font and the system's: nothing in them comes
     * out of the colour emoji font (on Android a bare ❤ or ☺ does, as its text glyph comes after
     * the emoji font).
     */
    @Test
    fun theTextTabsDrawAsTextNotAsColourEmoji() {
        val paint = android.graphics.Paint().apply {
            typeface = RuntimeEnvironment.getApplication().resources.getFont(R.font.misans_vf)
            textSize = 60f
        }
        val coloured = mutableListOf<String>()
        for (tab in listOf(EmojiTab.EMOTICONS, EmojiTab.SYMBOLS, EmojiTab.LETTERS)) {
            for (s in EmojiGrid.textPages(tab).flatMap { it.items }) {
                val glyphs = android.graphics.text.TextRunShaper.shapeTextRun(s, 0, s.length, 0, s.length, 0f, 0f, false, paint)
                if ((0 until glyphs.glyphCount()).any { "Emoji" in (glyphs.getFont(it).file?.name ?: "") }) coloured += s
            }
        }
        assertEquals(emptyList<String>(), coloured)
    }

    @Test
    fun eachTextTabHasItsOwnRecentsFirst() {
        val p = panel(listOf("🎂"))
        p.setRecents(EmojiTab.EMOTICONS, listOf("(T_T)", ":v"))
        p.tab = EmojiTab.EMOTICONS
        assertEquals(EmojiGrid.RECENTS, p.page)
        assertEquals(listOf("(T_T)", ":v"), p.pages[EmojiGrid.RECENTS].cells.map { it.text })
        // A tap types; the service puts it first, and the page holds still under the finger.
        tapAt(p, cell * 2 + 60, header + 60)
        assertEquals(listOf(":v"), typed)
        assertEquals(listOf(EmojiTab.EMOTICONS), typedOn)
        p.setRecents(EmojiTab.EMOTICONS, listOf(":v", "(T_T)"))
        assertEquals(listOf("(T_T)", ":v"), p.pages[EmojiGrid.RECENTS].cells.map { it.text })
        // Opened again, in the new order; the emoji's recents are their own.
        p.open()
        assertEquals(listOf(":v", "(T_T)"), p.pages[EmojiGrid.RECENTS].cells.map { it.text })
        p.tab = EmojiTab.EMOJI
        assertEquals(listOf("🎂"), p.pages[EmojiGrid.RECENTS].cells.map { it.text })
        // A tab without recents opens on its first category, its recents page empty.
        p.tab = EmojiTab.SYMBOLS
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(0, p.pages[EmojiGrid.RECENTS].cells.size)
        p.setRecents(EmojiTab.SYMBOLS, listOf("₫"))
        p.open()
        assertEquals(EmojiGrid.RECENTS, p.page)
        assertEquals("₫", p.pages[EmojiGrid.RECENTS].cells.single().text)

        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("tabs", Context.MODE_PRIVATE)
        val m = EmojiMemory(prefs)
        m.addRecent("😀")
        m.addRecent("(T_T)", EmojiTab.EMOTICONS)
        m.addRecent(":v", EmojiTab.EMOTICONS)
        m.addRecent("(T_T)", EmojiTab.EMOTICONS)
        m.addRecent("𝓐", EmojiTab.LETTERS)
        assertEquals(listOf("😀"), m.recents())
        assertEquals(listOf("(T_T)", ":v"), EmojiMemory(prefs).recents(EmojiTab.EMOTICONS))
        assertEquals(listOf("𝓐"), m.recents(EmojiTab.LETTERS))
        assertEquals(emptyList<String>(), m.recents(EmojiTab.SYMBOLS))
        // Six rows each: 24 kaomoji, 48 letters.
        repeat(40) { m.addRecent("k$it", EmojiTab.EMOTICONS); m.addRecent("l$it", EmojiTab.LETTERS) }
        assertEquals(24, m.recents(EmojiTab.EMOTICONS).size)
        assertEquals("k39", m.recents(EmojiTab.EMOTICONS).first())
        assertEquals(41, m.recents(EmojiTab.LETTERS).size)
        repeat(20) { m.addRecent("m$it", EmojiTab.LETTERS) }
        assertEquals(48, m.recents(EmojiTab.LETTERS).size)
        assertEquals(listOf("😀"), m.recents())
    }

    @Test
    fun theLettersBarScrollsSidewaysAndATapPicksTheLetterUnderTheFinger() {
        val p = panel()
        p.tab = EmojiTab.LETTERS
        assertEquals(28, p.pages.size)  // recents, A to Z, the digits
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(0f, p.barScrolled, 0f)
        assertTrue(p.barCell(p.pages.size - 1).right > 1080)  // it doesn't fit
        // Dragged, the bar follows the finger, and stays where it is let go after a pause; the
        // page stays and nothing is typed.
        touch(p, MotionEvent.ACTION_DOWN, 800f, bar)
        for (x in 780 downTo 200 step 20) { t += 16; touch(p, MotionEvent.ACTION_MOVE, x.toFloat(), bar) }
        val dragged = p.barScrolled
        assertTrue("$dragged", dragged >= 500 && dragged <= 600)  // less the touch slop
        t += 300; touch(p, MotionEvent.ACTION_MOVE, 200f, bar)
        t += 300; touch(p, MotionEvent.ACTION_UP, 200f, bar)
        settle(p)
        assertEquals(dragged, p.barScrolled, 0f)
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(emptyList<String>(), typed)
        // A tap picks the cell under the finger where the bar now is: F or G, no longer A.
        val under = ((150f + dragged) / p.barCell(1).width()).toInt()
        assertTrue("$under", under >= 6)
        tapAt(p, 150f, bar)
        assertEquals(under, p.page)
        assertEquals("${'A' + under - 1}", p.pages[p.page].name)
        tapAt(p, 10f, header + 10)
        assertEquals(p.pages[under].cells[0].text, typed.single())
        // Flung, it runs to its end; Backspace stays in its place.
        drag(p, 800f, bar, 300f, bar, steps = 4, ms = 8)
        val end = p.barCell(p.pages.size - 1)
        assertEquals(945f, end.right, 0.01f)
        tapAt(p, end.centerX(), bar)
        assertEquals("0–9", p.pages[p.page].name)
        tapAt(p, 1000f, bar)
        assertEquals(1, deleted)
        // Opened again at once, while the bar still glides to that cell, it starts at its
        // start; swiping the pages, the bar brings each page's cell into view.
        tapAt(p, 10f, bar)  // the cell half hidden at the left, which the bar glides to show
        assertEquals("R", p.pages[p.page].name)
        p.open()
        assertEquals(EmojiGrid.SMILEYS, p.page)
        assertEquals(0f, p.barScrolled, 0f)
        repeat(12) {
            drag(p, 900f, 400f, 200f, 400f)
            val c = p.barCell(p.page)
            assertTrue("${p.page}: ${c.left}..${c.right}", c.left >= 0 && c.right <= 945)
        }
        assertEquals("M", p.pages[p.page].name)
        assertTrue(p.barScrolled > 0)
        assertEquals(1, typed.size)
    }

    @Test
    fun onlyATapTypesOnTheTextTabs() {
        val p = panel()
        for (tab in listOf(EmojiTab.EMOTICONS, EmojiTab.SYMBOLS, EmojiTab.LETTERS)) {
            p.tab = tab
            typed.clear()
            val first = p.page
            drag(p, 900f, 400f, 200f, 400f)  // a swipe turns the page
            assertEquals(first + 1, p.page)
            drag(p, 200f, 400f, 900f, 400f)
            assertEquals(first, p.page)
            // A long page scrolls, flings, and a tap on it while it moves only stops it.
            val long = (1 until p.pages.size).first { EmojiGrid.rows(tab, p.pages[it]) > 6 }
            repeat(10) { if (p.barCell(long).right > 945f) drag(p, 900f, bar, 500f, bar) }
            assertTrue(tab.name, p.barCell(long).right <= 945f)
            tapAt(p, p.barCell(long).centerX(), bar)
            assertEquals(long, p.page)
            assertEquals(tab.name, emptyList<String>(), typed)
            drag(p, 100f, 700f, 100f, 150f, steps = 5, ms = 10, settle = false)
            assertTrue(p.gridScroll > 0)
            tapAt(p, 10f, header + 10)
            assertEquals(tab.name, emptyList<String>(), typed)
            settle(p)
            tapAt(p, 10f, header + 10)
            assertEquals(tab.name, 1, typed.size)
            assertEquals(tab, typedOn.last())
        }
    }

    @Test
    fun recentsComeFirstWithoutRepeatsAndTonesAreKept() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("t", Context.MODE_PRIVATE)
        val m = EmojiMemory(prefs)
        m.addRecent("😀"); m.addRecent("🎂"); m.addRecent("😀")
        assertEquals(listOf("😀", "🎂"), m.recents())
        repeat(60) { m.addRecent("x$it") }
        assertEquals(EmojiMemory.RECENTS, m.recents().size)
        m.setTone("👍", "👍🏽")
        m.setTone("👋", "👋🏿")
        assertEquals(mapOf("👍" to "👍🏽", "👋" to "👋🏿"), EmojiMemory(prefs).tones())
        assertTrue(EmojiMemory(prefs).recents().first() == "x59")
    }
}
