package com.xern.xrnkey.ime

import android.content.SharedPreferences
import com.xern.xrnkey.keyboard.EmojiGrid
import com.xern.xrnkey.keyboard.EmojiTab

/**
 * What each tab of the emoji panel typed lately (newest first, a list for each tab) and the skin
 * tone chosen for each emoji, kept on the phone.
 */
class EmojiMemory(private val prefs: SharedPreferences) {
    fun recents(tab: EmojiTab = EmojiTab.EMOJI): List<String> =
        prefs.getString(key(tab), "")!!.split(SEP).filter { it.isNotEmpty() }

    fun addRecent(e: String, tab: EmojiTab = EmojiTab.EMOJI) {
        val list = (listOf(e) + recents(tab).filter { it != e }).take(cap(tab))
        prefs.edit().putString(key(tab), list.joinToString(SEP)).apply()
    }

    fun tones(): Map<String, String> =
        prefs.getString("tones", "")!!.split(SEP).mapNotNull {
            val p = it.split(PAIR)
            if (p.size == 2) p[0] to p[1] else null
        }.toMap()

    fun setTone(base: String, toned: String) {
        val t = tones() + (base to toned)
        prefs.edit().putString("tones", t.entries.joinToString(SEP) { "${it.key}$PAIR${it.value}" }).apply()
    }

    companion object {
        private const val SEP = "\u001F"
        private const val PAIR = "\u001E"
        /** How many emoji the emoji tab's recents keep. */
        const val RECENTS = 48

        /** How many a tab's recents keep: six rows (48 emoji, symbols or letters; 24 kaomoji). */
        fun cap(tab: EmojiTab) = 6 * EmojiGrid.columns(tab)

        /** The emoji keep the key they always had. */
        private fun key(tab: EmojiTab) = when (tab) {
            EmojiTab.EMOJI -> "recents"
            EmojiTab.EMOTICONS -> "recents-kaomoji"
            EmojiTab.SYMBOLS -> "recents-symbols"
            EmojiTab.LETTERS -> "recents-letters"
        }
    }
}
