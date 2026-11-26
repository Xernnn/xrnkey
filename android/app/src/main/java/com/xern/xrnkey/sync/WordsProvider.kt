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
 *     call("state")                 learn, sync, the table's id, and when Clear all was last used
 *     openFile(.../words, "r")      personal-words.tsv, after the keyboard in use saved what it learned
 *     openFile(.../faded, "r")      the keys the monthly fade dropped (one a line), less any in the
 *                                   table again: the hub sends no deletion for them
 *     call("put", token, {text})    part of a patch: "key<TAB>count" sets, "key<TAB>-" deletes
 *     call("commit", token, {lines}) applies the parts put under that token, at once: set, deleted,
 *                                   skipped, lines. Throws, applying nothing, when the token holds
 *                                   no parts (lost with a process that died) or fewer lines than
 *                                   the hub put.
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
                    putLong(KEY_CLEAR_ALL, OnDevice.clearedAll(Options.prefs(ctx())))
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
                // No parts under the token (never put, expired, or lost with a process that died):
                // a failure, never "nothing to do", or the hub would save its round as applied.
                val patch = synchronized(pending) {
                    pending.remove(token)?.takeIf { SystemClock.elapsedRealtime() - it.second <= PENDING_MS }?.first?.toString()
                } ?: throw IllegalStateException("no patch under that token")
                val lines = patch.lineSequence().count { it.isNotEmpty() }
                if (extras != null && extras.containsKey(KEY_LINES) && extras.getInt(KEY_LINES) != lines) {
                    throw IllegalStateException("part of the patch is missing")
                }
                // The table is read, patched and written on this binder thread; the keyboard's thread
                // only saves what it learned first, and swaps the new file in after.
                val applied = synchronized(APPLYING) {
                    onMain { OnDevice.live()?.save() }
                    val ready = prepare(ctx(), patch)
                    onMain { place(ctx(), ready, patch) }
                }
                Bundle().apply {
                    putInt(KEY_SET, applied.set)
                    putInt(KEY_DELETED, applied.deleted)
                    putInt(KEY_SKIPPED, applied.skipped)
                    putInt(KEY_LINES, lines)
                }
            }
            else -> throw IllegalArgumentException("unknown call $method")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforce()
        val path = uri.pathSegments.singleOrNull()
        if (path !in listOf(WORDS_PATH, FADED_PATH) || mode != "r") throw FileNotFoundException("only $WORDS_PATH or $FADED_PATH, to read")
        if (!on()) throw FileNotFoundException("Sync is off")
        // What the keyboard learned since it last saved goes in first.
        onMain { OnDevice.live()?.save() }
        if (path == FADED_PATH) return faded()
        val file = File(dir(), OnDevice.WORDS)
        val read = if (file.isFile) file else File(ctx().cacheDir, "no-words.tsv").apply { writeText("") }
        // The engine replaces the file by rename, so what is opened stays one whole version.
        return ParcelFileDescriptor.open(read, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The keys the fade dropped that aren't in the table again, in a file of their own for this read. */
    private fun faded(): ParcelFileDescriptor {
        val words = File(dir(), OnDevice.WORDS)
        val table = if (words.isFile) WordsTable.parse(words.readText()).keys else emptySet()
        val text = OnDevice.faded(dir()).filter { it !in table }.joinToString("") { "$it\n" }
        val out = File.createTempFile("faded-", ".txt", ctx().cacheDir)
        try {
            out.writeText(text)
            return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            out.delete() // read through the descriptor only
        }
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

    /** What a patch made ready came from: the file as it was read, and the settings' stamp. */
    data class From(val length: Long, val modified: Long, val edits: Long)

    /** A patch made ready ([prepare]): the new file beside the words (null: nothing changes). */
    class Ready(val part: File?, val applied: WordsTable.Applied, val from: From)

    companion object {
        const val AUTHORITY = "com.xern.xrnkey.words"
        const val PERMISSION = "com.xern.xrnkey.permission.SYNC_WORDS"
        const val WORDS_PATH = "words"
        /** The keys the monthly fade dropped: not deletions, so the hub app sends no tombstone for them. */
        const val FADED_PATH = "faded"
        const val STATE = "state"
        const val PUT = "put"
        const val COMMIT = "commit"
        const val KEY_LEARN = "learn"
        const val KEY_SYNC = "sync"
        const val KEY_ID = "id"
        /** When Clear all was last used (ms), 0 if never: an emptied table that is meant. */
        const val KEY_CLEAR_ALL = "cleared"
        const val KEY_TEXT = "text"
        const val KEY_SET = "set"
        const val KEY_DELETED = "deleted"
        const val KEY_SKIPPED = "skipped"
        /** In a commit's extras, the lines the hub put; in its answer, the lines applied. */
        const val KEY_LINES = "lines"
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
            writePart(part, text, flush)
            placeWhole(part, file)
        }

        /** [text] in [part], flushed to the disk. */
        private fun writePart(part: File, text: String, flush: (FileOutputStream) -> Unit = { it.fd.sync() }) {
            try {
                FileOutputStream(part).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    flush(out)
                }
            } catch (_: IOException) {
                part.delete()
                throw IllegalStateException("couldn't write the personal words")
            }
        }

        /** [part], written whole, renamed over [file], and the folder flushed. */
        private fun placeWhole(part: File, file: File) {
            if (!part.renameTo(file)) {
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

        /** One patch at a time: each is made ready from the file the last one left. */
        private val APPLYING = Any()

        private fun from(context: Context): From {
            val file = File(context.filesDir, OnDevice.WORDS)
            return From(if (file.isFile) file.length() else -1L, file.lastModified(), OnDevice.wordsStamp(Options.prefs(context)))
        }

        /**
         * Off the keyboard's thread: the words as last saved, with [patch] applied, written beside them
         * and flushed. The keyboard types on meanwhile; [place] swaps the file in.
         */
        fun prepare(context: Context, patch: String): Ready {
            val file = File(context.filesDir, OnDevice.WORDS)
            val from = from(context)
            val (text, applied) = WordsTable.patch(if (file.isFile) file.readText() else "", patch)
            if (applied.set + applied.deleted == 0) return Ready(null, applied, from)
            val part = File(context.filesDir, OnDevice.WORDS + ".sync")
            writePart(part, text)
            return Ready(part, applied, from)
        }

        /**
         * On the main thread: moves a patch made ready into place and has the keyboard read it. If the
         * words changed since it was made (learned since the save before it, or edited in the
         * settings), it would lose that change, so the patch is applied again here instead ([apply]).
         */
        fun place(context: Context, ready: Ready, patch: String): WordsTable.Applied {
            val live = OnDevice.live()
            if ((live?.words?.unsaved ?: 0) > 0 || from(context) != ready.from) {
                ready.part?.delete()
                return apply(context, patch)
            }
            if (ready.part != null) {
                placeWhole(ready.part, File(context.filesDir, OnDevice.WORDS))
                OnDevice.wordsChanged(Options.prefs(context))
                live?.reloadWords()
            }
            return ready.applied
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
