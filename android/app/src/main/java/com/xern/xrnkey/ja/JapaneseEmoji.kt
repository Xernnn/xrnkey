package com.xern.xrnkey.ja

/**
 * The emoji the Japanese keyboard's strip offers for a reading being composed, from
 * data/emoji/keywords-ja.tsv (CLDR's Japanese names and keywords, and this project's hand list,
 * kaomoji among them): ねこ (猫) 🐱, わらい (笑い) 😂, ありがとう 🙏, かお (^_^). No Android types
 * here, so the JVM tests run it as the keyboard does.
 */
class JapaneseEmoji private constructor(private val table: Map<String, Entry>) {
    /**
     * A keyword's emoji, best first, and whether the first may take a slot of the strip: the hand
     * list's, and CLDR's where the emoji is named so (すし 🍣); not CLDR's other keywords, which
     * are associations (本 📕), whose emoji wait in the panel.
     */
    class Entry(val emoji: List<String>, val strong: Boolean)

    val size: Int get() = table.size

    /**
     * The emoji for a reading whose best conversion is `best`: the conversion's keyword first
     * (あめ is 雨 ☔ before the reading's 🍬), then the reading's own, at most [MOST]. Null when
     * neither is a keyword.
     */
    fun of(reading: String, best: String): Entry? {
        val hits = listOf(best, reading).distinct().mapNotNull { table[it] }
        if (hits.isEmpty()) return null
        return Entry(hits.flatMap { it.emoji }.distinct().take(MOST), hits.first().strong)
    }

    companion object {
        const val MOST = 5

        /** The table from keywords-ja.tsv's text: keyword, emoji ..., and hand, name or cldr. */
        fun load(bytes: ByteArray): JapaneseEmoji {
            val table = HashMap<String, Entry>()
            for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val f = line.split('\t')
                if (f.size < 3) continue
                val source = f.last()
                table[f[0]] = Entry(f.subList(1, f.size - 1).take(MOST), source == "hand" || source == "name")
            }
            return JapaneseEmoji(table)
        }
    }
}
