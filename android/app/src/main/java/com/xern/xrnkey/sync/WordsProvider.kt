package com.xern.xrnkey.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.xern.xrnkey.Options
import com.xern.xrnkey.ime.OnDevice
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The personal words, for the owner's hub app (android/docs/feature-spec.md, "Sync"). The keyboard has
 * no network; the hub app carries the words to the owner's server, where the laptop's keyboard gets
 * them too, and brings back what changed there. Nothing passes while "Learn words" or "Sync through
 * xrnhub" is off.
 *
 * Only the hub app gets in. [PERMISSION] is a signature permission that the hub, signed with another
 * key, is granted by its certificate's digest (knownSigner, res/values/hub.xml), and a caller must
 * also be the hub's package ([Gate]). [call] isn't covered by the manifest's permission, so every
 * entry point checks for itself.
 *
 *     call("state")                 learn, sync, and the table's id
 *     openFile(.../words, "r")      personal-words.tsv, after the keyboard in use saved what it learned
 *     call("put", token, {text})    part of a patch: "key<TAB>count" sets, "key<TAB>-" deletes
 *     call("commit", token)         applies the parts put under that token, at once: set, deleted, skipped
 *
 * The table's id is made the first time it's asked for and kept with the words, so it changes only
 * when the keyboard's files go (its data cleared, or the app installed afresh). The hub app then
 * knows that a table without its words lost them, rather than had them all deleted.
 */
class WordsProvider : ContentProvider() {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Patches arriving in parts, by token, with when each was last added to. */
    private val pending = HashMap<String, Pair<StringBuilder, Long>>()

    override fun onCreate() = true

    private fun ctx(): Context = context ?: throw IllegalStateException("not attached")

    private fun dir(): File = ctx().filesDir

    private fun enforce() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val holds = ctx().checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!Gate.allows(uid, Process.myUid(), holds, if (holds) callingPackage else null)) {
            throw SecurityException("Only xrnhub may use the personal words")
        }
    }

    private fun options() = Options.load(Options.prefs(ctx()))

    private fun on(o: Options = options()) = o.learn && o.syncWords

    private fun requireOn() {
        if (!on()) throw IllegalStateException("Sync is off")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforce()
        return when (method) {
            STATE -> options().let { o ->
                Bundle().apply {
                    putBoolean(KEY_LEARN, o.learn)
                    putBoolean(KEY_SYNC, o.syncWords)
                    putString(KEY_ID, if (on(o)) tableId(dir()) else null)
                }
            }
            PUT -> {
                requireOn()
                val token = token(arg)
                val text = extras?.getString(KEY_TEXT) ?: throw IllegalArgumentException("no text")
                if (text.length > MAX_PART) throw IllegalArgumentException("a part is at most $MAX_PART characters")
                synchronized(pending) {
                    val now = SystemClock.elapsedRealtime()
                    pending.entries.removeAll { now - it.value.second > PENDING_MS }
                    val held = pending[token]?.first ?: StringBuilder()
                    if (held.length + text.length > MAX_PATCH) throw IllegalArgumentException("the patch is too long")
                    held.append(text)
                    if (!text.endsWith("\n")) held.append('\n')
                    pending[token] = held to now
                }
                Bundle()
            }
            COMMIT -> {
                requireOn()
                val token = token(arg)
                val patch = synchronized(pending) { pending.remove(token)?.first?.toString() }.orEmpty()
                val applied = onMain { apply(ctx(), patch) }
                Bundle().apply {
                    putInt(KEY_SET, applied.set)
                    putInt(KEY_DELETED, applied.deleted)
                    putInt(KEY_SKIPPED, applied.skipped)
                }
            }
            else -> throw IllegalArgumentException("unknown call $method")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforce()
        if (uri.pathSegments != listOf(WORDS_PATH) || mode != "r") throw FileNotFoundException("only $WORDS_PATH, to read")
        if (!on()) throw FileNotFoundException("Sync is off")
        // What the keyboard learned since it last saved goes in first.
        onMain { OnDevice.live()?.save() }
        val file = File(dir(), OnDevice.WORDS)
        val read = if (file.isFile) file else File(ctx().cacheDir, "no-words.tsv").apply { writeText("") }
        // The engine replaces the file by rename, so what is opened stays one whole version.
        return ParcelFileDescriptor.open(read, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun token(arg: String?): String =
        arg?.takeIf { TOKEN.matches(it) } ?: throw IllegalArgumentException("bad token")

    /** On the keyboard's thread, where its words are used, so it can't learn in between. */
    private fun <T> onMain(work: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val task = FutureTask(work)
        main.post(task)
        return task.get(MAIN_WAIT_S, TimeUnit.SECONDS)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        enforce()
        return null
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "com.xern.xrnkey.words"
        const val PERMISSION = "com.xern.xrnkey.permission.SYNC_WORDS"
        const val WORDS_PATH = "words"
        const val STATE = "state"
        const val PUT = "put"
        const val COMMIT = "commit"
        const val KEY_LEARN = "learn"
        const val KEY_SYNC = "sync"
        const val KEY_ID = "id"
        const val KEY_TEXT = "text"
        const val KEY_SET = "set"
        const val KEY_DELETED = "deleted"
        const val KEY_SKIPPED = "skipped"
        /** Characters in one part: well inside a binder transaction's room. */
        const val MAX_PART = 100_000
        private const val MAX_PATCH = 32 * 1024 * 1024
        private const val PENDING_MS = 10 * 60_000L
        private const val MAIN_WAIT_S = 20L
        private const val ID_FILE = "words-sync-id"
        private val TOKEN = Regex("[A-Za-z0-9-]{8,64}")

        /**
         * [text] as [file], whole or not at all: written beside it, flushed to the disk ([flush])
         * before it is renamed over the old one, and the folder flushed after, so a power cut leaves
         * the old words or the new ones, never an empty file that a sync would read as deletions.
         */
        fun writeWhole(file: File, text: String, flush: (FileOutputStream) -> Unit = { it.fd.sync() }) {
            val part = File(file.parentFile, file.name + ".part")
            try {
                FileOutputStream(part).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    flush(out)
                }
                if (!part.renameTo(file)) throw IOException("rename")
            } catch (_: IOException) {
                part.delete()
                throw IllegalStateException("couldn't write the personal words")
            }
            try {
                // The rename itself, best effort: not every platform opens a folder to flush it.
                FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {
            }
        }

        /** The table's id, made once and kept with the words. */
        fun tableId(dir: File): String {
            val file = File(dir, ID_FILE)
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { TOKEN.matches(it) }?.let { return it }
            val id = UUID.randomUUID().toString()
            file.writeText(id)
            return id
        }

        /**
         * Applies a patch to the personal words, on the main thread: what the keyboard in use learned
         * is saved first, the file is changed and moved into place, and the keyboard reads it again
         * into the table it uses. Without a keyboard up, the settings' stamp has the next one read it.
         */
        fun apply(context: Context, patch: String): WordsTable.Applied {
            val dir = context.filesDir
            val prefs = Options.prefs(context)
            val live = OnDevice.live()
            live?.save()
            val file = File(dir, OnDevice.WORDS)
            val (text, applied) = WordsTable.patch(if (file.isFile) file.readText() else "", patch)
            if (applied.set + applied.deleted > 0) {
                writeWhole(file, text)
                OnDevice.wordsChanged(prefs)
                live?.reloadWords()
            }
            return applied
        }
    }
}

/** Who may use the personal words: this app itself, or the hub app holding [WordsProvider.PERMISSION]. */
object Gate {
    const val HUB = "com.xern.xrnhub"

    fun allows(callerUid: Int, ownUid: Int, holdsPermission: Boolean, callerPackage: String?): Boolean =
        callerUid == ownUid || (holdsPermission && callerPackage == HUB)
}
