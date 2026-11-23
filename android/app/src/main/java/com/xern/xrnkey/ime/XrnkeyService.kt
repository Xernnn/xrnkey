package com.xern.xrnkey.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.InputType
import android.text.SpannableString
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import com.xern.xrnkey.Options
import com.xern.xrnkey.SettingsActivity
import com.xern.xrnkey.clip.ClipHistory
import com.xern.xrnkey.engine.EmojiIndex
import com.xern.xrnkey.engine.Model
import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.keyboard.ClipboardPanelView
import com.xern.xrnkey.keyboard.EmojiPanelView
import com.xern.xrnkey.keyboard.EnterAction
import com.xern.xrnkey.keyboard.KeyboardFrame
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Layouts
import com.xern.xrnkey.keyboard.Page
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.keyboard.Shift
import com.xern.xrnkey.keyboard.Strip
import com.xern.xrnkey.keyboard.Tool
import java.io.File
import java.io.IOException

/**
 * The keyboard. The models are memory-mapped straight from the APK (uncompressed assets), so
 * they cost no copy and only the pages in use take RAM. Nothing needs a permission.
 *
 * The input view is a frame: the keys, and the panels that take their place (emoji, clipboard). A panel
 * hides the keys but keeps their height, so the field above doesn't jump.
 */
class XrnkeyService :
    InputMethodService(),
    KeyboardView.Listener,
    EmojiPanelView.Listener,
    ClipboardPanelView.Listener {
    private var vi: Model? = null
    private var en: Model? = null
    private var models: Models? = null
    private var emoji: EmojiIndex? = null
    private var state: TypingState? = null
    /** Internal: the tests read its context. */
    internal lateinit var typist: Typist
        private set
    private var options = Options()
    private lateinit var strip: StripMaker
    private var keyboard: KeyboardView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var clipPanel: ClipboardPanelView? = null
    private var inputFrame: KeyboardFrame? = null
    private var clips: ClipHistory? = null
    private var onDevice: OnDevice? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onCopied() }

    /** Where the word in progress starts in the field, as the editor last said (-1: not known). */
    private var composingStart = -1

    /**
     * Where this keyboard's own edits put the cursor, oldest first, until the editor reports
     * them. Any other selection update means the field changed under the typist (an app clears
     * its box after Send, select-all and delete, a tap elsewhere): it reads the field again.
     */
    private val expected = ArrayDeque<Int>()

    /** The emoji search's query, while the keys type into it rather than the field. */
    private var searchQuery: String? = null

    private val editor = object : Editor {
        override fun setComposing(text: String) {
            // A Spannable with no spans of its own gets no underline from the editor's theme.
            currentInputConnection?.setComposingText(if (options.underline) text else SpannableString(text), 1)
        }

        override fun commit(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun finishComposing() {
            currentInputConnection?.finishComposingText()
        }

        override fun backspace() = sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)

        override fun deleteBefore(codePoints: Int) {
            currentInputConnection?.deleteSurroundingTextInCodePoints(codePoints, 0)
        }

        override fun textBefore(n: Int): String =
            currentInputConnection?.getTextBeforeCursor(n, 0)?.toString() ?: ""

        override fun enter() {
            val info = currentInputEditorInfo
            val action = info?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION } ?: EditorInfo.IME_ACTION_NONE
            val noAction = info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
                action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
            if (noAction) sendKeyChar('\n') else currentInputConnection?.performEditorAction(action)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val loaded = ArrayList<String>()
        vi = openModel("vi.xklm", loaded)
        en = openModel("en.xklm", loaded)
        // One line, so a model that didn't load shows in logcat (the English one once didn't, and
        // nothing said so: "teh" just stayed "teh").
        Log.i("xrnkey", "models: " + loaded.joinToString("; "))
        models = Models(vi, en)
        models?.correction = Models.CORRECT_FLEXIBLE
        emoji = try {
            // The strip's keyword table goes with the index; without it the strip offers no emoji.
            val keywords = try {
                assets.open("emoji-keywords.tsv").use { it.readBytes() }
            } catch (e: IOException) {
                null
            }
            assets.open("emoji-index.tsv").use { EmojiIndex.load(it.readBytes(), keywords) }
        } catch (e: IOException) {
            null
        }
        state = TypingState().also { it.useModels(models) }
        typist = Typist(state, editor, models)
        strip = StripMaker(models, emoji)
        clipboard().addPrimaryClipChangedListener(clipListener)
    }

    /** The models opened from the APK (false when an asset was missing). */
    fun hasModels(): Boolean = vi != null && en != null

    /** A model mapped in place from the APK, and how that went in `loaded`. */
    private fun openModel(name: String, loaded: MutableList<String>): Model? = try {
        (modelAssets?.invoke(name) ?: assets.openFd(name)).use { a ->
            val model = Model.open(a.parcelFileDescriptor.fd, a.startOffset, a.length)
            loaded += "$name " + (model?.let { "${it.vocabulary} words" } ?: "REFUSED") +
                " at ${a.startOffset} (${a.length} bytes)"
            model
        }
    } catch (e: IOException) {
        loaded += "$name MISSING (${e.message})"
        null
    }

    override fun onDestroy() {
        handler.removeCallbacks(stripTask)
        models?.user = null
        typist.personal = null
        typist.shortcuts = null
        onDevice?.close()
        clipboard().removePrimaryClipChangedListener(clipListener)
        state?.close()
        models?.close()
        emoji?.close()
        vi?.close()
        en?.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val frame = KeyboardFrame(this).also { inputFrame = it }
        val keys = KeyboardView(this).also {
            it.listener = this
            it.palette = savedPalette()
            keyboard = it
        }
        frame.palette = keys.palette
        val panel = EmojiPanelView(this).also {
            it.listener = this
            it.palette = keys.palette
            it.visibility = View.GONE
            emojiPanel = it
        }
        val clipboard = ClipboardPanelView(this).also {
            it.listener = this
            it.palette = keys.palette
            it.visibility = View.GONE
            clipPanel = it
        }
        frame.addView(keys, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (v in listOf(panel, clipboard)) {
            frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return frame
    }

    /** Never the full-screen editor in landscape. */
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyOptions(Options.load(prefs()))
        inputFrame?.claimInsets()
        // Numbers, PINs, phone numbers and dates get a pad; what is typed there goes in as it is.
        val home = Layouts.pageFor(info.inputType)
        typist.raw = isPassword(info) || home.isPad
        // No auto-correct where words are addresses, not language.
        typist.autoCorrect = !isAddress(info)
        loadOnDevice(info)
        expected.clear()
        typist.reset(info.initialSelStart)
        searchQuery = null
        showKeys()
        keyboard?.apply {
            this.home = home
            pad = Layouts.padFor(info.inputType, resources.configuration.locales[0])
            page = home
            shift = Shift.OFF
            enterAction = enterActionOf(info)
            // The number row on Auto: shown in portrait, left out in landscape.
            numberRow = when (options.numberRow) {
                "on" -> true
                "off" -> false
                else -> resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            }
            strip = Strip.Tools
        }
    }

    /** The keyboard shows: the band is weighed again (the window's insets may have changed). */
    override fun onWindowShown() {
        super.onWindowShown()
        inputFrame?.claimInsets()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        typist.finish()
        onDevice?.save()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // A report of this keyboard's own edits, on time or late: nothing else moved.
        val cursor = typist.cursor()
        if (newSelStart == newSelEnd && cursor >= 0) {
            if (newSelStart == cursor) {
                expected.clear()
                if (candidatesStart >= 0) composingStart = candidatesStart
                return
            }
            val ours = expected.indexOf(newSelStart)
            if (ours >= 0) {
                repeat(ours + 1) { expected.removeFirst() }
                return
            }
        }
        // The cursor left the word in progress (a tap elsewhere): the word ends where it is.
        // Some editors (Compose's text fields) report an update without the composing region
        // while the cursor is still at the end of the word: that is not a move, and ending the
        // word there left Space nothing to fix ("teh" stayed "teh" on the phone).
        if (candidatesStart >= 0) composingStart = candidatesStart
        // Where the word ends: as reported, or from where it starts and what it shows now.
        val at = when {
            candidatesStart >= 0 -> candidatesEnd
            composingStart >= 0 -> composingStart + typist.shown.length
            else -> -1
        }
        val inWord = at >= 0 && newSelStart == at && newSelEnd == at
        if (typist.shown.isEmpty()) composingStart = -1
        if (typist.shown.isNotEmpty() && !inWord) {
            composingStart = -1
            typist.finish()
            refresh(newSelStart)  // the context is what is before the cursor there
            return
        }
        if (typist.shown.isNotEmpty()) return
        // No word in progress, and the field changed in a way this keyboard's edits don't
        // explain (an app cleared it after Send, select-all and delete, a tap elsewhere): what is
        // before the cursor is read again, once.
        if (cursor < 0 && newSelStart == newSelEnd) typist.anchorAt(newSelStart) else refresh(newSelStart)
    }

    /** The field changed under the typist: it reads what is before the cursor again. */
    private fun refresh(cursor: Int) {
        expected.clear()
        typist.reset(cursor)
        showStrip()
    }

    /**
     * One edit of the field by this keyboard: one selection update from the editor, at the
     * position the typist expects.
     */
    private inline fun edit(block: () -> Unit) {
        val ic = currentInputConnection
        ic?.beginBatchEdit()
        try {
            block()
        } finally {
            ic?.endBatchEdit()
        }
        typist.cursor().takeIf { it >= 0 }?.let {
            expected.addLast(it)
            while (expected.size > EXPECTED) expected.removeFirst()
        }
    }

    // ---- the keyboard's keys ------------------------------------------------------------------

    override fun onText(text: String) {
        val q = searchQuery
        if (q != null) {
            search(q + text)
            return
        }
        edit { typist.text(text) }
        showStrip()
    }

    override fun onBackspace() {
        val q = searchQuery
        if (q != null) {
            search(q.dropLast(1))
            return
        }
        edit { typist.backspace() }
        showStrip()
    }

    override fun onEnter() {
        if (searchQuery != null) {
            onSearchClose()
            return
        }
        edit { typist.enter() }
        showStrip()
    }

    override fun onCandidate(slot: Int) {
        val pick = strip.pick(slot) ?: return
        edit {
            if (pick.emoji) {
                strip.putEmoji(typist, pick)
                memory.addRecent(pick.text)
            } else if (pick.phrase != null) {
                typist.pickPhrase(pick.phrase)
            } else {
                typist.pick(pick.text)
            }
        }
        showStrip()
    }

    /** A swipe left from Backspace: through the typist, so the words before the cursor stay right. */
    override fun onDeleteWord() {
        val q = searchQuery
        if (q != null) {
            search(q.trimEnd().dropLastWhile { !it.isWhitespace() })
            return
        }
        edit { typist.deleteWord() }
        showStrip()
    }

    /** An emoticon from the number row's second page: whole, as the emoji panel puts one in. */
    override fun onEmoticon(text: String) {
        val q = searchQuery
        if (q != null) {
            search(q + text)
            return
        }
        edit { typist.insert(text) }
        showStrip()
    }

    /** A candidate from the more-suggestions panel, as a tap on a slot takes one. */
    override fun onMorePick(text: String) {
        edit { typist.pick(text) }
        showStrip()
    }

    /**
     * A strip slot's remove mark: the word is forgotten from the personal words, as the settings'
     * list does it, and hidden, so that no model offers it (or the phrase) either.
     */
    override fun onRemove(text: String) {
        val od = onDevice ?: return
        if (' ' !in text) od.words?.forget(text)
        od.hidden?.hide(text)
        showStrip()
    }

    override fun onCursorStart() {
        typist.finish()
        keyboard?.strip = Strip.Tools
    }

    override fun onCursor(dx: Int, dy: Int) {
        val ic = currentInputConnection ?: return
        fun press(code: Int, times: Int) = repeat(times) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        press(if (dx < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, Math.abs(dx))
        press(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, Math.abs(dy))
    }

    /** The cursor moved: what's before it now is the context for the next word. */
    override fun onCursorEnd() {
        typist.reset()
        showStrip()
    }

    override fun onStripMenu() {
        keyboard?.strip = Strip.Tools
    }

    override fun onPickKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onTool(tool: Tool) {
        when (tool) {
            Tool.SETTINGS -> startActivity(
                Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Tool.THEME -> {
                val next = if (keyboard?.palette == Palette.LIGHT) Palette.DARK else Palette.LIGHT
                keyboard?.palette = next
                emojiPanel?.palette = next
                clipPanel?.palette = next
                inputFrame?.palette = next
                navBarButtons(next)
                prefs().edit().putString("palette", if (next == Palette.LIGHT) "light" else "dark").apply()
            }
            Tool.EMOJI -> showEmoji()
            Tool.CLIPBOARD -> showClipboard()
            Tool.CURSOR -> keyboard?.openCursorPad()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stripTask = Runnable {
        keyboard?.strip = strip.make(typist)
        autoCapitalise()
    }

    /** With auto-capitalise on: Shift for the first letter of a sentence. */
    private fun autoCapitalise() {
        val k = keyboard ?: return
        if (options.autoCap && k.page == Page.LETTERS && k.shift == Shift.OFF && typist.atSentenceStart()) k.shift = Shift.ONCE
    }

    /**
     * The strip follows the text, after it: the key's text goes to the field first, and when
     * keys come faster than the strip can follow, only the latest strip is worked out.
     */
    private fun showStrip() {
        handler.removeCallbacks(stripTask)
        handler.post(stripTask)
    }

    // ---- the emoji panel ----------------------------------------------------------------------

    private fun showKeys() {
        emojiPanel?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
    }

    private fun showEmoji() {
        val panel = emojiPanel ?: return
        if (panel.emoji.isEmpty()) panel.emoji = emoji?.all.orEmpty()
        panel.recents = memory.recents()
        panel.tones = memory.tones()
        panel.open()
        // The keys stay laid out (invisible), so the panel keeps their height.
        keyboard?.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
    }

    override fun onEmoji(text: String) {
        edit { typist.insert(text) }
        memory.addRecent(text)
        emojiPanel?.recents = memory.recents()
    }

    override fun onSkinTone(base: String, toned: String) {
        memory.setTone(base, toned)
        emojiPanel?.tones = memory.tones()
    }

    override fun onClosePanel() {
        showKeys()
        showStrip()
    }

    override fun onEmojiSearch() {
        showKeys()
        search("")
    }

    private fun search(query: String) {
        searchQuery = query
        val found = if (query.isBlank()) memory.recents() else emoji?.search(query).orEmpty()
        keyboard?.strip = Strip.Search(query, found)
    }

    override fun onSearchPick(emoji: String) {
        onEmoji(emoji)
    }

    override fun onSearchClose() {
        searchQuery = null
        showEmoji()
    }

    /**
     * The navigation bar over the keyboard: no scrim of its own (the keyboard's colour shows
     * through, as the band below the keys), and buttons that read on that colour.
     */
    private fun navBarButtons(palette: Palette) {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = palette == Palette.LIGHT
    }

    // ---- personal words and shortcuts --------------------------------------------------------

    /** After the first unlock: the personal words and shortcuts, as the settings left them. */
    private fun loadOnDevice(info: EditorInfo) {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return
        val od = onDevice ?: OnDevice(filesDir, prefs()).also { onDevice = it }
        od.refresh()
        strip.hidden = od.hidden?.let { h -> { text: String -> text in h } } ?: { false }
        val learning = options.learn
        typist.personal = if (learning) od.words else null
        models?.user = if (learning) od.words else null
        typist.shortcuts = if (options.shortcuts) od.shortcuts else null
        // Incognito fields ask for nothing to be learned.
        typist.learnHere = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    }

    // ---- the clipboard ------------------------------------------------------------------------

    private fun clipboard() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** The history, once the phone has been unlocked (it lives in credential-encrypted storage). */
    private var clipsStamp = -1L

    /** Read again when the settings cleared it. */
    private fun history(): ClipHistory? {
        val stamp = OnDevice.clipsStamp(prefs())
        clips?.let { if (stamp == clipsStamp) return it }
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return null
        clipsStamp = stamp
        return ClipHistory(File(filesDir, CLIPS)).also { clips = it }
    }

    private fun onCopied() {
        val clip = clipboard().primaryClip ?: return
        val extras = clip.description?.extras
        val sensitive = extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        // typist.raw: the field that has the keyboard is a password field.
        val inPassword = typist.raw
        if (!options.clipboard || !ClipHistory.keep(text, sensitive, inPassword)) return
        history()?.add(text.toString().take(MAX_CLIP), System.currentTimeMillis())
        clipPanel?.clips = history()?.clips.orEmpty()
    }

    private fun showClipboard() {
        val panel = clipPanel ?: return
        panel.clips = history()?.clips.orEmpty()
        emojiPanel?.visibility = View.GONE
        keyboard?.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
    }

    override fun onPaste(text: String) {
        edit { typist.insert(text) }
    }

    override fun onPin(text: String, pinned: Boolean) {
        history()?.pin(text, pinned)
        clipPanel?.clips = history()?.clips.orEmpty()
    }

    override fun onDelete(text: String) {
        history()?.delete(text)
        clipPanel?.clips = history()?.clips.orEmpty()
    }

    override fun onClearClips() {
        history()?.clear()
        clipPanel?.clips = history()?.clips.orEmpty()
    }

    // ---- settings -----------------------------------------------------------------------------

    /** Device-protected storage: the keyboard runs before the phone's first unlock. */
    private fun prefs() = createDeviceProtectedStorageContext().getSharedPreferences("keyboard", MODE_PRIVATE)

    private val memory by lazy { EmojiMemory(prefs()) }

    private fun savedPalette(): Palette = when (Options.load(prefs()).theme) {
        "light" -> Palette.LIGHT
        "system" -> if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) Palette.DARK else Palette.LIGHT
        else -> Palette.DARK
    }

    /**
     * The settings, as the settings screen left them: read when a field starts. A new typing
     * method needs a new core state (and a typist around it).
     */
    private fun applyOptions(o: Options) {
        options = o
        val mode = if (o.vni) TypingState.VNI else TypingState.TELEX
        if (state?.mode != mode) {
            typist.finish()
            state?.close()
            state = TypingState(mode).also { it.useModels(models) }
            typist = Typist(state, editor, models)
        }
        state?.setOption(TypingState.OPT_MODERN_STYLE, o.modernTones)
        typist.keepForeign = o.keepForeign
        state?.setOption(TypingState.OPT_FREE_MARKING, o.freeMarking)
        state?.setOption(TypingState.OPT_W_SHORTCUT, o.wShortcut)
        // The typo channel inside restoration only works where Space restores every word.
        models?.correction = if (o.tonesOnSpace == Typist.TONES_ALWAYS) o.correction else Models.CORRECT_OFF
        typist.tonesOnSpace = o.tonesOnSpace
        typist.correctWrong = o.correction
        typist.doubleSpace = o.doubleSpace
        strip.suggestions = o.suggestions
        strip.emojiSuggestions = o.emojiSuggestions
        strip.nextWords = o.nextWord
        val palette = savedPalette()
        keyboard?.apply {
            this.palette = palette
            keyPreview = o.keyPopup
            holdMillis = o.holdMillis.toLong()
            holdDigits = o.holdDigits
            spaceSwipe = o.spaceSwipe
            spaceHold = o.spaceHold
            commaHold = o.commaHold
            heightPercent = o.height
        }
        emojiPanel?.palette = palette
        clipPanel?.palette = palette
        inputFrame?.palette = palette
        navBarButtons(palette)
    }

    companion object {
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), by name so older phones read it too. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /**
         * For the tests: each model asset by name, as AssetManager.openFd gives it on the phone (a
         * descriptor, an offset and a length), from a file the test lays out. Robolectric's own
         * assets can't be opened that way.
         */
        internal var modelAssets: ((String) -> AssetFileDescriptor)? = null
        const val CLIPS = "clips.tsv"

        /** Edits whose selection updates may still be on their way. */
        private const val EXPECTED = 32
        private const val MAX_CLIP = 20_000

        fun isPassword(info: EditorInfo): Boolean {
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            return when (cls) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        fun isAddress(info: EditorInfo): Boolean {
            if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        }

        fun enterActionOf(info: EditorInfo): EnterAction {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.NEWLINE
            return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                EditorInfo.IME_ACTION_GO -> EnterAction.GO
                EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                else -> EnterAction.NEWLINE
            }
        }
    }
}
