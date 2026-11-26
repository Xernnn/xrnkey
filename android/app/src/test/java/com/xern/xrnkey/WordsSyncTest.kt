package com.xern.xrnkey

import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import com.xern.xrnkey.engine.PersonalWords
import com.xern.xrnkey.ime.OnDevice
import com.xern.xrnkey.sync.Gate
import com.xern.xrnkey.sync.WordsProvider
import com.xern.xrnkey.sync.WordsTable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.File
import java.io.FileNotFoundException

/**
 * The personal words' door for the hub app: who gets in, when, and what a read and a patch do to the
 * keyboard's words. Made-up words only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WordsSyncTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Options.prefs(app)
    private val wordsFile get() = File(app.filesDir, OnDevice.WORDS)
    private val words = Uri.parse("content://${WordsProvider.AUTHORITY}/${WordsProvider.WORDS_PATH}")
    private var keyboard: OnDevice? = null

    private fun turn(learn: Boolean = true, sync: Boolean = true) =
        prefs.edit().putBoolean("learn", learn).putBoolean("sync_words", sync).commit()

    private fun provider(): WordsProvider = Robolectric.buildContentProvider(WordsProvider::class.java).create().get()

    private fun WordsProvider.read(): String =
        ParcelFileDescriptor.AutoCloseInputStream(openFile(words, "r")).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun WordsProvider.send(patch: String, token: String = "a-token-1"): Bundle {
        call(WordsProvider.PUT, token, Bundle().apply { putString(WordsProvider.KEY_TEXT, patch) })
        return call(WordsProvider.COMMIT, token, null)
    }

    private fun WordsProvider.faded(): List<String> =
        ParcelFileDescriptor.AutoCloseInputStream(openFile(Uri.parse("content://${WordsProvider.AUTHORITY}/faded"), "r"))
            .use { it.readBytes().toString(Charsets.UTF_8) }.lines().filter { it.isNotEmpty() }.sorted()

    private fun lines(n: Int) = Bundle().apply { putInt(WordsProvider.KEY_LINES, n) }

    private fun saved(vararg learned: Pair<String?, String>) = PersonalWords().use { w ->
        learned.forEach { (prev, word) -> w.learn(prev, word) }
        w.save(wordsFile)
    }

    @Before
    fun on() {
        turn()
    }

    @After
    fun off() {
        keyboard?.close()
        ShadowBinder.reset()
    }

    // ---- who gets in ---------------------------------------------------------------------------

    @Test
    fun thePermissionIsASignatureOneTheHubsCertificateIsGranted() {
        val info = app.packageManager.getPermissionInfo(WordsProvider.PERMISSION, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, info.protection)
        assertTrue("knownSigner", info.protectionFlags and PROTECTION_FLAG_KNOWN_SIGNER != 0)
        // The hub app's certificate, and nothing else: a digest the system compares as hex.
        assertEquals(listOf(HUB_CERT), app.resources.getStringArray(R.array.hub_certs).toList())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("""android:knownCerts="@array/hub_certs"""").containsMatchIn(manifest))
    }

    @Test
    fun theProviderAsksForThePermissionToReadAndToWrite() {
        val info = app.packageManager.resolveContentProvider(WordsProvider.AUTHORITY, 0)!!
        assertTrue(info.exported)
        assertEquals(WordsProvider.PERMISSION, info.readPermission)
        assertEquals(WordsProvider.PERMISSION, info.writePermission)
    }

    @Test
    fun onlyTheHubHoldingThePermissionGetsIn() {
        val mine = Process.myUid()
        assertTrue(Gate.allows(mine, mine, holdsPermission = false, callerPackage = null))
        assertTrue(Gate.allows(OTHER_UID, mine, holdsPermission = true, callerPackage = Gate.HUB))
        assertFalse(Gate.allows(OTHER_UID, mine, holdsPermission = false, callerPackage = Gate.HUB))
        assertFalse(Gate.allows(OTHER_UID, mine, holdsPermission = true, callerPackage = "com.example.other"))
        assertFalse(Gate.allows(OTHER_UID, mine, holdsPermission = true, callerPackage = null))
    }

    @Test
    fun anotherAppIsRefusedAtEveryDoorEvenHoldingThePermission() {
        saved("<s>" to "phở", "<s>" to "phở")
        val p = provider()
        ShadowBinder.setCallingUid(OTHER_UID)
        ShadowBinder.setCallingPid(OTHER_PID)
        val doors = listOf<() -> Unit>(
            { p.call(WordsProvider.STATE, null, null) },
            { p.read() },
            { p.send("bún\t2\n") },
            { p.query(words, null, null, null, null) },
        )
        doors.forEach { assertThrows(SecurityException::class.java) { it() } }
        // Signed with a key the permission trusts, but not the hub's package: still out.
        // Robolectric answers checkCallingPermission for this process, whoever calls, so it's granted here.
        shadowOf(app).grantPermissions(WordsProvider.PERMISSION)
        shadowOf(p).setCallingPackage("com.example.other")
        doors.forEach { assertThrows(SecurityException::class.java) { it() } }
        // The hub holding it gets in.
        shadowOf(p).setCallingPackage(Gate.HUB)
        assertTrue(p.call(WordsProvider.STATE, null, null).getBoolean(WordsProvider.KEY_SYNC))
        assertTrue("phở" in p.read())
        ShadowBinder.reset()
        assertEquals(2.0, PersonalWords().use { it.load(wordsFile); it.count(null, "phở") }, 0.0)
    }

    // ---- when ----------------------------------------------------------------------------------

    @Test
    fun nothingPassesWhileLearningOrSyncIsOff() {
        saved("<s>" to "phở", "<s>" to "phở")
        val p = provider()
        for ((learn, sync) in listOf(false to true, true to false, false to false)) {
            turn(learn, sync)
            val state = p.call(WordsProvider.STATE, null, null)
            assertEquals(learn, state.getBoolean(WordsProvider.KEY_LEARN))
            assertEquals(sync, state.getBoolean(WordsProvider.KEY_SYNC))
            assertNull(state.getString(WordsProvider.KEY_ID))
            assertThrows(FileNotFoundException::class.java) { p.read() }
            assertThrows(IllegalStateException::class.java) { p.send("bún\t2\n") }
        }
        assertEquals(2.0, PersonalWords().use { it.load(wordsFile); it.count(null, "phở") }, 0.0)
        assertEquals(0.0, PersonalWords().use { it.load(wordsFile); it.count(null, "bún") }, 0.0)
    }

    @Test
    fun syncIsOffUntilTheOwnerTurnsItOn() {
        assertFalse(Options().syncWords)
        prefs.edit().clear().commit()
        assertFalse(provider().call(WordsProvider.STATE, null, null).getBoolean(WordsProvider.KEY_SYNC))
    }

    @Test
    fun theTablesIdStaysUntilTheKeyboardsFilesGo() {
        val p = provider()
        val id = p.call(WordsProvider.STATE, null, null).getString(WordsProvider.KEY_ID)!!
        assertEquals(id, p.call(WordsProvider.STATE, null, null).getString(WordsProvider.KEY_ID))
        OnDevice.clearWords(app.filesDir, prefs) // Clear all is a deletion to sync, not a new table
        assertEquals(id, p.call(WordsProvider.STATE, null, null).getString(WordsProvider.KEY_ID))
        app.filesDir.listFiles()!!.forEach { it.deleteRecursively() } // the app's data cleared
        assertNotEquals(id, p.call(WordsProvider.STATE, null, null).getString(WordsProvider.KEY_ID))
    }

    // ---- what ----------------------------------------------------------------------------------

    @Test
    fun aReadHasWhatTheKeyboardLearnedSinceItLastSaved() {
        val od = OnDevice(app.filesDir, prefs).also { keyboard = it }
        od.refresh()
        assertEquals("", provider().read())
        od.words!!.learn("<s>", "phở")
        od.words!!.learn("<s>", "phở")
        val text = provider().read()
        assertTrue(text, "phở\t2\n" in text && "<s> phở\t2\n" in text)
    }

    @Test
    fun aPatchChangesTheFileAndTheTableTheKeyboardIsUsing() {
        saved("<s>" to "phở", "<s>" to "phở", "<s>" to "bún")
        val od = OnDevice(app.filesDir, prefs).also { keyboard = it }
        od.refresh()
        val table = od.words!!
        od.words!!.learn("<s>", "chè") // not saved yet: the patch keeps it
        val done = provider().send("phở\t-\nbún\t7\n<s> bún\t-\ncơm\t2\nbad line\n", token = "token-two")
        assertEquals(listOf(2, 2, 1), listOf(done.getInt("set"), done.getInt("deleted"), done.getInt("skipped")))
        assertSame(table, od.words) // the typist and the models hold this one
        assertEquals(0.0, table.count(null, "phở"), 0.0)
        assertEquals(7.0, table.count(null, "bún"), 0.0)
        assertEquals(2.0, table.count(null, "cơm"), 0.0)
        assertEquals(1.0, table.count(null, "chè"), 0.0)
        assertEquals(0.0, table.count("<s>", "bún"), 0.0)
        assertEquals(0, table.unsaved)
        // Read again, the file is the same table: the keyboard's next field doesn't read it again.
        val fresh = PersonalWords().apply { load(wordsFile) }
        assertEquals(table.words(), fresh.words())
        fresh.close()
    }

    @Test
    fun withoutTheKeyboardUpThePatchWaitsInTheFileForItsNextField() {
        saved("<s>" to "phở")
        provider().send("bún\t3\n")
        val od = OnDevice(app.filesDir, prefs).also { keyboard = it }
        od.refresh()
        assertEquals(3.0, od.words!!.count(null, "bún"), 0.0)
        assertEquals(1.0, od.words!!.count(null, "phở"), 0.0)
    }

    @Test
    fun aPatchComesInPartsAndOnlyItsOwnTokenCommitsIt() {
        val p = provider()
        p.call(WordsProvider.PUT, "token-a1", Bundle().apply { putString(WordsProvider.KEY_TEXT, "phở\t2") })
        p.call(WordsProvider.PUT, "token-a1", Bundle().apply { putString(WordsProvider.KEY_TEXT, "bún\t3\n") })
        assertThrows(IllegalStateException::class.java) { p.call(WordsProvider.COMMIT, "token-b2", null) }
        val done = p.call(WordsProvider.COMMIT, "token-a1", null)
        assertEquals(listOf(2, 2), listOf(done.getInt("set"), done.getInt(WordsProvider.KEY_LINES)))
        assertThrows(IllegalStateException::class.java) { p.call(WordsProvider.COMMIT, "token-a1", null) } // once only
        assertThrows(IllegalArgumentException::class.java) { p.call(WordsProvider.COMMIT, "../x", null) }
        val tooLong = "x".repeat(WordsProvider.MAX_PART + 1)
        assertThrows(IllegalArgumentException::class.java) {
            p.call(WordsProvider.PUT, "token-a1", Bundle().apply { putString(WordsProvider.KEY_TEXT, tooLong) })
        }
    }

    @Test
    fun aPatchLostWithTheKeyboardsProcessFailsRatherThanLookingApplied() {
        // The hub put its patch, then the keyboard's process died (an update, a low-memory kill):
        // Android starts a new provider, which holds no parts. Its commit used to apply nothing and
        // answer 0 set, 0 deleted, and the hub saved its base as if the phone had the hub's words,
        // so the next round took them as deleted on the phone and deleted them everywhere.
        saved("<s>" to "phở")
        provider().call(WordsProvider.PUT, "token-lost", Bundle().apply { putString(WordsProvider.KEY_TEXT, "bún\t3\nphở\t-\n") })
        val restarted = provider()
        assertThrows(IllegalStateException::class.java) { restarted.call(WordsProvider.COMMIT, "token-lost", lines(2)) }
        // Restarted between two parts: only the second arrived, and the count of lines tells.
        restarted.call(WordsProvider.PUT, "token-half", Bundle().apply { putString(WordsProvider.KEY_TEXT, "chè\t2\n") })
        assertThrows(IllegalStateException::class.java) { restarted.call(WordsProvider.COMMIT, "token-half", lines(3)) }
        assertThrows(IllegalStateException::class.java) { restarted.call(WordsProvider.COMMIT, "token-half", lines(1)) } // dropped
        PersonalWords().use {
            it.load(wordsFile)
            assertEquals(listOf(1.0, 0.0, 0.0), listOf(it.count(null, "phở"), it.count(null, "bún"), it.count(null, "chè")))
        }
        // The whole patch, with its count of lines, goes in.
        restarted.call(WordsProvider.PUT, "token-whole", Bundle().apply { putString(WordsProvider.KEY_TEXT, "chè\t2\nphở\t-\n") })
        val done = restarted.call(WordsProvider.COMMIT, "token-whole", lines(2))
        assertEquals(listOf(1, 1, 0, 2), listOf("set", "deleted", "skipped", WordsProvider.KEY_LINES).map(done::getInt))
    }

    @Test
    fun theWordsFileReachesTheDiskBeforeItReplacesTheOldOne() {
        // A power cut right after a patch must leave the old words or the new ones, never an empty
        // file, which the next sync would send as every word deleted.
        val file = File(app.filesDir, "durable.tsv").apply { writeText("old\t1\n") }
        val seen = mutableListOf<String>()
        WordsProvider.writeWhole(file, "new\t2\n") { out ->
            out.flush()
            seen += file.readText() // flushed while the old file still stands
        }
        assertEquals(listOf("old\t1\n"), seen)
        assertEquals("new\t2\n", file.readText())
        assertFalse(File(app.filesDir, "durable.tsv.part").exists())
    }

    @Test
    fun theStateSaysWhenClearAllWasUsedAndNoOtherEditMovesIt() {
        // Clear all empties the table on purpose; an empty table is otherwise taken as lost (a
        // power cut) and deletes nothing. The hub app tells the two apart by this time.
        val p = provider()
        assertEquals(0L, p.call(WordsProvider.STATE, null, null).getLong("clearAll"))
        saved("<s>" to "phở")
        p.send("bún\t3\n") // a synced patch, and an edit in the settings, aren't a Clear all
        OnDevice.wordsChanged(prefs, now = 5_000L)
        assertEquals(0L, p.call(WordsProvider.STATE, null, null).getLong("clearAll"))
        OnDevice.clearWords(app.filesDir, prefs, now = 7_000L)
        assertEquals(7_000L, p.call(WordsProvider.STATE, null, null).getLong("clearAll"))
        OnDevice.clearWords(app.filesDir, prefs, now = 6_000L) // a clock set back still moves it on
        assertEquals(7_001L, p.call(WordsProvider.STATE, null, null).getLong("clearAll"))
    }

    @Test
    fun wordsTheMonthlyFadeDroppedAreListedSoTheyDontSyncAsDeletions() {
        // A word added on the laptop reaches the phone with a small count, and if it isn't typed on
        // the phone the fade drops it there after some months. Synced as a deletion, it went from
        // the laptop too, where it was typed every day.
        wordsFile.writeText("${'$'}{WordsTable.HEADER}\nbún\t3\nphở\t0.5\ntừ\t0.52\n<s> phở\t0.5\n<s> bún\t3\n")
        prefs.edit().putLong("words-decayed", 1L).commit() // the last fade, long ago
        val od = OnDevice(app.filesDir, prefs).also { keyboard = it }
        od.refresh(now = 100L * 24 * 3600 * 1000)
        assertEquals(0.0, od.words!!.count(null, "phở"), 0.0)
        assertEquals(2.7, od.words!!.count(null, "bún"), 1e-9)
        val p = provider()
        assertEquals(listOf("<s> phở", "phở", "từ"), p.faded())
        // Typed again, it is a word again, not a faded one.
        od.words!!.learn("<s>", "phở")
        od.words!!.learn("<s>", "phở")
        assertEquals(listOf("từ"), p.faded())
        // Deleted by hand it is a deletion, though it once faded: the keyboard's strip, the settings.
        OnDevice.forgotByHand(app.filesDir, "phở")
        od.words!!.forget("phở")
        od.save()
        assertEquals(listOf("từ"), p.faded())
        OnDevice.clearWords(app.filesDir, prefs)
        assertEquals(emptyList<String>(), p.faded())
    }

    @Test
    fun onlyTheWordsFileIsReadAndNothingIsWrittenThroughAFile() {
        val p = provider()
        assertThrows(FileNotFoundException::class.java) { p.openFile(Uri.parse("content://${WordsProvider.AUTHORITY}/../shortcuts"), "r") }
        assertThrows(FileNotFoundException::class.java) { p.openFile(words, "w") }
    }

    companion object {
        /** android.content.pm.PermissionInfo.PROTECTION_FLAG_KNOWN_SIGNER, a system API. */
        const val PROTECTION_FLAG_KNOWN_SIGNER = 0x8000000
        const val HUB_CERT = "952ddb480995b0cb812e0f46d8185b9ca3b28686987256c6ea2274d7567dce6f"
        const val OTHER_UID = 10_999
        const val OTHER_PID = 31_999
    }
}
