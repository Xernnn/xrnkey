package com.xern.xrnkey

import android.content.Context
import android.content.SharedPreferences

/**
 * The keyboard's settings, with the owner's choices as the defaults. They live in the
 * keyboard's device-protected preferences, which the keyboard can read before the phone's
 * first unlock; the settings screen writes them, and the keyboard reads them when a field
 * starts.
 */
data class Options(
    val vni: Boolean = false,
    val modernTones: Boolean = false,
    /** Telex: tone and mark keys may come after the final consonant (toans). */
    val freeMarking: Boolean = true,
    /** Telex: w alone is ư. Off, as in Laban Key's simple Telex: a w with no vowel to mark stays w ("wá"); uw, ow, aw still mark. */
    val wShortcut: Boolean = false,
    val underline: Boolean = false,
    val suggestions: Boolean = true,
    val emojiSuggestions: Boolean = true,
    /**
     * What Space does to a word typed without marks (Typist.TONES_*): off by default, as Laban
     * Key does. A new setting, so every install starts off (0.3 restored every word).
     */
    val tonesOnSpace: Int = 0,
    /** Shift for a sentence's first letter. */
    val autoCap: Boolean = false,
    /** The next word in the strip after a Space. */
    val nextWord: Boolean = true,
    val learn: Boolean = true,
    /** 0 off, 1 flexible, 2 strong (Models.CORRECT_*). */
    val correction: Int = 1,
    val keepForeign: Boolean = true,
    val doubleSpace: Boolean = true,
    /** "auto" (shown in portrait), "on" or "off". */
    val numberRow: String = "auto",
    val holdDigits: Boolean = true,
    val keyPopup: Boolean = true,
    val holdMillis: Int = 400,
    val spaceSwipe: Boolean = true,
    /** Holding the comma switches between Vietnamese and English, as on Laban Key. */
    val commaHold: Boolean = true,
    /** Keyboard height, in percent. */
    val height: Int = 100,
    val clipboard: Boolean = true,
    val shortcuts: Boolean = true,
    /** "dark", "light" or "system". */
    val theme: String = "dark",
    /** The tool row is folded away (its ≡): the bare strip, ≡ and emoji, shows instead. */
    val toolsHidden: Boolean = false,
    /**
     * The keyboard's language, chosen from the comma's menu: "vi" (Telex or VNI), "en" (no
     * marks, English words only).
     */
    val language: String = "vi",
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putBoolean("vni", vni)
            .putBoolean("modern_tones", modernTones)
            .putBoolean("free_marking", freeMarking)
            .putBoolean("w_shortcut", wShortcut)
            .putBoolean("next_word", nextWord)
            .putBoolean("underline", underline)
            .putBoolean("suggestions", suggestions)
            .putBoolean("emoji_suggestions", emojiSuggestions)
            .putInt("tones_on_space", tonesOnSpace)
            .putBoolean("auto_cap", autoCap)
            .putBoolean("learn", learn)
            .putInt("correction", correction)
            .putBoolean("keep_foreign", keepForeign)
            .putBoolean("double_space", doubleSpace)
            .putString("number_row", numberRow)
            .putBoolean("hold_digits", holdDigits)
            .putBoolean("key_popup", keyPopup)
            .putInt("hold_ms", holdMillis)
            .putBoolean("space_swipe", spaceSwipe)
            .putBoolean("comma_hold", commaHold)
            .putInt("height", height)
            .putBoolean("clipboard", clipboard)
            .putBoolean("shortcuts", shortcuts)
            .putString("palette", theme)
            .putBoolean("tools_hidden", toolsHidden)
            .putString("language", language)
            .apply()
    }

    companion object {
        val HOLD_CHOICES = listOf(200, 300, 400, 500, 600)

        /** The keyboard's languages, as the comma's menu offers them. */
        val LANGUAGES = listOf("vi", "en")
        val HEIGHT_CHOICES = listOf(80, 90, 100, 110, 120)

        fun prefs(context: Context): SharedPreferences =
            context.createDeviceProtectedStorageContext().getSharedPreferences("keyboard", Context.MODE_PRIVATE)

        fun load(p: SharedPreferences): Options {
            val d = Options()
            return Options(
                vni = p.getBoolean("vni", d.vni),
                modernTones = p.getBoolean("modern_tones", d.modernTones),
                freeMarking = p.getBoolean("free_marking", d.freeMarking),
                wShortcut = p.getBoolean("w_shortcut", d.wShortcut),
                nextWord = p.getBoolean("next_word", d.nextWord),
                underline = p.getBoolean("underline", d.underline),
                suggestions = p.getBoolean("suggestions", d.suggestions),
                emojiSuggestions = p.getBoolean("emoji_suggestions", d.emojiSuggestions),
                tonesOnSpace = p.getInt("tones_on_space", d.tonesOnSpace).coerceIn(0, 2),
                autoCap = p.getBoolean("auto_cap", d.autoCap),
                learn = p.getBoolean("learn", d.learn),
                correction = p.getInt("correction", d.correction).coerceIn(0, 2),
                keepForeign = p.getBoolean("keep_foreign", d.keepForeign),
                doubleSpace = p.getBoolean("double_space", d.doubleSpace),
                numberRow = p.getString("number_row", d.numberRow)!!.takeIf { it in listOf("auto", "on", "off") } ?: d.numberRow,
                holdDigits = p.getBoolean("hold_digits", d.holdDigits),
                keyPopup = p.getBoolean("key_popup", d.keyPopup),
                holdMillis = p.getInt("hold_ms", d.holdMillis).coerceIn(150, 1000),
                spaceSwipe = p.getBoolean("space_swipe", d.spaceSwipe),
                commaHold = p.getBoolean("comma_hold", d.commaHold),
                height = p.getInt("height", d.height).coerceIn(70, 130),
                clipboard = p.getBoolean("clipboard", d.clipboard),
                shortcuts = p.getBoolean("shortcuts", d.shortcuts),
                theme = p.getString("palette", d.theme)!!.takeIf { it in listOf("dark", "light", "system") } ?: d.theme,
                toolsHidden = p.getBoolean("tools_hidden", d.toolsHidden),
                // 0.7.0 kept only whether it was English.
                language = p.getString("language", null)?.takeIf { it in LANGUAGES }
                    ?: if (p.getBoolean("english", false)) "en" else d.language,
            )
        }
    }
}
