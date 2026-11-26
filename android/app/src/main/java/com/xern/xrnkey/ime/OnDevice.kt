package com.xern.xrnkey.ime

import android.content.SharedPreferences
import com.xern.xrnkey.engine.PersonalWords
import com.xern.xrnkey.engine.Shortcuts
import com.xern.xrnkey.ja.UserDictionary
import com.xern.xrnkey.ja.UserHistory
import com.xern.xrnkey.sync.WordsProvider
import com.xern.xrnkey.sync.WordsTable
import java.io.File
import java.lang.ref.WeakReference

/**
 * What the keyboard keeps about its owner, on the phone only: the personal words it learned, the
 * suggestions the owner hid, the shortcuts table, what was chosen on the Japanese keyboard
 * ([japanese]) and the owner's own Japanese words ([japaneseWords]). All are files in credential-encrypted storage ([dir]), so they exist only
 * after the first unlock and survive restarts and app updates. The settings screen changes them
 * through the same files; [refresh] picks the changes up when a field starts. The hub app's sync
 * (sync/WordsProvider.kt) reads and changes the personal words' file too, through [live] on the main
 * thread, where the keyboard uses them.
 */
class OnDevice(private val dir: File, private val prefs: SharedPreferences) : AutoCloseable {
    init {
        current = WeakReference(this)
    }

    val wordsFile get() = File(dir, WORDS)
    val shortcutsFile get() = File(dir, SHORTCUTS)
    val hiddenFile get() = File(dir, HIDDEN)
    val japaneseFile get() = File(dir, JAPANESE)
    val spotsFile get() = File(dir, SPOTS)
    val japaneseWordsFile get() = File(dir, JAPANESE_WORDS)

    var words: PersonalWords? = null
        private set
    var shortcuts: Shortcuts? = null
        private set
    var hidden: HiddenWords? = null
        private set

    /** Where the owner's taps land on each letter: learned, kept and cleared with the personal words. */
    var spots: TouchSpots? = null
        private set

    /** The words chosen on the Japanese keyboard, each for its reading and after the one before. */
    var japanese: UserHistory? = null
        private set

    /** The owner's own Japanese words, each with its reading (the settings add and remove them). */
    var japaneseWords: UserDictionary? = null
        private set

    private var wordsCleared = -1L
    private var shortcutsStamp = -1L
    private var hiddenStamp = -1L
    private var japaneseCleared = -1L
    private var japaneseWordsStamp = -1L

    /** Loads what isn't loaded yet, or changed since. Old habits fade once a month. */
    fun refresh(now: Long = System.currentTimeMillis()) {
        val cleared = prefs.getLong(KEY_CLEARED, 0L)
        if (words == null || cleared != wordsCleared) {
            words?.close()
            words = PersonalWords().also { w ->
                if (wordsFile.exists()) w.load(wordsFile)
                val last = prefs.getLong(KEY_DECAYED, 0L)
                if (last == 0L) {
                    prefs.edit().putLong(KEY_DECAYED, now).apply()
                } else if (now - last > MONTH) {
                    val before = keys(wordsFile)
                    w.decay(DECAY)
                    w.save(wordsFile)
                    noteFaded(dir, before, keys(wordsFile))
                    prefs.edit().putLong(KEY_DECAYED, now).apply()
                }
            }
            spots = TouchSpots().also { it.load(spotsFile) }
            wordsCleared = cleared
        }
        val stamp = if (shortcutsFile.exists()) shortcutsFile.lastModified() else 0L
        if (shortcuts == null || stamp != shortcutsStamp) {
            shortcuts?.close()
            shortcuts = Shortcuts().also { if (shortcutsFile.exists()) it.load(shortcutsFile) }
            shortcutsStamp = stamp
        }
        // The keyboard hides suggestions, the settings show them again: whichever wrote last.
        val hiddenNow = if (hiddenFile.exists()) hiddenFile.lastModified() else 0L
        if (hidden == null || hiddenNow != hiddenStamp) {
            hidden = HiddenWords(hiddenFile)
            hiddenStamp = hiddenNow
        }
        val jaCleared = japaneseStamp(prefs)
        if (japanese == null || jaCleared != japaneseCleared) {
            japanese = UserHistory().also { it.load(japaneseFile) }
            japaneseCleared = jaCleared
        }
        val wordsStamp = japaneseWordsStamp(prefs)
        if (japaneseWords == null || wordsStamp != japaneseWordsStamp) {
            japaneseWords = UserDictionary().also { it.load(japaneseWordsFile) }
            japaneseWordsStamp = wordsStamp
        }
    }

    /** Forgets a word by hand (the strip's remove mark): a deletion to sync, even if it once faded. */
    fun forget(word: String) {
        words?.forget(word)
        forgotByHand(dir, word)
    }

    /** Writes the personal words and the Japanese choices, if any were learned since the last save. */
    fun save() {
        // Cleared in the settings since these were loaded: what is held is what was cleared.
        val j = japanese
        if (j != null && j.unsaved > 0 && japaneseStamp(prefs) == japaneseCleared) j.save(japaneseFile)
        val w = words ?: return
        if (prefs.getLong(KEY_CLEARED, 0L) != wordsCleared) return
        if (w.unsaved > 0) w.save(wordsFile)
        spots?.takeIf { it.unsaved > 0 }?.save(spotsFile)
    }

    /**
     * The personal words' file was changed under the keyboard (the hub's sync, after [save]): read
     * it again into the same table, which the typist and the models hold on to, and take the
     * settings' stamp as read. Main thread only, as everything that touches [words].
     */
    fun reloadWords() {
        val w = words ?: return
        if (wordsFile.exists()) w.load(wordsFile)
        wordsCleared = prefs.getLong(KEY_CLEARED, 0L)
    }

    override fun close() {
        if (current?.get() === this) current = null
        save()
        words?.close()
        shortcuts?.close()
        words = null
        spots = null
        shortcuts = null
        japanese = null
        japaneseWords = null
    }

    companion object {
        @Volatile private var current: WeakReference<OnDevice>? = null

        /** The keyboard's own, while it is up: what it learned since its last save is in there. */
        fun live(): OnDevice? = current?.get()

        const val WORDS = "personal-words.tsv"
        /** The keys the monthly fade dropped, one a line: the hub app's sync doesn't send them as deletions. */
        const val FADED = "faded-words.txt"
        private const val MAX_FADED = 100_000
        /** The laptop's macros.txt format, so one file can serve both (feature-spec, "Shortcuts"). */
        const val SHORTCUTS = "shortcuts.txt"
        const val HIDDEN = "hidden-suggestions.txt"
        const val JAPANESE = "japanese-history.tsv"
        const val SPOTS = "touch-spots.tsv"
        const val JAPANESE_WORDS = "japanese-words.tsv"
        private const val KEY_JA_WORDS = "japanese-words-changed"
        private const val KEY_JA_CLEARED = "japanese-cleared"
        private const val KEY_CLEARED = "words-cleared"
        private const val KEY_CLEAR_ALL = "words-clear-all"
        private const val KEY_DECAYED = "words-decayed"
        private const val MONTH = 30L * 24 * 3600 * 1000
        private const val DECAY = 0.9

        /** Forgets every personal word and where the taps land, and shows every hidden suggestion again (from the settings screen). */
        fun clearWords(dir: File, prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
            File(dir, WORDS).delete()
            File(dir, HIDDEN).delete()
            File(dir, SPOTS).delete()
            File(dir, FADED).delete()
            prefs.edit().putLong(KEY_CLEAR_ALL, maxOf(now, clearedAll(prefs) + 1)).commit()
            wordsChanged(prefs, now)
        }

        private fun keys(file: File): Set<String> =
            if (file.isFile) WordsTable.parse(file.readText()).keys else emptySet()

        /**
         * The keys the fade dropped from the table, kept so the hub app's sync drops them on the phone
         * only: on the laptop, and on the server, a word typed there stays. The list keeps the newest
         * [MAX_FADED], and loses a key that is in the table again.
         */
        private fun noteFaded(dir: File, before: Set<String>, after: Set<String>) {
            val dropped = before - after
            val old = faded(dir)
            if (dropped.isEmpty() && old.none { it in after }) return
            val all = LinkedHashSet<String>()
            old.forEach { if (it !in after && it !in dropped) all += it }
            all += dropped
            WordsProvider.writeWhole(File(dir, FADED), all.toList().takeLast(MAX_FADED).joinToString("") { "$it\n" })
        }

        /** The keys the fade dropped (see [noteFaded]), oldest first. */
        fun faded(dir: File): List<String> {
            val file = File(dir, FADED)
            if (!file.isFile) return emptyList()
            return file.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        /**
         * A word deleted by hand (the settings' list, the strip's remove mark) leaves the faded list,
         * with its pairs, so the sync sends it as the deletion it is.
         */
        fun forgotByHand(dir: File, word: String) {
            val old = faded(dir)
            val kept = old.filter { key -> key.split(' ').none { it == word } }
            if (kept.size != old.size) WordsProvider.writeWhole(File(dir, FADED), kept.joinToString("") { "$it\n" })
        }

        /**
         * When Clear all was last used, 0 if never: the hub app's sync sends an emptied table as
         * deletions only after it, and otherwise takes it as lost (a power cut), deleting nothing.
         */
        fun clearedAll(prefs: SharedPreferences) = prefs.getLong(KEY_CLEAR_ALL, 0L)

        /**
         * The settings screen changed the personal words' file (a word deleted): the keyboard
         * reads it again when the next field starts, and drops what it held.
         */
        fun wordsChanged(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
            prefs.edit().putLong(KEY_CLEARED, maxOf(now, prefs.getLong(KEY_CLEARED, 0L) + 1)).commit()
        }

        /** Moves on whenever the personal words' file is changed under the keyboard (see [wordsChanged]). */
        fun wordsStamp(prefs: SharedPreferences) = prefs.getLong(KEY_CLEARED, 0L)

        /**
         * Forgets what was chosen on the Japanese keyboard (from the settings screen); or, with a
         * changed file (a word deleted), makes the keyboard read it again at its next field.
         */
        fun clearJapanese(dir: File, prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
            File(dir, JAPANESE).delete()
            japaneseChanged(prefs, now)
        }

        fun japaneseChanged(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
            prefs.edit().putLong(KEY_JA_CLEARED, maxOf(now, japaneseStamp(prefs) + 1)).commit()
        }

        private fun japaneseStamp(prefs: SharedPreferences) = prefs.getLong(KEY_JA_CLEARED, 0L)

        /** The settings changed the owner's Japanese words: the keyboard reads them again at its next field. */
        fun japaneseWordsChanged(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
            prefs.edit().putLong(KEY_JA_WORDS, maxOf(now, japaneseWordsStamp(prefs) + 1)).commit()
        }

        private fun japaneseWordsStamp(prefs: SharedPreferences) = prefs.getLong(KEY_JA_WORDS, 0L)

        /** The clipboard history was cleared in the settings: the keyboard reads it again. */
        fun clipsChanged(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
            prefs.edit().putLong(KEY_CLIPS, maxOf(now, prefs.getLong(KEY_CLIPS, 0L) + 1)).commit()
        }

        fun clipsStamp(prefs: SharedPreferences) = prefs.getLong(KEY_CLIPS, 0L)
        private const val KEY_CLIPS = "clips-changed"
    }
}
