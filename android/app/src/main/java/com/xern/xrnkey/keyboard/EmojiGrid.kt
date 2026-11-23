package com.xern.xrnkey.keyboard

import com.xern.xrnkey.engine.Emoji

/**
 * The emoji panel's tabs: emoji, kaomoji, symbols and fancy letters; no stickers
 * (android/docs/feature-spec.md).
 */
enum class EmojiTab { EMOJI, EMOTICONS, SYMBOLS, LETTERS }

/** One cell of the grid: what it types, and whether holding it offers skin tones. */
data class Cell(val text: String, val skinTones: Boolean = false)

/** The line icons of the emoji tab's category bar. */
enum class PageIcon { RECENTS, SMILEYS, FOOD, ACTIVITY, TRAVEL, ANIMALS, OBJECTS }

/**
 * One page of a tab: its cells in order, and what its cell on the category bar shows: a line
 * icon on the emoji tab and on every tab's recents, a sample of the page on the others.
 */
data class GridPage(val name: String, val cells: List<Cell>, val icon: PageIcon? = null, val sample: String = "")

/**
 * What the emoji panel's pages hold. Each category is a page of its own that scrolls only
 * within itself; a sideways swipe or the category bar moves between them. Pure Kotlin, so the
 * tests check it without a phone.
 */
object EmojiGrid {
    /** An emoji tab category: its name, its icon and the Unicode groups it holds. */
    data class Category(val name: String, val icon: PageIcon, val groups: List<String>)

    /** The emoji tab's pages in the bar's order; Unicode's nine groups fold into the last six. */
    val categories = listOf(
        Category("Recents", PageIcon.RECENTS, emptyList()),
        Category("Smileys & people", PageIcon.SMILEYS, listOf("Smileys & Emotion", "People & Body")),
        Category("Food & drink", PageIcon.FOOD, listOf("Food & Drink")),
        Category("Activity", PageIcon.ACTIVITY, listOf("Activities")),
        Category("Travel & places", PageIcon.TRAVEL, listOf("Travel & Places")),
        Category("Animals & nature", PageIcon.ANIMALS, listOf("Animals & Nature")),
        Category("Objects", PageIcon.OBJECTS, listOf("Objects", "Symbols", "Flags")),
    )

    /**
     * Every tab's recents page, and the page a tab opens on when there are no recents: its first
     * category (on the emoji tab, smileys and people).
     */
    const val RECENTS = 0
    const val SMILEYS = 1

    /** How many rows of the grid show at once, on every tab. */
    const val VISIBLE_ROWS = 4

    /** How many cells a row holds on each tab: kaomoji are wide. */
    fun columns(tab: EmojiTab) = if (tab == EmojiTab.EMOTICONS) 4 else 8

    /** The pages a text tab holds after its recents. */
    fun textPages(tab: EmojiTab): List<TextPage> = when (tab) {
        EmojiTab.EMOJI -> emptyList()
        EmojiTab.EMOTICONS -> Emoticons.pages
        EmojiTab.SYMBOLS -> Symbols.pages
        EmojiTab.LETTERS -> FancyLetters.pages
    }

    /**
     * The pages of a tab: its recents page, always there (empty when there are none) so the bar's
     * cells keep their places, then its categories. `tones` gives the skin tone chosen for an
     * emoji, shown in its place; `text` is what a text tab lists (the panel leaves out what the
     * phone's fonts can't draw).
     */
    fun pages(
        tab: EmojiTab,
        emoji: List<Emoji>,
        recents: List<String>,
        tones: Map<String, String> = emptyMap(),
        text: List<TextPage> = textPages(tab),
    ): List<GridPage> = when (tab) {
        EmojiTab.EMOJI -> {
            val byGroup = emoji.groupBy { it.group }
            categories.mapIndexed { i, c ->
                val cells = if (i == RECENTS) recents.map { Cell(it) }
                else c.groups.flatMap { g -> byGroup[g].orEmpty() }.map { Cell(tones[it.text] ?: it.text, it.skinTones) }
                GridPage(c.name, cells, icon = c.icon)
            }
        }
        else -> listOf(GridPage("Recents", recents.map { Cell(it) }, icon = PageIcon.RECENTS)) +
            text.map { GridPage(it.name, it.items.map { s -> Cell(s) }, sample = it.sample) }
    }

    /** The page every tab opens on: its recents, or its first category when there are none. */
    fun firstPage(recents: List<String>) = if (recents.isEmpty()) SMILEYS else RECENTS

    /** How many rows a page's cells take on a tab. */
    fun rows(tab: EmojiTab, page: GridPage) = (page.cells.size + columns(tab) - 1) / columns(tab)
}
