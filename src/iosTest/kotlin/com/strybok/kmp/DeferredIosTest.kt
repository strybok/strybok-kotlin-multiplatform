package com.strybok.kmp

import com.strybok.kmp.internal.DeferredKind
import com.strybok.kmp.internal.DeferredState
import com.strybok.kmp.internal.FileKeyIdStore
import com.strybok.kmp.internal.IosDeferredPlatform
import com.strybok.kmp.internal.deferredNeedsContext
import com.strybok.kmp.internal.deviceFamilyOf
import com.strybok.kmp.internal.platformDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIUserInterfaceIdiomCarPlay
import platform.UIKit.UIUserInterfaceIdiomMac
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIUserInterfaceIdiomTV
import platform.UIKit.UIUserInterfaceIdiomUnspecified

/**
 * What can be checked of the iOS glue of deferred links in the simulator. The flows themselves
 * are in the common tests (they run here too). **The real pasteboard is never touched by a
 * test**: `pasteboardMayContainUrl` of the real platform is not called.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class DeferredIosTest {
    @Test
    fun theDeviceFamilyIsOneOfThreeWordsOrNothing() {
        assertEquals("iphone", deviceFamilyOf(UIUserInterfaceIdiomPhone, "iPhone"))
        assertEquals("ipod", deviceFamilyOf(UIUserInterfaceIdiomPhone, "iPod touch"))
        assertEquals("ipad", deviceFamilyOf(UIUserInterfaceIdiomPad, "iPad"))
        assertEquals("iphone", deviceFamilyOf(UIUserInterfaceIdiomPhone, ""))
        for (other in listOf(
            UIUserInterfaceIdiomMac, UIUserInterfaceIdiomTV, UIUserInterfaceIdiomCarPlay, UIUserInterfaceIdiomUnspecified,
        )) {
            assertNull(deviceFamilyOf(other, "iPhone"), "$other")
        }
    }

    @Test
    fun thePlatformOfTheSdkNeedsNoContextAndDoesNoWorkWhenCreated() {
        assertSame(IosDeferredPlatform, platformDeferred(null))
        assertNull(deferredNeedsContext)
        assertEquals(DeferredKind.IOS, IosDeferredPlatform.kind)
    }

    @Test
    fun readsTheDeviceFamilyAndTheFirstLanguageOfTheSimulator() {
        // Whatever simulator runs the tests: one of the three words, and a language tag the
        // service accepts (it uses the part before the first separator only).
        val family = IosDeferredPlatform.deviceFamily()
        assertTrue(family in setOf("iphone", "ipad", "ipod"), "family: $family")
        val language = IosDeferredPlatform.preferredLanguage()
        if (language != null) {
            assertTrue(Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{1,8}){0,4}$").matches(language), "language: $language")
        }
    }

    @Test
    fun theStateLivesInTheSameKindOfStoreAsTheAttestationKeyId() {
        val directory = NSTemporaryDirectory() + "strybok-deferred-" + NSUUID().UUIDString
        val state = DeferredState(FileKeyIdStore { directory })
        assertEquals(0, state.attempts())
        assertFalse(state.isDone())
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(directory), "reading creates nothing")

        assertTrue(state.storeAttempts(1))
        assertTrue(state.storeAttempts(2))
        assertTrue(state.storeDone())

        // A new process sees the same values.
        val later = DeferredState(FileKeyIdStore { directory })
        assertEquals(2, later.attempts())
        assertTrue(later.isDone())
        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)

        // A store without a directory stores nothing, and says so.
        val none = DeferredState(FileKeyIdStore { null })
        assertFalse(none.storeAttempts(1))
        assertFalse(none.storeDone())
    }

    /** The public constructor with the real platform: flow A only, nothing sent for a foreign string. */
    @Test
    fun withTheRealPlatformAForeignStringIsDroppedWithoutARequest() = runTest {
        val api = FakeApi()
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api,
            coroutineContext = StandardTestDispatcher(testScheduler), deferredLinks = true,
        )
        assertEquals(DeferredLinkStatus.CHECKING, strybok.deferredLinkStatus.value)
        strybok.start()
        val status = strybok.deferredLinkStatus.first { it != DeferredLinkStatus.CHECKING }
        if (status != DeferredLinkStatus.NOT_FIRST_LAUNCH) {
            assertEquals(DeferredLinkStatus.AWAITING_PASTE, status)
            assertEquals(PastedLinkResult.NOT_OURS, strybok.redeemPastedLink("just some text"))
        }
        strybok.skipPastedLink() // Without allowProbabilisticMatching this never matches.
        assertTrue(api.redeems.isEmpty() && api.matches.isEmpty())
        strybok.dispose()
    }

    /** The constructors Swift callers use: all defaults, and the one with the deferred-link switches. */
    @Test
    fun theSwiftConvenienceConstructorsLeaveDeferredLinksOffUnlessAsked() {
        val plain = Strybok(API, setOf(HOST), CLIENT_KEY, false, null)
        assertEquals(DeferredLinkStatus.DISABLED, plain.deferredLinkStatus.value)
        plain.dispose()

        val off = Strybok(API, setOf(HOST), null, false, false, false, null)
        assertEquals(DeferredLinkStatus.DISABLED, off.deferredLinkStatus.value)
        off.dispose()

        val on = Strybok(API, setOf(HOST), null, false, true, false, null)
        assertEquals(DeferredLinkStatus.CHECKING, on.deferredLinkStatus.value)
        on.dispose()
    }
}
