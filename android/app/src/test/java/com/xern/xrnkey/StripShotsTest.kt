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
     * accent), whole, with its 、 and 。 keys. Mozc's dictionary where it is built.
     */
    @Test
    fun theJapaneseStrips() {
        val dir = File(System.getProperty("xrnkey.shots") ?: return)
        dir.mkdirs()
        val dict = if (TestModels.realJapaneseAvailable) TestModels.realJapanese() else TestModels.japanese()
        val cases = listOf("composing-kami" to "kami", "converted-kami" to "kami ", "next-kami" to "kami  ", "sentence" to "watashihagakuseidesu")
        for ((name, keys) in cases) for ((theme, palette) in listOf("dark" to Palette.DARK, "light" to Palette.LIGHT)) {
            val t = KanaTypist(FakeEditor(), Converter(dict)).also { it.reset() }
            t.text(keys)
            val v = KeyboardView(RuntimeEnvironment.getApplication())
            v.palette = palette
            v.language = "ja"
            v.kana = true
            v.strip = KanaStrip().make(t)
            v.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "japanese-$name-$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
