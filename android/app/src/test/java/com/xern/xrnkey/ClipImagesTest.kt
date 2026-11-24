package com.xern.xrnkey

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.xern.xrnkey.clip.Clip
import com.xern.xrnkey.clip.ClipHistory
import com.xern.xrnkey.clip.ClipImages
import com.xern.xrnkey.ime.XrnkeyService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Copied images in the clipboard history: kept as files beside it, bounded, downscaled when big,
 * and deleted with their entry or on Clear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun synchronousAgain() {
        XrnkeyService.imageWork = null
    }

    /** A made-up picture: a gradient with a disc, [seed] making each one different. */
    private fun picture(w: Int, h: Int, seed: Int = 0, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(40 + seed * 13 % 200, 120, 200))
        c.drawCircle(w / 2f, h / 2f, minOf(w, h) / 3f, Paint().apply { color = Color.rgb(250, 200, seed * 37 % 255) })
        return ByteArrayOutputStream().also { b.compress(format, 90, it) }.toByteArray()
    }

    private fun size(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun aSmallImageIsKeptAsItCameWithAThumbnail() {
        val images = ClipImages(File(tmp.root, ClipImages.DIR))
        val png = picture(400, 300)
        val kept = images.store(ByteArrayInputStream(png), "image/png")!!
        assertEquals(ClipImages.PNG, kept.mime)
        assertArrayEquals(png, images.file(kept.name).readBytes())
        val thumb = images.thumbnail(kept.name)!!
        assertEquals(320, thumb.width)
        // The same image again is the same file.
        assertEquals(kept, images.store(ByteArrayInputStream(png), "image/*"))
        assertEquals(listOf(kept.name), images.names())
    }

    @Test
    fun aBigImageIsDownscaledAndAJpegStaysAJpeg() {
        val images = ClipImages(File(tmp.root, ClipImages.DIR))
        val big = images.store(ByteArrayInputStream(picture(3200, 2000)), "image/png")!!
        assertEquals(ClipImages.PNG, big.mime)
        assertEquals(1600 to 1000, size(images.file(big.name)))
        val photo = images.store(ByteArrayInputStream(picture(2000, 3000, format = Bitmap.CompressFormat.JPEG)), "image/jpeg")!!
        assertEquals(ClipImages.JPEG, photo.mime)
        assertTrue(photo.name.endsWith(".jpg"))
        assertEquals(1066 to 1600, size(images.file(photo.name)))
        // The bytes say what an image is, whatever the app named it.
        val named = images.store(ByteArrayInputStream(picture(10, 10, seed = 3, format = Bitmap.CompressFormat.JPEG)), "image/png")!!
        assertEquals(ClipImages.JPEG, named.mime)
    }

    @Test
    fun whatIsNoImageIsNotKept() {
        val images = ClipImages(File(tmp.root, ClipImages.DIR))
        assertNull(images.store(ByteArrayInputStream("not a picture".toByteArray()), "image/png"))
        assertTrue(images.names().isEmpty())
    }

    @Test
    fun theHistoryKeepsTheLastTenImagesAndDeletesTheRest() {
        val dir = File(tmp.root, ClipImages.DIR)
        val images = ClipImages(dir)
        val list = File(tmp.root, "clips.tsv")
        val h = ClipHistory(list, images)
        val names = mutableListOf<String>()
        fun copied(i: Int) {
            val name = images.store(ByteArrayInputStream(picture(64, 48, seed = i)), "image/png")!!.name
            names += name
            h.addImage(name, ClipImages.PNG, i.toLong())
        }
        copied(0)
        // Pinned, it stays beyond the bound.
        h.pin(Clip.IMAGE_KEY + names[0], true)
        for (i in 1..13) copied(i)
        h.add("a text between", 20)
        val kept = h.clips.mapNotNull { it.image }
        assertEquals(ClipImages.MAX + 1, kept.size)
        assertEquals((names.subList(4, 14) + names[0]).toSet(), kept.toSet())
        // The files go with the entries, the thumbnails too.
        assertEquals(kept.toSet(), images.names().toSet())
        assertEquals(kept.size * 2, dir.listFiles()!!.size)
        // The list survives a restart, texts and images alike.
        assertEquals(h.clips, ClipHistory(list, images).clips)
        // Deleted: its file goes.
        h.delete(Clip.IMAGE_KEY + names[13])
        assertFalse(images.file(names[13]).exists())
        // Clear keeps the pinned one, and only its file.
        h.clear()
        assertEquals(listOf(names[0]), h.clips.map { it.image })
        assertEquals(listOf(names[0]), images.names())
    }

    @Test
    fun aFileNoEntryNamesIsDeletedAndAnEntryWithoutItsFileDropped() {
        val dir = File(tmp.root, ClipImages.DIR)
        val images = ClipImages(dir)
        val list = File(tmp.root, "clips.tsv")
        val first = ClipHistory(list, images)
        val a = images.store(ByteArrayInputStream(picture(64, 48, seed = 1)), "image/png")!!
        val b = images.store(ByteArrayInputStream(picture(64, 48, seed = 2)), "image/png")!!
        first.addImage(a.name, a.mime, 1)
        first.addImage(b.name, b.mime, 2)
        images.file(a.name).delete()
        // A write cut short, and a file from a history the settings cleared.
        File(dir, "0123.png.tmp").writeText("x")
        val stray = images.store(ByteArrayInputStream(picture(64, 48, seed = 3)), "image/png")!!
        val h = ClipHistory(list, images)
        assertEquals(listOf(b.name), h.clips.mapNotNull { it.image })
        assertEquals(listOf(b.name), images.names())
        assertFalse(images.file(stray.name).exists())
        assertEquals(listOf(b.name), ClipHistory(list, images).clips.mapNotNull { it.image })
    }

    @Test
    fun aListLineCanOnlyNameAFileInTheFolder() {
        val list = File(tmp.root, "clips.tsv")
        list.writeText("img\t0\t1\t../clips.tsv\timage/png\nimg\t0\t2\tabc.png\timage/png\n0\t3\tkept text\n")
        val h = ClipHistory(list)
        assertEquals(listOf("abc.png"), h.clips.mapNotNull { it.image })
        assertEquals(listOf("", "kept text"), h.clips.map { it.text })
    }

    // ---- through the service ------------------------------------------------------------------

    private fun service(options: Options = Options()): XrnkeyService {
        options.save(Options.prefs(RuntimeEnvironment.getApplication()))
        XrnkeyService.imageWork = { it.run() }
        val s = Robolectric.setupService(XrnkeyService::class.java)
        s.onCreateInputView()
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        return s
    }

    /** An app copies an image: a content URI the keyboard reads, as a screenshot editor does. */
    private fun copyImage(bytes: ByteArray, n: Int, sensitive: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://com.example.screenshots/shot$n.png")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val clip = ClipData(ClipDescription("", arrayOf("image/png")), ClipData.Item(uri))
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(XrnkeyService.EXTRA_IS_SENSITIVE, true) }
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun kept(s: XrnkeyService) =
        ClipHistory(File(s.filesDir, XrnkeyService.CLIPS), ClipImages(File(s.filesDir, ClipImages.DIR))).clips

    @Test
    fun aCopiedImageJoinsTheHistory() {
        val s = service()
        copyImage(picture(1080, 2400), 1)
        val clip = kept(s).single()
        assertEquals(ClipImages.PNG, clip.mime)
        assertNotNull(clip.image)
        assertEquals(720 to 1600, size(File(File(s.filesDir, ClipImages.DIR), clip.image!!)))
        s.onDestroy()
    }

    @Test
    fun sensitiveImagesAndImagesWithTheHistoryOffAreNotKept() {
        val s = service()
        copyImage(picture(100, 100), 1, sensitive = true)
        assertTrue(kept(s).isEmpty())
        s.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, false)
        copyImage(picture(100, 100, seed = 2), 2)
        assertTrue(kept(s).isEmpty())
        s.onDestroy()
        val off = service(Options(clipboard = false))
        copyImage(picture(100, 100, seed = 3), 3)
        assertTrue(kept(off).isEmpty())
        off.onDestroy()
    }
}
