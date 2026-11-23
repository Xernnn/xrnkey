package com.xern.xrnkey

import com.xern.xrnkey.engine.Model
import com.xern.xrnkey.engine.Models
import com.xern.xrnkey.ja.Dictionary
import java.io.File
import java.io.RandomAccessFile

/**
 * The models the tests use: the small ones trained from made-up text (android/host builds
 * them), or the real ones when this machine has them (data/lm/README.md). The same for the
 * Japanese dictionary: the made-up one (data/ja/test/dict.txt), or Mozc's (data/ja/README.md).
 */
object TestModels {
    val testDir = File(System.getProperty("xrnkey.testModels") ?: "build/host/lm")
    val realDir = File(System.getProperty("xrnkey.realModels") ?: "")

    val realAvailable: Boolean get() = File(realDir, "vi-phone.xklm").isFile && File(realDir, "en.xklm").isFile

    /** The made-up Japanese dictionary, and Mozc's when this machine has it built. */
    val testJapanese = File(testDir, "ja.xkdic")
    val realJapanese = File(realDir, "ja.xkdic")
    val realJapaneseAvailable: Boolean get() = realJapanese.isFile

    /**
     * The files the APK bundles as vi.xklm, en.xklm and ja.xkdic: the real ones, else the test
     * ones.
     */
    val bundled: Map<String, File>
        get() = (
            if (realAvailable) {
                mapOf("vi.xklm" to File(realDir, "vi-phone.xklm"), "en.xklm" to File(realDir, "en.xklm"))
            } else {
                mapOf("vi.xklm" to File(testDir, "vi.xklm"), "en.xklm" to File(testDir, "en.xklm"))
            }
            ) + ("ja.xkdic" to if (realJapaneseAvailable) realJapanese else testJapanese)

    fun japanese(): Dictionary = Dictionary.open(testJapanese)

    fun realJapanese(): Dictionary = Dictionary.open(realJapanese)

    class Opened(val vi: Model, val en: Model) : AutoCloseable {
        val models = Models(vi, en)
        override fun close() {
            models.close()
            vi.close()
            en.close()
        }
    }

    fun test(): Opened = Opened(
        Model.open(File(testDir, "vi.xklm").path)!!,
        Model.open(File(testDir, "en.xklm").path)!!,
    )

    fun real(): Opened = Opened(
        Model.open(File(realDir, "vi-phone.xklm").path)!!,
        Model.open(File(realDir, "en.xklm").path)!!,
    )

    /** The real models when present, else the test ones. */
    fun best(): Opened = if (realAvailable) real() else test()

    /**
     * Models laid out in one file the way AGP lays out uncompressed assets in an APK: aligned
     * to 4 bytes, not to 8. Each starts at an offset of 4 mod 8, where the phone's English model
     * was when the reader refused it. [places] holds each one's offset and length, as
     * AssetFileDescriptor gives them on the phone.
     */
    class Packed(val file: File, val places: Map<String, Pair<Long, Long>>) : AutoCloseable {
        override fun close() {
            file.delete()
        }
    }

    fun packed(models: Map<String, File> = bundled): Packed {
        val file = File.createTempFile("models", ".apk")
        val places = LinkedHashMap<String, Pair<Long, Long>>()
        RandomAccessFile(file, "rw").use { out ->
            out.write(ByteArray(PACK_START))
            for ((name, model) in models) {
                while (out.filePointer % 8 != 4L) out.write(0)
                val bytes = model.readBytes()
                places[name] = out.filePointer to bytes.size.toLong()
                out.write(bytes)
                out.write(ByteArray(3))  // the next file's header, roughly
            }
        }
        return Packed(file, places)
    }

    /** Where the first model starts in [packed]: inside a page, like an asset in an APK. */
    private const val PACK_START = 4100

    /** The descriptor number behind a RandomAccessFile (what AssetFileDescriptor gives on a phone). */
    fun fdOf(f: RandomAccessFile): Int {
        val field = java.io.FileDescriptor::class.java.getDeclaredField("fd")
        field.isAccessible = true
        return field.getInt(f.fd)
    }
}
