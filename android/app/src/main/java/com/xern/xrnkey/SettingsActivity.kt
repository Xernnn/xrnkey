package com.xern.xrnkey

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.xern.xrnkey.engine.ImportCounts
import com.xern.xrnkey.engine.PersonalWords
import com.xern.xrnkey.engine.Shortcuts
import com.xern.xrnkey.keyboard.Palette
import com.xern.xrnkey.ime.OnDevice
import com.xern.xrnkey.ime.XrnkeyService
import java.io.File

/** The settings' colours: the keyboard's own, so the two look alike in either theme. */
private data class Look(
    val bg: Color,
    val card: Color,
    val glyph: Color,
    val dim: Color,
    val accent: Color,
    val pill: Color,
)

private val DarkLook = Look(
    bg = Color(0xFF263238), card = Color(0xFF2E3B42), glyph = Color(0xFFECEFF1),
    dim = Color(0xFFB0BEC5), accent = Color(0xFFC4579D), pill = Color(0xFF3E4C53),
)
private val LightLook = Look(
    bg = Color(0xFFECEFF1), card = Color(0xFFFFFFFF), glyph = Color(0xFF1F2428),
    dim = Color(0xFF5F6B73), accent = Color(0xFF983A78), pill = Color(0xFFCFD8DC),
)

/** The pages: the main list, a page per section, and the licences under About. */
enum class Page(val title: String, val icon: Icon? = null) {
    MAIN(""),
    KEYBOARD("Keyboard", Icon.KEYBOARD),
    TYPING("Typing", Icon.TYPING),
    SUGGESTIONS("Suggestions & auto-correct", Icon.SUGGEST),
    WORDS("Personal words", Icon.WORDS),
    SHORTCUTS("Shortcuts", Icon.SHORTCUTS),
    CLIPBOARD("Clipboard", Icon.CLIPBOARD),
    THEME("Theme", Icon.THEME),
    ABOUT("About", Icon.ABOUT),
    LICENCES("Licences", Icon.LICENCES);

    fun parent(): Page = if (this == LICENCES) ABOUT else MAIN

    companion object {
        /** The main list, in order. */
        val sections = listOf(KEYBOARD, TYPING, SUGGESTIONS, WORDS, SHORTCUTS, CLIPBOARD, THEME, ABOUT)

        /** The main list's cards: typing, what the keyboard keeps, and the rest. */
        val groups = listOf(listOf(KEYBOARD, TYPING, SUGGESTIONS), listOf(WORDS, SHORTCUTS, CLIPBOARD), listOf(THEME, ABOUT))
    }
}

/**
 * The settings, as sections with a page each, like the keyboard the owner came from
 * (android/docs/feature-spec.md, "Settings"). The xrnkey tile is the title; labels are short,
 * with no descriptions. The two steps Android needs (Enable, Choose) show until they are done.
 * The pages take the keyboard's theme.
 */
class SettingsActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every Android version; the pages keep to the safe drawing area.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { Root(refresh) }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh++  // the keyboard picker closed
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun enabled() = imm().enabledInputMethodList.any { it.packageName == packageName }

    private fun chosen() =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true

    private val prefs by lazy { Options.prefs(this) }
    private val shortcutsFile get() = File(filesDir, OnDevice.SHORTCUTS)
    private val wordsFile get() = File(filesDir, OnDevice.WORDS)

    private fun <T> withShortcuts(change: (Shortcuts) -> T): T = Shortcuts().use { sc ->
        if (shortcutsFile.exists()) sc.load(shortcutsFile)
        val r = change(sc)
        sc.save(shortcutsFile)
        r
    }

    private fun readShortcuts(): List<Pair<String, String>> = Shortcuts().use { sc ->
        if (shortcutsFile.exists()) sc.load(shortcutsFile)
        sc.all()
    }

    private fun readWords(): List<Pair<String, Double>> = PersonalWords().use { w ->
        if (wordsFile.exists()) w.load(wordsFile)
        w.words()
    }

    private fun forgetWord(word: String) {
        PersonalWords().use { w ->
            if (wordsFile.exists()) w.load(wordsFile)
            w.forget(word)
            w.save(wordsFile)
        }
        OnDevice.wordsChanged(prefs)
    }

    private fun readText(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun version(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

    // ---- the frame ------------------------------------------------------------------------------

    @Composable
    private fun Root(@Suppress("UNUSED_PARAMETER") tick: Int) {
        var o by remember { mutableStateOf(Options.load(prefs)) }
        val set: (Options) -> Unit = { n -> o = n; n.save(prefs) }
        val dark = when (o.theme) {
            "light" -> false
            "system" -> isSystemInDarkTheme()
            else -> true
        }
        val look = if (dark) DarkLook else LightLook
        val view = LocalView.current
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val font = FontFamily(Font(R.font.misans_vf, FontWeight.Normal), Font(R.font.misans_vf, FontWeight.SemiBold))
        val ui = Ui(look, font)
        var page by rememberSaveable { mutableStateOf(Page.MAIN) }
        BackHandler(enabled = page != Page.MAIN) { page = page.parent() }

        Column(Modifier.fillMaxSize().background(look.bg).safeDrawingPadding()) {
            if (page == Page.MAIN) {
                ui.MainPage(o) { page = it }
            } else {
                ui.Header(page.title) { page = page.parent() }
                when (page) {
                    Page.KEYBOARD -> ui.Scrolling { ui.KeyboardPage(o, set) }
                    Page.TYPING -> ui.Scrolling { ui.TypingPage(o, set) }
                    Page.SUGGESTIONS -> ui.Scrolling { ui.SuggestionsPage(o, set) }
                    Page.WORDS -> ui.WordsPage(o, set)
                    Page.SHORTCUTS -> ui.ShortcutsPage(o, set)
                    Page.CLIPBOARD -> ui.Scrolling { ui.ClipboardPage(o, set) }
                    Page.THEME -> ui.Scrolling { ui.ThemePage(o, set) }
                    Page.ABOUT -> ui.Scrolling { ui.AboutPage { page = Page.LICENCES } }
                    Page.LICENCES -> ui.Scrolling { ui.LicencesPage() }
                    Page.MAIN -> Unit
                }
            }
        }
    }

    /** The building blocks, in the settings' colours and MiSans. */
    private inner class Ui(val look: Look, val font: FontFamily) {
        val label = TextStyle(color = look.glyph, fontFamily = font, fontSize = 17.sp)
        val quiet = TextStyle(color = look.dim, fontFamily = font, fontSize = 15.sp)

        @Composable
        fun Scrolling(content: @Composable () -> Unit) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }

        // ---- the main list and the frame of a page -----------------------------------------------

        @Composable
        fun MainPage(o: Options, open: (Page) -> Unit) {
            Scrolling {
                Spacer(Modifier.height(12.dp))
                Image(painterResource(R.drawable.tile), contentDescription = stringResource(R.string.app_name), Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                val isEnabled = enabled()
                val isChosen = chosen()
                // Setting up: only until xrnkey is enabled and is the keyboard in use.
                if (!isEnabled || !isChosen) {
                    Card {
                        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Step(stringResource(R.string.enable), isEnabled) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                            Step(stringResource(R.string.choose), isChosen) { imm().showInputMethodPicker() }
                        }
                    }
                }
                TryIt()
                val words = remember { readWords().size }
                val shortcuts = remember { readShortcuts().size }
                fun summary(p: Page) = when (p) {
                    Page.TYPING -> if (o.vni) "VNI" else "Telex"
                    Page.SUGGESTIONS -> listOf("Off", "Flexible", "Strong")[o.correction]
                    Page.WORDS -> if (o.learn) "$words" else "Off"
                    Page.SHORTCUTS -> if (o.shortcuts) "$shortcuts" else "Off"
                    Page.CLIPBOARD -> if (o.clipboard) "On" else "Off"
                    Page.THEME -> o.theme.replaceFirstChar { it.uppercase() }
                    Page.ABOUT -> version()
                    else -> ""
                }
                for (group in Page.groups) {
                    Card { for (p in group) NavRow(p.title, summary(p), p.icon) { open(p) } }
                }
            }
        }

        /** A field to try the keyboard in, without leaving the settings. */
        @Composable
        fun TryIt() {
            var text by remember { mutableStateOf("") }
            Card {
                Row(Modifier.heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(Icon.KEYBOARD, look.accent)
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (text.isEmpty()) Text("Try it", style = quiet.copy(fontSize = 17.sp))
                        BasicTextField(
                            text, { text = it }, textStyle = label, cursorBrush = SolidColor(look.accent),
                            modifier = Modifier.fillMaxWidth().testTag("Try it"),
                        )
                    }
                }
            }
        }

        /** Rows that belong together, on a rounded card. */
        @Composable
        fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
            Column(
                modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(look.card)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) { content() }
        }

        /** The keyboard as the settings leave it, small: the theme, and the rows' height. */
        @Composable
        fun Preview(o: Options, modifier: Modifier = Modifier) {
            val dark = when (o.theme) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true
            }
            MiniKeyboard(
                if (dark) Palette.DARK else Palette.LIGHT,
                modifier.fillMaxWidth().aspectRatio(1f / miniKeyboardRatio(o.height)).clip(RoundedCornerShape(14.dp)),
                heightPercent = o.height,
            )
        }

        @Composable
        fun Header(title: String, back: () -> Unit) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clickable(onClick = back).semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) {
                    val c = look.glyph
                    Canvas(Modifier.size(22.dp)) {
                        val w = size.width
                        val sw = 2.2.dp.toPx()
                        drawLine(c, Offset(w * 0.15f, w / 2), Offset(w * 0.85f, w / 2), sw, StrokeCap.Round)
                        drawLine(c, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.2f), sw, StrokeCap.Round)
                        drawLine(c, Offset(w * 0.15f, w / 2), Offset(w * 0.45f, w * 0.8f), sw, StrokeCap.Round)
                    }
                }
                Text(title, style = label.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
            }
        }

        @Composable
        fun NavRow(title: String, summary: String, icon: Icon? = null, onClick: () -> Unit) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    RowIcon(icon, look.accent)
                    Spacer(Modifier.width(14.dp))
                }
                Text(title, style = label, modifier = Modifier.weight(1f))
                if (summary.isNotEmpty()) Text(summary, style = quiet)
                Spacer(Modifier.width(10.dp))
                val c = look.dim
                Canvas(Modifier.size(12.dp)) {
                    val w = size.width
                    val sw = 1.8.dp.toPx()
                    drawLine(c, Offset(w * 0.3f, w * 0.1f), Offset(w * 0.75f, w * 0.5f), sw, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.75f, w * 0.5f), Offset(w * 0.3f, w * 0.9f), sw, StrokeCap.Round)
                }
            }
        }

        @Composable
        fun Step(text: String, done: Boolean, onClick: () -> Unit) {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(if (done) look.pill else look.accent).clickable(onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, color = if (done) look.glyph else Color.White, fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                if (done) {
                    Spacer(Modifier.width(8.dp))
                    Tick(look.accent)
                }
            }
        }

        @Composable
        fun Tick(color: Color) {
            Canvas(Modifier.size(22.dp)) {
                val w = size.width
                val sw = 2.6.dp.toPx()
                drawLine(color, Offset(w * 0.15f, w * 0.55f), Offset(w * 0.4f, w * 0.8f), sw, StrokeCap.Round)
                drawLine(color, Offset(w * 0.4f, w * 0.8f), Offset(w * 0.88f, w * 0.22f), sw, StrokeCap.Round)
            }
        }

        // ---- controls ---------------------------------------------------------------------------

        @Composable
        fun Toggle(title: String, on: Boolean, change: (Boolean) -> Unit) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { change(!on) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = label, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = change,
                    modifier = Modifier.testTag(title),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = look.accent, checkedThumbColor = Color.White, checkedBorderColor = look.accent,
                        uncheckedTrackColor = look.pill, uncheckedThumbColor = look.dim, uncheckedBorderColor = look.pill,
                    ),
                )
            }
        }

        @OptIn(ExperimentalLayoutApi::class)
        @Composable
        fun Choice(title: String, options: List<String>, selected: Int, change: (Int) -> Unit) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEachIndexed { i, name -> Pill(name, i == selected) { change(i) } }
                }
            }
        }

        @Composable
        fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
            Text(
                text,
                color = if (on) Color.White else look.glyph,
                fontFamily = font,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) look.accent else look.pill)
                    .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        @Composable
        fun Action(title: String, onClick: () -> Unit) {
            Text(
                title,
                style = label.copy(color = look.accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                    .padding(horizontal = 4.dp, vertical = 16.dp),
            )
        }

        @Composable
        fun Bin(name: String, onClick: () -> Unit) {
            Box(
                Modifier.size(48.dp).clickable(onClick = onClick).semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                val c = look.dim
                Canvas(Modifier.size(18.dp)) {
                    val w = size.width
                    val sw = 1.7.dp.toPx()
                    drawLine(c, Offset(w * 0.1f, w * 0.22f), Offset(w * 0.9f, w * 0.22f), sw, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.38f, w * 0.08f), Offset(w * 0.62f, w * 0.08f), sw, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.2f, w * 0.22f), Offset(w * 0.27f, w * 0.95f), sw, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.27f, w * 0.95f), Offset(w * 0.73f, w * 0.95f), sw, StrokeCap.Round)
                    drawLine(c, Offset(w * 0.73f, w * 0.95f), Offset(w * 0.8f, w * 0.22f), sw, StrokeCap.Round)
                }
            }
        }

        /**
         * A text field; its hint shows while it is empty, and names it for the tests. What the
         * keyboard sends is kept as it is while it types (changing it would break the word it is
         * composing); [refused] outlines it in the accent when Save won't take it.
         */
        @Composable
        fun Field(text: String, hint: String, change: (String) -> Unit, modifier: Modifier = Modifier, refused: Boolean = false) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier.clip(shape).background(look.pill)
                    .then(if (refused) Modifier.border(2.dp, look.accent, shape).semantics { error(hint) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (text.isEmpty()) Text(hint, style = quiet)
                BasicTextField(
                    text, change, singleLine = true, textStyle = label, cursorBrush = SolidColor(look.accent),
                    modifier = Modifier.fillMaxWidth().testTag(hint),
                )
            }
        }

        @Composable
        fun Confirm(title: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
            AlertDialog(
                onDismissRequest = onNo,
                containerColor = look.card,
                title = { Text(title, style = label.copy(fontWeight = FontWeight.SemiBold)) },
                confirmButton = { TextButton(onClick = onYes) { Text(yes, style = label.copy(color = look.accent, fontWeight = FontWeight.SemiBold)) } },
                dismissButton = { TextButton(onClick = onNo) { Text("Cancel", style = quiet) } },
            )
        }

        // ---- the pages --------------------------------------------------------------------------

        @Composable
        fun KeyboardPage(o: Options, set: (Options) -> Unit) {
            Preview(o, Modifier.padding(vertical = 6.dp).testTag("Preview"))
            Card {
                Choice("Height", Options.HEIGHT_CHOICES.map { "$it%" }, Options.HEIGHT_CHOICES.indexOf(o.height)) {
                    set(o.copy(height = Options.HEIGHT_CHOICES[it]))
                }
                val rows = listOf("auto", "on", "off")
                Choice("Number row", listOf("Auto", "On", "Off"), rows.indexOf(o.numberRow)) { set(o.copy(numberRow = rows[it])) }
            }
            Card {
                Toggle("Hold for digits", o.holdDigits) { set(o.copy(holdDigits = it)) }
                Choice("Hold delay", Options.HOLD_CHOICES.map { "$it ms" }, Options.HOLD_CHOICES.indexOf(o.holdMillis)) {
                    set(o.copy(holdMillis = Options.HOLD_CHOICES[it]))
                }
                Toggle("Key popup", o.keyPopup) { set(o.copy(keyPopup = it)) }
            }
            Card {
                Toggle("Swipe space bar", o.spaceSwipe) { set(o.copy(spaceSwipe = it)) }
                Toggle("Hold space bar", o.spaceHold) { set(o.copy(spaceHold = it)) }
                Toggle("Hold comma", o.commaHold) { set(o.copy(commaHold = it)) }
            }
        }

        @Composable
        fun TypingPage(o: Options, set: (Options) -> Unit) {
            Card { Choice("Method", listOf("Telex", "VNI"), if (o.vni) 1 else 0) { set(o.copy(vni = it == 1)) } }
            Card {
                Toggle("Old-style tones", !o.modernTones) { set(o.copy(modernTones = !it)) }
                Toggle("Tones after the word", o.freeMarking) { set(o.copy(freeMarking = it)) }
                if (!o.vni) Toggle("W types ư", o.wShortcut) { set(o.copy(wShortcut = it)) }
            }
            Card { Toggle("Underline the word", o.underline) { set(o.copy(underline = it)) } }
        }

        @Composable
        fun SuggestionsPage(o: Options, set: (Options) -> Unit) {
            Card {
                Toggle("Suggestions", o.suggestions) { set(o.copy(suggestions = it)) }
                Toggle("Next word", o.nextWord) { set(o.copy(nextWord = it)) }
                Toggle("Emoji", o.emojiSuggestions) { set(o.copy(emojiSuggestions = it)) }
            }
            Card {
                Choice("Auto-correct", listOf("Off", "Flexible", "Strong"), o.correction) { set(o.copy(correction = it)) }
                Toggle("Non-Vietnamese as typed", o.keepForeign) { set(o.copy(keepForeign = it)) }
                Toggle("Double space full stop", o.doubleSpace) { set(o.copy(doubleSpace = it)) }
            }
        }

        @Composable
        fun WordsPage(o: Options, set: (Options) -> Unit) {
            var words by remember { mutableStateOf(readWords()) }
            var query by remember { mutableStateOf("") }
            var confirmClear by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Card { Toggle("Learn words", o.learn) { set(o.copy(learn = it)) } }
                Field(query, "Search", { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) words else words.filter { it.first.contains(q) }
                LazyColumn(Modifier.weight(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(look.card).padding(horizontal = 12.dp)) {
                    items(shown, key = { it.first }) { (word, count) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(word, style = label, modifier = Modifier.weight(1f))
                            Text("%.0f".format(count), style = quiet)
                            Bin("Delete $word") {
                                forgetWord(word)
                                words = readWords()
                            }
                        }
                    }
                }
                if (words.isNotEmpty()) Action("Clear all") { confirmClear = true }
            }
            if (confirmClear) {
                Confirm("Clear personal words", "Clear", {
                    OnDevice.clearWords(filesDir, prefs)
                    words = readWords()
                    confirmClear = false
                }) { confirmClear = false }
            }
        }

        @Composable
        fun ShortcutsPage(o: Options, set: (Options) -> Unit) {
            var shortcuts by remember { mutableStateOf(readShortcuts()) }
            var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
            var pending by remember { mutableStateOf<Pair<String, ImportCounts>?>(null) }
            val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val text = uri?.let { readText(it) } ?: return@rememberLauncherForActivityResult
                val counts = Shortcuts().use { sc ->
                    if (shortcutsFile.exists()) sc.load(shortcutsFile)
                    sc.import(text, apply = false)
                } ?: return@rememberLauncherForActivityResult
                pending = text to counts
            }
            val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(readShortcuts().joinToString("") { "${it.first}\t${it.second}\n" }.toByteArray())
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Card { Toggle("Shortcuts", o.shortcuts) { set(o.copy(shortcuts = it)) } }
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Add", true) { editing = "" to "" }
                    Pill("Import", false) { importer.launch(arrayOf("text/*")) }
                    Pill("Export", false) { exporter.launch("shortcuts.txt") }
                }
                editing?.let { (oldAbbr, oldExpansion) ->
                    // The entry being added or edited, in place above the list.
                    var abbr by remember(oldAbbr) { mutableStateOf(oldAbbr) }
                    var expansion by remember(oldAbbr) { mutableStateOf(oldExpansion) }
                    var refused by remember(oldAbbr) { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(look.card).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Field(abbr, "Abbreviation", { abbr = it; refused = false }, Modifier.fillMaxWidth(), refused)
                        Field(expansion, "Expansion", { expansion = it }, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Save", true) {
                                // Trimmed here, not while typing. An abbreviation is one word: one
                                // with a space in it is refused, and the field says so.
                                val a = abbr.trim()
                                val e = expansion.trim()
                                val ok = a.isNotEmpty() && a.none { it.isWhitespace() } && e.isNotEmpty() &&
                                    withShortcuts {
                                        val saved = it.set(a, e)
                                        if (saved && oldAbbr.isNotEmpty() && oldAbbr != a) it.remove(oldAbbr)
                                        saved
                                    }
                                if (ok) {
                                    shortcuts = readShortcuts()
                                    editing = null
                                } else {
                                    refused = true
                                }
                            }
                            if (oldAbbr.isNotEmpty()) {
                                Pill("Delete", false) {
                                    withShortcuts { it.remove(oldAbbr) }
                                    shortcuts = readShortcuts()
                                    editing = null
                                }
                            }
                            Pill("Cancel", false) { editing = null }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(look.card).padding(horizontal = 12.dp)) {
                    items(shortcuts, key = { it.first }) { (abbr, expansion) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { editing = abbr to expansion }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(abbr, style = label.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(96.dp))
                            Text(expansion, style = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            pending?.let { (text, c) ->
                AlertDialog(
                    onDismissRequest = { pending = null },
                    containerColor = look.card,
                    title = { Text("Import", style = label.copy(fontWeight = FontWeight.SemiBold)) },
                    text = {
                        Column {
                            for ((name, n) in listOf("New" to c.added, "Same" to c.same, "Changed" to c.changed, "Skipped" to c.skipped)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(name, style = label, modifier = Modifier.weight(1f))
                                    Text("$n", style = label)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            withShortcuts { it.import(text, apply = true) }
                            shortcuts = readShortcuts()
                            pending = null
                        }) { Text("Import", style = label.copy(color = look.accent, fontWeight = FontWeight.SemiBold)) }
                    },
                    dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel", style = quiet) } },
                )
            }
        }

        @Composable
        fun ClipboardPage(o: Options, set: (Options) -> Unit) {
            var confirm by remember { mutableStateOf(false) }
            Card {
                Toggle("History", o.clipboard) { set(o.copy(clipboard = it)) }
                Action("Clear history") { confirm = true }
            }
            if (confirm) {
                Confirm("Clear history", "Clear", {
                    File(filesDir, XrnkeyService.CLIPS).delete()
                    OnDevice.clipsChanged(prefs)
                    confirm = false
                }) { confirm = false }
            }
        }

        @Composable
        fun ThemePage(o: Options, set: (Options) -> Unit) {
            val themes = listOf("dark" to "Dark", "light" to "Light", "system" to "System")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((key, name) in themes) {
                    val on = o.theme == key
                    val shape = RoundedCornerShape(18.dp)
                    Column(
                        Modifier.weight(1f).clip(shape).background(look.card)
                            .then(if (on) Modifier.border(2.dp, look.accent, shape) else Modifier)
                            .clickable { set(o.copy(theme = key)) }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MiniKeyboard(
                            if (key == "light") Palette.LIGHT else Palette.DARK,
                            Modifier.fillMaxWidth().aspectRatio(1f / miniKeyboardRatio(100)).clip(RoundedCornerShape(10.dp)),
                            split = if (key == "system") Palette.LIGHT else null,
                        )
                        Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (on) {
                                Tick(look.accent)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(name, style = label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }
            }
        }

        @Composable
        fun AboutPage(licences: () -> Unit) {
            Card {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(Icon.ABOUT, look.accent)
                    Spacer(Modifier.width(14.dp))
                    Text("Version", style = label, modifier = Modifier.weight(1f))
                    Text(version(), style = quiet)
                }
                NavRow("Licences", "", Icon.LICENCES, licences)
            }
        }

        @Composable
        fun LicencesPage() {
            val entries = listOf(
                "xrnkey" to "MIT",
                "Leipzig Corpora Collection" to "CC BY 4.0",
                "Tatoeba" to "CC BY 2.0 FR",
                "Common Voice" to "CC0",
                "Unicode CLDR" to "Unicode License v3",
                "MiSans" to "MiSans Font License",
            )
            Card {
                for ((what, licence) in entries) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
                        Text(what, style = label)
                        Text(licence, style = quiet)
                    }
                }
            }
        }
    }
}
