package com.strybok.kmp

import com.strybok.kmp.internal.AppAttestAttestor
import com.strybok.kmp.internal.AppAttestFailure
import com.strybok.kmp.internal.DeviceCheckAppAttestService
import com.strybok.kmp.internal.FileKeyIdStore
import com.strybok.kmp.internal.failureOf
import com.strybok.kmp.internal.platformAttestor
import com.strybok.kmp.internal.toByteArray
import com.strybok.kmp.internal.toNSData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import platform.DeviceCheck.DCError
import platform.DeviceCheck.DCErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSUUID

/**
 * What can be checked of the iOS glue in the simulator. App Attest itself does not exist there:
 * `generateKey`, `attestKey` and `generateAssertion` of the real service are not exercised.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class AttestationIosTest {
    @Test
    fun appAttestIsNotSupportedInTheSimulator() {
        assertFalse(DeviceCheckAppAttestService.isSupported)
    }

    /** The public constructor with the real `DCAppAttestService`: it reports "not supported" here. */
    @Test
    fun inTheSimulatorTheRequestGoesOutWithoutATokenAndTheServiceDecides() = runTest {
        assertTrue(platformAttestor(null) is AppAttestAttestor)
        val api = AttestApi { Clock.System.now() }
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api, clientKey = CLIENT_KEY,
            coroutineContext = StandardTestDispatcher(testScheduler), attestation = true,
        )
        strybok.createLink("/product/42")
        api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED") }
        assertFailsWith<StrybokAttestationRequiredException> { strybok.createLink("/product/42") }

        assertEquals(listOf("/v1/public/links", "/v1/public/links"), api.all.map { it.path })
        assertTrue(api.creates.none { ATTESTATION_HEADER in it.headers })
        strybok.dispose()
    }

    @Test
    fun applesErrorCodesAreToldApart() {
        fun dc(code: DCError) = NSError.errorWithDomain(DCErrorDomain, code.value, null)
        assertEquals(AppAttestFailure.InvalidKey, failureOf(dc(DCError.DCErrorInvalidKey)))
        assertEquals(AppAttestFailure.ServerUnavailable, failureOf(dc(DCError.DCErrorServerUnavailable)))
        assertEquals(AppAttestFailure.Other, failureOf(dc(DCError.DCErrorFeatureUnsupported)))
        assertEquals(AppAttestFailure.Other, failureOf(dc(DCError.DCErrorInvalidInput)))
        assertEquals(AppAttestFailure.Other, failureOf(dc(DCError.DCErrorUnknownSystemFailure)))
        // The same number in another domain means something else.
        assertEquals(AppAttestFailure.Other, failureOf(NSError.errorWithDomain(NSURLErrorDomain, 3, null)))
        assertEquals(AppAttestFailure.Other, failureOf(null))
    }

    @Test
    fun bytesSurviveTheTripThroughNSData() {
        for (size in listOf(0, 1, 32, 1000)) {
            val bytes = ByteArray(size) { (it * 7).toByte() }
            assertEquals(size, bytes.toNSData().length.toInt())
            assertContentEquals(bytes, bytes.toNSData().toByteArray())
        }
    }

    @Test
    fun theKeyIdStoreKeepsReplacesAndRemovesAValue() {
        val directory = NSTemporaryDirectory() + "strybok-test-" + NSUUID().UUIDString + "/nested"
        val store = FileKeyIdStore { directory }
        val keyId = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        try {
            assertNull(store.get("key.a"))
            store.remove("key.a") // Removing what is not there is fine, also before the directory exists.
            store.set("key.a", keyId)
            store.set("key.b", "other")
            assertEquals(keyId, store.get("key.a"))
            assertEquals("other", store.get("key.b"))

            store.set("key.a", "replaced")
            assertEquals("replaced", store.get("key.a"))
            // Another instance over the same directory reads the same values, as after a restart.
            assertEquals("other", FileKeyIdStore { directory }.get("key.b"))
            assertNull(FileKeyIdStore { "$directory/elsewhere" }.get("key.b"))

            store.remove("key.a")
            assertNull(store.get("key.a"))
            assertEquals("other", store.get("key.b"))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        }
    }

    @Test
    fun theKeyIdStoreNeverLeavesItsDirectoryAndNeverThrows() {
        val directory = NSTemporaryDirectory() + "strybok-test-" + NSUUID().UUIDString
        val store = FileKeyIdStore { directory }
        try {
            for (name in listOf("", "../escape", "a/b", "key..a", "KEY", "key a")) {
                store.set(name, "value")
                assertNull(store.get(name), name)
                store.remove(name)
            }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(NSTemporaryDirectory() + "escape"))

            // No directory at all (the platform could not name one): nothing is stored.
            val nowhere = FileKeyIdStore { null }
            nowhere.set("key.a", "value")
            assertNull(nowhere.get("key.a"))
            nowhere.remove("key.a")

            // A directory that cannot be created: still no exception.
            val impossible = FileKeyIdStore { "/dev/null/strybok" }
            impossible.set("key.a", "value")
            assertNull(impossible.get("key.a"))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        }
    }

    @Test
    fun theSharedKeyIdStoreWorksInTheAppsOwnStorage() {
        val name = "test." + NSUUID().UUIDString.lowercase().filter { it != '-' }
        try {
            FileKeyIdStore.shared.set(name, "value")
            assertEquals("value", FileKeyIdStore.shared.get(name))
        } finally {
            FileKeyIdStore.shared.remove(name)
        }
        assertNull(FileKeyIdStore.shared.get(name))
    }
}
