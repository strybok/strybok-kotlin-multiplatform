package com.strybok.kmp

import com.strybok.kmp.internal.IntegrityAttestor
import com.strybok.kmp.internal.platformAttestor
import com.strybok.kmp.internal.requestHashOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class AttestationAndroidTest {
    /** The public constructor on Android: Play Integrity through the provider the app passed. */
    @Test
    fun attestationUsesTheIntegrityTokenProviderOfTheApp() = runTest {
        val integrity = FakeIntegrity()
        val api = AttestApi { Clock.System.now() }
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api, clientKey = CLIENT_KEY,
            coroutineContext = StandardTestDispatcher(testScheduler),
            attestation = true, integrityTokenProvider = integrity,
        )
        strybok.createLink("/product/42")
        strybok.createLink("/product/43")

        assertEquals(
            listOf("/v1/public/attest/challenge", "/v1/public/attest/android", "/v1/public/links", "/v1/public/links"),
            api.all.map { it.path },
        )
        assertEquals(listOf(requestHashOf(api.challengeNo(1))), integrity.hashes)
        assertEquals(listOf(api.tokenNo(1), api.tokenNo(1)), api.creates.map { it.attestation })
        strybok.dispose()
    }

    /** A build for a market without Google Play passes no provider. */
    @Test
    fun withoutAProviderTheAndroidAppCannotAttest() = runTest {
        assertNull(platformAttestor(null))
        assertTrue(platformAttestor(FakeIntegrity()) is IntegrityAttestor)

        val api = AttestApi { Clock.System.now() }
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api, clientKey = CLIENT_KEY,
            coroutineContext = StandardTestDispatcher(testScheduler), attestation = true,
        )
        strybok.createLink("/product/42")

        assertEquals(listOf("/v1/public/links"), api.all.map { it.path })
        assertNull(api.creates.single().attestation)
        strybok.dispose()
    }

    @Test
    fun thePlayIntegrityLibraryIsNotADependencyOfTheSdk() {
        // compileOnly: it is on the compile classpath of the SDK and nowhere else, so these tests
        // run like an app that did not add it.
        assertFalse(PlayIntegrityTokenProvider.isLibraryAvailable())
    }
}
