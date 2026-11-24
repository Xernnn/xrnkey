package com.xern.xrnkey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View.MeasureSpec
import com.xern.xrnkey.engine.TypingState
import com.xern.xrnkey.ime.KanaStrip
import com.xern.xrnkey.ime.KanaTypist
import com.xern.xrnkey.ime.StripMaker
import com.xern.xrnkey.ime.Typist
import com.xern.xrnkey.ja.Converter
import com.xern.xrnkey.ja.UserHistory
import com.xern.xrnkey.keyboard.KeyboardView
import com.xern.xrnkey.keyboard.Palette
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The strip's states as the owner meets them: rendered from the real strip maker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w432dp-h960dp-400dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StripShotsTest {
    @Test
    fun theStrips() {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        val index = testEmojiIndex()  // with the strip's keywords
        val cases = listOf(
            Triple("off-ong", "ong", Typist.TONES_OFF),
            Triple("off-khong", "khong", Typist.TONES_OFF),
            Triple("off-biet", "biet", Typist.TONES_OFF),
            Triple("phrase-toi-khong-biet", "toi khong biet", Typist.TONES_OFF),
            Triple("always-khong", "khong", Typist.TONES_ALWAYS),
            Triple("finished-ban", "banj", Typist.TONES_OFF),
            Triple("next-mot", "mootj ", Typist.TONES_OFF),
            Triple("kept-em", "em", Typist.TONES_OFF),
            Triple("kept-love", "love", Typist.TONES_OFF),
            Triple("one-word-ok", "ok", Typist.TONES_OFF),
            Triple("row-haha", "haha", Typist.TONES_OFF),
            Triple("row-kkk", "kkk", Typist.TONES_OFF),
            Triple("three-kk", "kk", Typist.TONES_OFF),
            Triple("next-cam-on", "cam on ", Typist.TONES_OFF),
            Triple("next-chuc-mung-sinh-nhat", "chuc mung sinh nhat ", Typist.TONES_OFF),
            Triple("next-dep-qua", "hom nay troi dep qua ", Typist.TONES_OFF),
        )
        TestModels.best().use { m ->
            for ((name, keys, mode) in cases) for ((theme, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
                TypingState().use { s ->
                    s.useModels(m.models)
                    val t = Typist(s, FakeEditor(), m.models).also { it.tonesOnSpace = mode; it.reset() }
                    t.text(keys)
                    val v = KeyboardView(RuntimeEnvironment.getApplication())
                    v.palette = palette
                    v.strip = StripMaker(m.models, index).make(t)
                    v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    v.layout(0, 0, v.measuredWidth, v.measuredHeight)
                    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                    v.draw(Canvas(bmp))
                    // The strip, and the top row of keys under it.
                    val top = Bitmap.createBitmap(bmp, 0, 0, bmp.width, (bmp.height * 0.28f).toInt())
                    File(dir, "strip-$name-$theme.png").outputStream().use { top.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        index.close()
    }

    /**
     * The Japanese keyboard: composing, and after one and two Spaces (the candidate shown on the
     * accent), whole, with its 、 and 。 keys; the likeliest while typing (こんにちは, ありが), the
     * words that follow one put in (私, ありがとう), and a word chosen before coming first (紙).
     * Mozc's dictionary where it is built.
     */
    @Test
    fun theJapaneseStrips() {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        val dict = if (TestModels.realJapaneseAvailable) TestModels.realJapanese() else TestModels.japanese()
        val cases = listOf(
            "composing-kami" to "kami", "converted-kami" to "kami ", "next-kami" to "kami  ", "sentence" to "watashihagakuseidesu",
            "composing-konnichiha" to "konnichiha", "composing-ariga" to "ariga", "after-watashi" to "watashi \n",
            "after-arigatou" to "arigatou \n", "chosen-kami" to "kami",
            "emoji-warai" to "warai", "emoji-neko" to "neko", "emoji-kao" to "kao", "after-wo" to "honwo \n",
        )
        val emoji = com.xern.xrnkey.ja.JapaneseEmoji.load(File(System.getProperty("xrnkey.emojiJapanese")!!).readBytes())
        for ((name, keys) in cases) for ((theme, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            val conv = Converter(dict)
            if (name.startsWith("chosen")) {
                conv.history = UserHistory().also { h -> h.learn(null, conv.convert("かみ").words.first { it.text == "紙" }.segments) }
            }
            val t = KanaTypist(FakeEditor(), conv).also { it.reset() }
            for (k in keys) if (k == '\n') t.enter() else t.text(k.toString())
            val v = KeyboardView(RuntimeEnvironment.getApplication())
            v.palette = palette
            v.language = "ja"
            v.kana = true
            v.strip = KanaStrip().also { it.emoji = emoji }.make(t)
            v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "japanese-$name-$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * A sentence converted in phrases: the field above the keyboard as the service marks it (the
     * focused phrase on a tint), and the strip with the focused phrase's candidates and ⇤ ⇥: the
     * first focused, the focus moved on, the next candidate, and a phrase shrunk. Mozc's
     * dictionary where it is built.
     */
    @Test
    fun theJapanesePhrases() {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        val dict = if (TestModels.realJapaneseAvailable) TestModels.realJapanese() else TestModels.japanese()
        val cases = listOf<Pair<String, (KanaTypist) -> Unit>>(
            "first" to {},
            "moved" to { it.moveFocus(1) },
            "next" to { it.text(" ") },
            "shrunk" to { it.moveFocus(1); it.resize(-2) },
        )
        for ((name, act) in cases) for ((theme, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            val ed = FakeEditor()
            val t = KanaTypist(ed, Converter(dict)).also { it.reset() }
            t.text("kyouhaiitenkidesune ")
            act(t)
            val app = RuntimeEnvironment.getApplication()
            val field = android.widget.TextView(app).apply {
                text = com.xern.xrnkey.ime.XrnkeyService.phrasesText(ed.composing, ed.focus!!, true, palette.accent)
                textSize = 22f
                setPadding(48, 36, 48, 36)
                setTextColor(if (palette == Palette.DARK) 0xFFECEFF1.toInt() else 0xFF1F2428.toInt())
                setBackgroundColor(if (palette == Palette.DARK) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())
            }
            field.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            field.layout(0, 0, field.measuredWidth, field.measuredHeight)
            val v = KeyboardView(app)
            v.palette = palette
            v.language = "ja"
            v.kana = true
            v.strip = KanaStrip().make(t)
            v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val keys = (v.measuredHeight * 0.4f).toInt()
            val bmp = Bitmap.createBitmap(1080, field.height + keys, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            field.draw(c)
            c.save()
            c.translate(0f, field.height.toFloat())
            c.clipRect(0, 0, 1080, keys)
            v.draw(c)
            c.restore()
            File(dir, "japanese-phrases-$name-$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
