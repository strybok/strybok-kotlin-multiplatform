package com.strybok.kmp

import android.database.Cursor
import com.strybok.kmp.internal.DeferredState
import com.strybok.kmp.internal.FileValueStore
import com.strybok.kmp.internal.deferredNeedsContext
import com.strybok.kmp.internal.platformDeferred
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * A [Cursor] with [rows] of [columns] strings each. Only the four methods the source may use
 * work; any other call fails the test, which is how "no other column is read" is checked.
 */
private fun fakeCursor(columns: Int, rows: List<List<String?>>, onClose: () -> Unit = {}): Cursor {
    var position = -1
    return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
        when (method.name) {
            "moveToFirst" -> {
                position = 0
                rows.isNotEmpty()
            }
            "getColumnCount" -> columns
            "getString" -> {
                val column = args[0] as Int
                check(column == 5) { "column $column was read" }
                rows[position][column]
            }
            "close" -> onClose()
            else -> throw AssertionError("unexpected call: ${method.name}")
        }
    } as Cursor
}

/** What exists of deferred links only on the Android target: the shipped sources and the storage. */
class DeferredAndroidTest {
    // --- the public constructor ------------------------------------------------------------------

    @Test
    fun thePlatformContextIsTheAndroidContext() {
        val context: android.content.Context? = null
        val platformContext: PlatformContext? = context
        assertNull(platformContext)
        assertNull(platformDeferred(null))
        assertNotNull(deferredNeedsContext)
    }

    @Test
    fun deferredLinksNeedAContextOnAndroid() = runTest {
        assertFailsWith<IllegalArgumentException> {
            Strybok(apiBaseUrl = API, allowedHosts = setOf(HOST), transport = FakeApi(), deferredLinks = true)
        }

        val plain = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = FakeApi(),
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        assertEquals(DeferredLinkStatus.DISABLED, plain.deferredLinkStatus.value)
        assertFailsWith<IllegalStateException> { plain.enableDeferredLinks() }
        assertEquals(DeferredLinkStatus.DISABLED, plain.deferredLinkStatus.value)
        // The iOS flows do nothing here.
        assertEquals(PastedLinkResult.DISABLED, plain.redeemPastedLink(COPIED))
        assertFalse(plain.pasteboardMayContainLink())
        plain.dispose()
    }

    // --- Google Play and Galaxy Store ------------------------------------------------------------

    @Test
    fun theVendorLibrariesAreNotDependenciesOfTheSdk() {
        // compileOnly: they are on the compile classpath of the SDK and nowhere else, so these
        // tests run like an app that did not add them.
        assertFalse(PlayInstallReferrerSource.isLibraryAvailable())
        assertFalse(GalaxyStoreInstallReferrerSource.isLibraryAvailable())
    }

    @Test
    fun mapsTheResponseCodesOfTheVendorClients() {
        fun answer(code: Int) = installReferrerAnswer(code) { "strybok_click=$CLICK_ID" }

        assertEquals("strybok_click=$CLICK_ID", assertIs<InstallReferrerResult.Referrer>(answer(0)).value)
        assertEquals(InstallReferrerResult.UnavailableForNow, answer(1), "SERVICE_UNAVAILABLE")
        assertEquals(InstallReferrerResult.UnavailableForNow, answer(-1), "SERVICE_DISCONNECTED")
        assertEquals(InstallReferrerResult.Unavailable, answer(2), "FEATURE_NOT_SUPPORTED")
        assertEquals(InstallReferrerResult.Unavailable, answer(3), "DEVELOPER_ERROR")
        assertEquals(InstallReferrerResult.Unavailable, answer(4), "PERMISSION_ERROR")
        assertEquals(InstallReferrerResult.UnavailableForNow, answer(17), "a code from the future")
    }

    @Test
    fun anEmptyOrMissingReferrerIsStillAReferrerAndARemoteExceptionIsNot() {
        assertEquals("", assertIs<InstallReferrerResult.Referrer>(installReferrerAnswer(0) { "" }).value)
        assertEquals("", assertIs<InstallReferrerResult.Referrer>(installReferrerAnswer(0) { null }).value)
        // android.os.RemoteException in an app; any exception of the binder call here.
        assertEquals(
            InstallReferrerResult.UnavailableForNow,
            installReferrerAnswer(0) { throw IllegalStateException("Service not connected") },
        )
        var read = 0
        installReferrerAnswer(1) { (++read).toString() }
        assertEquals(0, read, "nothing is read unless the store said OK")
    }

    // --- AppGallery ------------------------------------------------------------------------------

    @Test
    fun appGalleryReturnsColumn5OfTheFirstRowAndClosesTheCursor() = runTest {
        var closed = 0
        val row = listOf("a", "b", "c", "d", "e", "strybok_click=$CLICK_ID", "g")
        val source = AppGalleryInstallReferrerSource { fakeCursor(7, listOf(row, row)) { closed++ } }

        assertEquals("strybok_click=$CLICK_ID", assertIs<InstallReferrerResult.Referrer>(source.installReferrer()).value)
        assertEquals(1, closed)

        val empty = AppGalleryInstallReferrerSource { fakeCursor(6, listOf(listOf("", "", "", "", "", null))) }
        assertEquals("", assertIs<InstallReferrerResult.Referrer>(empty.installReferrer()).value)
    }

    @Test
    fun appGalleryWithoutAUsableAnswerIsUnavailable() = runTest {
        var closed = 0
        val noProvider = AppGalleryInstallReferrerSource { null }
        val noRow = AppGalleryInstallReferrerSource { fakeCursor(6, emptyList()) { closed++ } }
        val tooFewColumns = AppGalleryInstallReferrerSource {
            fakeCursor(5, listOf(listOf("a", "b", "c", "d", "e"))) { closed++ }
        }
        val refused = AppGalleryInstallReferrerSource { throw SecurityException("Permission Denial") }

        assertEquals(InstallReferrerResult.Unavailable, noProvider.installReferrer())
        assertEquals(InstallReferrerResult.Unavailable, noRow.installReferrer())
        assertEquals(InstallReferrerResult.Unavailable, tooFewColumns.installReferrer())
        assertEquals(InstallReferrerResult.Unavailable, refused.installReferrer())
        assertEquals(2, closed, "both cursors were closed")
    }

    @Test
    fun anyOtherAppGalleryFailureIsUnavailableForNow() = runTest {
        val broken = AppGalleryInstallReferrerSource { throw IllegalArgumentException("Unknown URI") }
        assertEquals(InstallReferrerResult.UnavailableForNow, broken.installReferrer())

        var closed = 0
        val failingRead = AppGalleryInstallReferrerSource {
            val real = fakeCursor(6, listOf(listOf("", "", "", "", "", "x"))) { closed++ }
            Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
                if (method.name == "getString") throw IllegalStateException("CursorWindow")
                method.invoke(real, *(args ?: emptyArray()))
            } as Cursor
        }
        assertEquals(InstallReferrerResult.UnavailableForNow, failingRead.installReferrer())
        assertEquals(1, closed)
    }

    // --- the two stored values ---------------------------------------------------------------------

    private fun temporaryDirectory(): File = Files.createTempDirectory("strybok-deferred").toFile()

    @Test
    fun storesAttemptsAndDoneInADirectoryThatIsCreatedOnDemand() {
        val directory = File(temporaryDirectory(), "no_backup/com.strybok.sdk/deferred")
        val state = DeferredState(FileValueStore { directory })
        assertEquals(0, state.attempts())
        assertFalse(state.isDone())
        assertFalse(directory.exists(), "reading creates nothing")

        assertTrue(state.storeAttempts(1))
        assertTrue(state.storeAttempts(2))
        assertEquals(2, state.attempts())
        assertFalse(state.isDone())
        assertTrue(state.storeDone())

        // A new process sees the same values.
        val later = DeferredState(FileValueStore { directory })
        assertEquals(2, later.attempts())
        assertTrue(later.isDone())
        assertEquals(setOf("attempts", "done"), directory.list()!!.toSet())
        assertEquals("2", File(directory, "attempts").readText())
        directory.deleteRecursively()
    }

    @Test
    fun anUnreadableAttemptsValueMeansNoAttemptsAreLeft() {
        val directory = temporaryDirectory()
        val state = DeferredState(FileValueStore { directory })
        File(directory, "attempts").writeText("not a number")
        assertEquals(Int.MAX_VALUE, state.attempts())
        File(directory, "attempts").writeText("-4")
        assertEquals(0, state.attempts())
        directory.deleteRecursively()
    }

    @Test
    fun reportsWhenAValueCouldNotBeStored() {
        val none = DeferredState(FileValueStore { null })
        assertFalse(none.storeAttempts(1))
        assertFalse(none.storeDone())
        assertEquals(0, none.attempts())
        assertFalse(none.isDone())

        val directory = temporaryDirectory()
        val file = File(directory, "a-file").apply { writeText("x") }
        val blocked = DeferredState(FileValueStore { File(file, "below") })
        assertFalse(blocked.storeAttempts(1))
        assertFalse(blocked.storeDone())

        // Names are the SDK's own; anything that could leave the directory is refused.
        val store = FileValueStore { directory }
        store.set("../escape", "1")
        store.set("", "1")
        assertNull(store.get("../escape"))
        assertFalse(File(directory.parentFile, "escape").exists())
        store.set("done", "1")
        store.remove("done")
        assertNull(store.get("done"))
        directory.deleteRecursively()
    }
}
