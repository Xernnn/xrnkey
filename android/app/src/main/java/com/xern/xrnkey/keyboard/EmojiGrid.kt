package com.xern.xrnkey.keyboard

import com.xern.xrnkey.engine.Emoji

/** The emoji panel's tabs: no fancy text, no stickers (android/docs/feature-spec.md). */
enum class EmojiTab { EMOJI, EMOTICONS, SYMBOLS }

/** One cell of the grid: what it types, and whether holding it offers skin tones. */
data class Cell(val text: String, val skinTones: Boolean = false)

/** The line icons of the emoji tab's category bar. */
enum class PageIcon { RECENTS, SMILEYS, FOOD, ACTIVITY, TRAVEL, ANIMALS, OBJECTS }

/**
 * One page of a tab: its cells in order, and what its cell on the category bar shows: a line
 * icon on the emoji tab, a sample of the page on the others.
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

    /** The emoji tab's recents page, and the page it opens on when there are no recents. */
    const val RECENTS = 0
    const val SMILEYS = 1

    /** How many rows of the grid show at once, on every tab. */
    const val VISIBLE_ROWS = 4

    /** How many cells a row holds on each tab: kaomoji are wide. */
    fun columns(tab: EmojiTab) = if (tab == EmojiTab.EMOTICONS) 4 else 8

    /**
     * The pages of a tab. On the emoji tab the recents page is always there (empty when there
     * are none), so the bar's cells keep their places. `tones` gives the skin tone chosen for
     * an emoji, shown in its place.
     */
    fun pages(
        tab: EmojiTab,
        emoji: List<Emoji>,
        recents: List<String>,
        tones: Map<String, String> = emptyMap(),
    ): List<GridPage> = when (tab) {
        EmojiTab.EMOTICONS -> Emoticons.pages.map { (sample, list) -> GridPage(sample, list.map { Cell(it) }, sample = sample) }
        EmojiTab.SYMBOLS -> Symbols.pages.map { (sample, list) -> GridPage(sample, list.map { Cell(it) }, sample = sample) }
        EmojiTab.EMOJI -> {
            val byGroup = emoji.groupBy { it.group }
            categories.mapIndexed { i, c ->
                val cells = if (i == RECENTS) recents.map { Cell(it) }
                else c.groups.flatMap { g -> byGroup[g].orEmpty() }.map { Cell(tones[it.text] ?: it.text, it.skinTones) }
                GridPage(c.name, cells, icon = c.icon)
            }
        }
    }

    /** The page a tab opens on: the emoji tab on its recents, or on smileys when there are none. */
    fun firstPage(tab: EmojiTab, recents: List<String>) =
        if (tab == EmojiTab.EMOJI && recents.isEmpty()) SMILEYS else 0

    /** How many rows a page's cells take on a tab. */
    fun rows(tab: EmojiTab, page: GridPage) = (page.cells.size + columns(tab) - 1) / columns(tab)
}

/**
 * Text emoticons and kaomoji, as typed in chat, in pages named by a face from each. Written for
 * this project.
 */
object Emoticons {
    val pages = listOf(
        ":)" to listOf(
            ":)", ":))", "=))", ":D", ":v", ";)", ":(", ":((", ":'(", ":P", ":O", ":3",
            "<3", "^^", "^_^", "^.^", "-_-", "T_T", ">_<", "o.O", "xD", ":-)", ":-(", ";-)",
        ),
        "(^_^)" to listOf(
            "(^_^)", "(^o^)", "(◕‿◕)", "(•‿•)", "(✿◠‿◠)", "(●'◡'●)", "( ´ ▽ ` )", "(〃▽〃)",
            "(￣▽￣)", "(*^▽^*)", "(^人^)", "(｡•̀ᴗ-)✧", "(｡♥‿♥｡)", "( ˘ ³˘)♥", "(づ｡◕‿‿◕｡)づ",
            "(ノ◕ヮ◕)ノ*:･ﾟ✧", "(=^･ω･^=)", "(・ω・)", "ʕ•ᴥ•ʔ", "(っ˘ω˘ς)",
        ),
        "(T_T)" to listOf(
            "(T_T)", "(>_<)", "(╥﹏╥)", "( ˘︹˘ )", "(︶︹︺)", "(；一_一)", "(-_-;)", "(ಠ_ಠ)",
            "(¬‿¬)", "(⌐■_■)", "(°ロ°)", "ヽ(°〇°)ﾉ", "(ง •̀_•́)ง", "¯\\_(ツ)_/¯",
            "(╯°□°）╯︵ ┻━┻", "┬─┬ノ( º _ ºノ)",
        ),
    )
    val list = pages.flatMap { it.second }
}

/**
 * Symbols that the letter and symbol pages leave out, in pages by kind, each named by one of
 * its symbols. Written for this project.
 */
object Symbols {
    val pages = listOf(
        "→" to listOf("←", "→", "↑", "↓", "↔", "↕", "⇐", "⇒", "⇔", "↩", "↪", "➜"),
        "±" to listOf(
            "±", "×", "÷", "≈", "≠", "≤", "≥", "∞", "√", "∑", "∏", "∫", "∂", "∆", "π", "µ", "°", "‰",
            "¼", "½", "¾", "¹", "²", "³", "ⁿ",
        ),
        "₫" to listOf("₫", "$", "€", "£", "¥", "₩", "₹", "₽", "฿", "¢"),
        "©" to listOf(
            "©", "®", "™", "§", "¶", "†", "‡", "•", "·", "…", "—", "–", "«", "»", "‹", "›", "“", "”",
            "‘", "’", "¿", "¡", "※", "#", "&", "@",
        ),
        "★" to listOf(
            "★", "☆", "✦", "✧", "♥", "♡", "♦", "♣", "♠", "●", "○", "◆", "◇", "■", "□", "▲", "△",
            "▼", "▽", "◀", "▶", "✓", "✔", "✗", "✘", "☐", "☑", "☒",
        ),
        "♪" to listOf("☀", "☁", "☂", "☃", "☎", "✉", "✂", "✎", "♪", "♫", "☕", "⚡", "☯", "☮", "♻", "⚠"),
    )
    val list = pages.flatMap { it.second }
}
