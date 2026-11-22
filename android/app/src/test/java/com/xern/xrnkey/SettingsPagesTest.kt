package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.xern.xrnkey.clip.ClipHistory
import com.xern.xrnkey.engine.PersonalWords
import com.xern.xrnkey.engine.Shortcuts
import com.xern.xrnkey.ime.OnDevice
import com.xern.xrnkey.ime.XrnkeyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The settings as pages: each section opens, changes stick, and the lists edit their files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPagesTest {
    @get:Rule val rule = createAndroidComposeRule<SettingsActivity>()

    private fun app() = RuntimeEnvironment.getApplication()
    private fun prefs() = Options.prefs(app())

    private fun back() = rule.onNodeWithContentDescription("Back").performClick()

    private fun shot(name: String) {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theMainListOpensEachSectionAndBackReturns() {
        for (p in Page.sections) rule.onNodeWithText(p.title).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Keyboard").performClick()
        rule.onNodeWithText("Height").assertIsDisplayed()
        rule.onNodeWithText("Hold comma").performScrollTo().assertIsDisplayed()
        back()
        rule.onNodeWithText("Typing").performClick()
        rule.onNodeWithText("Old-style tones").assertIsDisplayed()
        rule.onNodeWithTag("W types ư").assertIsOn()
        back()
        rule.onNodeWithText("About").performClick()
        rule.onNodeWithText("Licences").performClick()
        rule.onNodeWithText("Leipzig Corpora Collection").assertIsDisplayed()
        back()
        rule.onNodeWithText("Version").assertIsDisplayed()
        back()
        rule.onNodeWithText("Theme").assertIsDisplayed()
    }

    @Test
    fun aSwitchChangesTheSettingTheKeyboardReads() {
        rule.onNodeWithText("Suggestions & auto-correct").performClick()
        rule.onNodeWithTag("Next word").assertIsOn().performClick()
        rule.onNodeWithTag("Next word").assertIsOff()
        assertFalse(Options.load(prefs()).nextWord)
        rule.onNodeWithText("Strong").performClick()
        assertEquals(2, Options.load(prefs()).correction)
        rule.onNodeWithText("When needed").performClick()
        assertEquals(1, Options.load(prefs()).tonesOnSpace)
        rule.onNodeWithTag("Auto-capitalise").performScrollTo().assertIsOff().performClick()
        assertTrue(Options.load(prefs()).autoCap)
        back()
        rule.onNodeWithText("Theme").performClick()
        rule.onNodeWithText("Light").performClick()
        assertEquals("light", Options.load(prefs()).theme)
    }

    @Test
    fun personalWordsAreListedSearchedAndDeletedOneByOne() {
        val dir = rule.activity.filesDir
        PersonalWords().use { w ->
            repeat(3) { w.learn("<s>", "alo") }
            w.learn("alo", "bạn")
            w.learn("<s>", "chào")
            w.save(File(dir, OnDevice.WORDS))
        }
        val before = prefs().getLong("words-cleared", 0L)
        rule.onNodeWithText("Personal words").performClick()
        rule.onNodeWithText("alo").assertIsDisplayed()
        rule.onNodeWithText("chào").assertIsDisplayed()
        rule.onNodeWithTag("Search").performTextInput("ch")
        rule.onNodeWithText("alo").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete chào").performClick()
        rule.onNodeWithText("chào").assertDoesNotExist()
        PersonalWords().use { w ->
            w.load(File(dir, OnDevice.WORDS))
            assertEquals(listOf("alo", "bạn"), w.words().map { it.first })
        }
        // The keyboard reads the file again at its next field.
        assertTrue(prefs().getLong("words-cleared", 0L) != before)
    }

    @Test
    fun shortcutsAreAddedEditedAndDeleted() {
        val file = File(rule.activity.filesDir, OnDevice.SHORTCUTS)
        rule.onNodeWithText("Shortcuts").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithTag("Abbreviation").performTextInput("ko")
        rule.onNodeWithTag("Expansion").performTextInput("không")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("không").assertIsDisplayed()
        Shortcuts().use { sc -> sc.load(file); assertEquals(listOf("ko" to "không"), sc.all()) }
        rule.onNodeWithText("ko").performClick()
        rule.onNodeWithTag("Expansion").performTextReplacement("không có")
        rule.onNodeWithText("Save").performClick()
        Shortcuts().use { sc -> sc.load(file); assertEquals(listOf("ko" to "không có"), sc.all()) }
        rule.onNodeWithText("ko").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("ko").assertDoesNotExist()
        Shortcuts().use { sc -> sc.load(file); assertTrue(sc.all().isEmpty()) }
    }

    @Test
    fun clearingTheClipboardHistoryEmptiesItForTheKeyboard() {
        val file = File(rule.activity.filesDir, XrnkeyService.CLIPS)
        ClipHistory(file).add("made-up clip", 1)
        val before = OnDevice.clipsStamp(prefs())
        rule.onNodeWithText("Clipboard").performClick()
        rule.onNodeWithText("Clear history").performClick()
        rule.onNodeWithText("Clear").performClick()
        assertTrue(ClipHistory(file).clips.isEmpty())
        assertTrue(OnDevice.clipsStamp(prefs()) != before)
    }

    @Test
    fun theShots() {
        PersonalWords().use { w ->
            for ((i, word) in listOf("alo", "bạn", "chào", "được", "không", "nhé", "okay", "vâng").withIndex()) {
                repeat(8 - i) { w.learn("<s>", word) }
            }
            w.save(File(rule.activity.filesDir, OnDevice.WORDS))
        }
        Shortcuts().use { sc ->
            sc.import("ko\tkhông\ndc\tđược\nvs\tvới\nnc\tnói chuyện\nbt\tbình thường\n", apply = true)
            sc.save(File(rule.activity.filesDir, OnDevice.SHORTCUTS))
        }
        for (theme in listOf("dark", "light")) {
            Options.load(prefs()).copy(theme = theme).save(prefs())
            rule.activity.runOnUiThread { rule.activity.recreate() }
            rule.waitForIdle()
            shot("settings-main-$theme")
            for (p in Page.sections) {
                rule.onNodeWithText(p.title).performClick()
                shot("settings-${p.name.lowercase()}-$theme")
                if (p == Page.ABOUT) {
                    rule.onNodeWithText("Licences").performClick()
                    shot("settings-licences-$theme")
                    back()
                }
                back()
            }
        }
    }

    @Test
    fun theSetupCardShowsUntilXrnkeyIsTheKeyboard() {
        rule.onNodeWithText("Turn on").assertIsDisplayed()
        val a = rule.activity
        val imm = a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        org.robolectric.Shadows.shadowOf(imm).setEnabledInputMethodInfoList(
            listOf(android.view.inputmethod.InputMethodInfo(a.packageName, "com.xern.xrnkey.ime.XrnkeyService", "xrnkey", null)),
        )
        android.provider.Settings.Secure.putString(
            a.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, "${a.packageName}/.ime.XrnkeyService",
        )
        a.runOnUiThread { a.recreate() }
        rule.waitForIdle()
        rule.onNodeWithText("Turn on").assertDoesNotExist()
        rule.onNodeWithText("Switch to xrnkey").assertDoesNotExist()
        rule.onNodeWithText("Keyboard").assertIsDisplayed()
    }

    @Test
    fun theKeyboardCanBeTriedOnTheMainPage() {
        rule.onNodeWithTag("Try it").performTextInput("xin chào")
        rule.onNodeWithTag("Try it").assert(hasText("xin chào"))
    }

    @Test
    fun theKeyboardPreviewFollowsTheHeight() {
        rule.onNodeWithText("Keyboard").performClick()
        // The preview is the real keyboard, scaled: its shape is the keyboard view's own.
        val real = com.xern.xrnkey.keyboard.KeyboardView(rule.activity).apply {
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
        }
        val shown = rule.onNodeWithTag("Preview").fetchSemanticsNode().size
        assertEquals(real.measuredHeight / 1080f, shown.height.toFloat() / shown.width, 0.01f)
        val before = rule.onNodeWithTag("Preview").fetchSemanticsNode().size.height
        rule.onNodeWithText("120%").performClick()
        val after = rule.onNodeWithTag("Preview").fetchSemanticsNode().size.height
        assertTrue("$before -> $after", after > before)
        rule.onNodeWithText("80%").performClick()
        assertTrue(rule.onNodeWithTag("Preview").fetchSemanticsNode().size.height < before)
    }

    @Test
    fun theThemeIsChosenFromPreviews() {
        rule.onNodeWithText("Theme").performClick()
        rule.onNodeWithText("System").performClick()
        assertEquals("system", Options.load(prefs()).theme)
        rule.onNodeWithText("Dark").performClick()
        assertEquals("dark", Options.load(prefs()).theme)
    }

    @Test
    fun aSpaceTypedInAShortcutStaysUntilSaveWhichRefusesItInAnAbbreviation() {
        val file = File(rule.activity.filesDir, OnDevice.SHORTCUTS)
        rule.onNodeWithText("Shortcuts").performClick()
        rule.onNodeWithText("Add").performClick()
        // The keyboard sends a word, a space, then the next word: the space stays put.
        rule.onNodeWithTag("Abbreviation").performTextInput("tiếng")
        rule.onNodeWithTag("Abbreviation").performTextInput(" ")
        rule.onNodeWithTag("Abbreviation").performTextInput("việt")
        rule.onNodeWithTag("Abbreviation").assert(hasText("tiếng việt"))
        rule.onNodeWithTag("Expansion").performTextInput("tiếng")
        rule.onNodeWithTag("Expansion").performTextInput(" ")
        rule.onNodeWithTag("Expansion").performTextInput("Việt Nam")
        rule.onNodeWithTag("Expansion").assert(hasText("tiếng Việt Nam"))
        // Two words are not an abbreviation: Save refuses, keeps the text, marks the field.
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithTag("Abbreviation").assert(hasText("tiếng việt"))
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).assertExists()
        assertTrue(!file.exists() || Shortcuts().use { sc -> sc.load(file); sc.all().isEmpty() })
        // One word, with the space it ended on, is saved trimmed.
        rule.onNodeWithTag("Abbreviation").performTextReplacement("tv ")
        rule.onNodeWithText("Save").performClick()
        Shortcuts().use { sc -> sc.load(file); assertEquals(listOf("tv" to "tiếng Việt Nam"), sc.all()) }
    }
}
