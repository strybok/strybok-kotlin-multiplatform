package com.strybok.kmp

import com.strybok.kmp.internal.AppAttestAttestor
import com.strybok.kmp.internal.AppAttestFailure
import com.strybok.kmp.internal.AttestationApi
import com.strybok.kmp.internal.clientDataHashOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

private const val REGISTER = "/v1/public/attest/ios/register"
private const val ASSERT = "/v1/public/attest/ios/assert"
private const val CHALLENGE = "/v1/public/attest/challenge"
private const val LINKS = "/v1/public/links"

/**
 * The iOS half of "Attestation (optional)" in the SDK contract: App Attest key registration,
 * assertions, the stored key id and starting over. The flow is common code, so it runs on every
 * target with [FakeAppAttest] in place of `DCAppAttestService` and [MemoryKeyIdStore] in place
 * of the stored key id. No real App Attest call is made here (it does not exist in the simulator).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAttestTest {
    private val device = FakeAppAttest()
    private val store = MemoryKeyIdStore()

    private fun TestScope.api(): AttestApi = AttestApi { wallClockOf(this) }

    private fun TestScope.ios(api: AttestApi, clientKey: String = CLIENT_KEY, apiBaseUrl: String = API): Strybok =
        sdk(api, attestor = AppAttestAttestor(device, store), clientKey = clientKey, apiBaseUrl = apiBaseUrl)

    private fun refused(reason: String) = errorAnswer(403, "ATTESTATION_FAILED", """{"reason":"$reason"}""")

    private fun scopeOf(clientKey: String = CLIENT_KEY, apiBaseUrl: String = API): String =
        AttestationApi(apiBaseUrl, clientKey, 1.seconds) { error("unused") }.scopeId

    private val keyName get() = "key.${scopeOf()}"
    private val pendingName get() = "pending.${scopeOf()}"

    // --- first time ----------------------------------------------------------------------------

    @Test
    fun theFirstTimeAKeyIsGeneratedAttestedAndRegistered() = runTest {
        val api = api()
        var storedWhenRegistering: String? = "unset"
        api.onExchange = { _, n ->
            storedWhenRegistering = store.get(keyName)
            api.grant(n)
        }
        val link = ios(api).createLink("/product/42")
        assertEquals("link_9", link.linkId)

        assertEquals(listOf(CHALLENGE, REGISTER, LINKS), api.all.map { it.path })
        assertEquals(listOf("generateKey", "attestKey:${device.keyNo(1)}"), device.calls)
        // clientDataHash: SHA-256 of the challenge string's UTF-8 bytes, 32 bytes.
        assertContentEquals(clientDataHashOf(api.challengeNo(1)), device.hashes.single())
        assertEquals(32, device.hashes.single().size)

        val register = api.exchanges.single()
        assertEquals("POST", register.method)
        assertEquals(
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "X-Strybok-Key" to CLIENT_KEY),
            register.headers,
        )
        assertEquals(setOf("keyId", "attestation", "challenge"), register.json.keys)
        assertEquals(device.keyNo(1), register.json.text("keyId")) // Unchanged, 44 characters.
        assertEquals(44, register.json.text("keyId").length)
        assertEquals(api.challengeNo(1), register.json.text("challenge"))
        // Standard base64 with padding.
        val attestation = register.json.text("attestation")
        assertContentEquals(device.attestationOf(device.keyNo(1)), Base64.Default.decode(attestation))
        assertTrue(attestation.endsWith("=") && attestation.none { it == '-' || it == '_' }, attestation)

        assertEquals(api.tokenNo(1), api.creates.single().attestation)
        // The key id is stored, and only after the service accepted it.
        assertNull(storedWhenRegistering)
        assertEquals(mapOf(keyName to device.keyNo(1)), store.values)
    }

    @Test
    fun aRefusedRegistrationStoresNothingAndTheNextAttemptUsesANewKey() = runTest {
        for (answer in listOf(
            refused("app_id"),
            refused("environment"),
            errorAnswer(409, "ATTESTATION_NOT_CONFIGURED", """{"scope":"application","missing":["iosTeamId"]}"""),
            errorAnswer(503, "ATTESTATION_NOT_CONFIGURED", """{"scope":"service"}"""),
            errorAnswer(429, "RATE_LIMITED"),
            HttpResponse(500, ""),
            HttpResponse(200, """{"token":"sat1.a.b"}"""),
        )) {
            val device = FakeAppAttest()
            val store = MemoryKeyIdStore()
            val api = api()
            api.onExchange = { _, n -> if (n == 1) answer else api.grant(n) }
            val strybok = sdk(api, attestor = AppAttestAttestor(device, store))

            strybok.createLink("/product/42")
            assertNull(api.creates.single().attestation, answer.body)
            assertTrue(store.values.isEmpty(), answer.body)
            assertEquals(1, api.exchanges.size) // Not retried.

            delay(60.seconds)
            strybok.createLink("/product/42")
            assertEquals(device.keyNo(2), api.exchanges.last().json.text("keyId"))
            assertEquals(api.tokenNo(2), api.creates.last().attestation)
            assertEquals(listOf(device.keyNo(2)), store.values.values.toList())
        }
    }

    // --- afterwards ----------------------------------------------------------------------------

    @Test
    fun afterwardsTheStoredKeySignsAnAssertion() = runTest {
        val api = api()
        ios(api).createLink("/product/1")
        device.calls.clear()
        device.hashes.clear()

        // A new SDK instance, as after an app restart: the token is gone, the key id is not.
        ios(api).createLink("/product/2")

        assertEquals(listOf(CHALLENGE, REGISTER, LINKS, CHALLENGE, ASSERT, LINKS), api.all.map { it.path })
        assertEquals(listOf("generateAssertion:${device.keyNo(1)}"), device.calls)
        assertContentEquals(clientDataHashOf(api.challengeNo(2)), device.hashes.single())

        val assert = api.exchanges.last()
        assertEquals(setOf("keyId", "assertion", "challenge"), assert.json.keys)
        assertEquals(device.keyNo(1), assert.json.text("keyId"))
        assertEquals(api.challengeNo(2), assert.json.text("challenge"))
        assertContentEquals(device.assertionOf(device.keyNo(1)), Base64.Default.decode(assert.json.text("assertion")))
        assertEquals(api.tokenNo(2), api.creates.last().attestation)
    }

    @Test
    fun anExpiredOrRefusedTokenIsReplacedWithAnAssertion() = runTest {
        val api = api()
        val strybok = ios(api)
        strybok.createLink("/product/1")
        api.onCreate = {
            if (it.attestation == api.tokenNo(1)) errorAnswer(401, "ATTESTATION_INVALID", """{"reason":"exhausted"}""") else created()
        }
        strybok.createLink("/product/2")

        assertEquals(listOf(REGISTER, ASSERT), api.exchanges.map { it.path })
        assertEquals(listOf(api.tokenNo(1), api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
    }

    @Test
    fun anAssertionRefusedForAnotherReasonKeepsTheKey() = runTest {
        for (answer in listOf(
            refused("counter"), refused("signature"), refused("environment"), refused("challenge"),
            errorAnswer(503, "ATTESTATION_NOT_CONFIGURED"), HttpResponse(500, ""), errorAnswer(429, "RATE_LIMITED"),
        )) {
            store.values.clear()
            store.set(keyName, device.keyNo(7))
            device.calls.clear()
            val api = api()
            api.onExchange = { _, _ -> answer }

            ios(api).createLink("/product/42")

            assertEquals(listOf(CHALLENGE, ASSERT, LINKS), api.all.map { it.path }, answer.body)
            assertNull(api.creates.single().attestation)
            assertEquals(mapOf(keyName to device.keyNo(7)), store.values)
            assertEquals(listOf("generateAssertion:${device.keyNo(7)}"), device.calls)
        }
    }

    // --- starting over -------------------------------------------------------------------------

    @Test
    fun aKeyTheServiceDoesNotKnowIsReplacedOnceWithANewChallenge() = runTest {
        val api = api()
        store.set(keyName, device.keyNo(7)) // Stored by an earlier installation.
        api.onExchange = { request, n -> if (request.path == ASSERT) refused("unknown_key") else api.grant(n) }

        val link = ios(api).createLink("/product/42")
        assertEquals("link_9", link.linkId)

        assertEquals(listOf(CHALLENGE, ASSERT, CHALLENGE, REGISTER, LINKS), api.all.map { it.path })
        assertEquals(
            listOf("generateAssertion:${device.keyNo(7)}", "generateKey", "attestKey:${device.keyNo(1)}"),
            device.calls,
        )
        assertEquals(api.challengeNo(1), api.exchanges[0].json.text("challenge"))
        assertEquals(api.challengeNo(2), api.exchanges[1].json.text("challenge"))
        assertEquals(device.keyNo(1), api.exchanges[1].json.text("keyId"))
        assertContentEquals(clientDataHashOf(api.challengeNo(2)), device.hashes.last())
        assertEquals(api.tokenNo(2), api.creates.single().attestation)
        assertEquals(mapOf(keyName to device.keyNo(1)), store.values)
    }

    @Test
    fun aKeyTheDeviceLostIsReplacedWithoutAskingTheService() = runTest {
        val api = api()
        store.set(keyName, device.keyNo(7))
        device.onGenerateAssertion = { FakeAppAttest.failing(AppAttestFailure.InvalidKey) }

        ios(api).createLink("/product/42")

        assertEquals(listOf(CHALLENGE, CHALLENGE, REGISTER, LINKS), api.all.map { it.path })
        assertEquals(api.challengeNo(2), api.exchanges.single().json.text("challenge"))
        assertEquals(api.tokenNo(1), api.creates.single().attestation)
        assertEquals(mapOf(keyName to device.keyNo(1)), store.values)
    }

    @Test
    fun startingOverHappensOnlyOncePerAttempt() = runTest {
        val api = api()
        store.set(keyName, device.keyNo(7))
        api.onExchange = { _, _ -> refused("unknown_key") } // Also for the registration that follows.

        ios(api).createLink("/product/42")

        assertEquals(listOf(CHALLENGE, ASSERT, CHALLENGE, REGISTER, LINKS), api.all.map { it.path })
        assertNull(api.creates.single().attestation)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun otherAssertionFailuresOfTheDeviceKeepTheKey() = runTest {
        val api = api()
        store.set(keyName, device.keyNo(7))
        device.onGenerateAssertion = { FakeAppAttest.failing(AppAttestFailure.Other) }

        ios(api).createLink("/product/42")

        assertEquals(listOf(CHALLENGE, LINKS), api.all.map { it.path })
        assertNull(api.creates.single().attestation)
        assertEquals(mapOf(keyName to device.keyNo(7)), store.values)
    }

    @Test
    fun aKeyTheServiceAlreadyHasProvesItselfWithAnAssertion() = runTest {
        val api = api()
        // The answer to an earlier registration got lost on the way.
        api.onExchange = { request, n -> if (request.path == REGISTER) refused("key_exists") else api.grant(n) }

        ios(api).createLink("/product/42")

        assertEquals(listOf(CHALLENGE, REGISTER, CHALLENGE, ASSERT, LINKS), api.all.map { it.path })
        assertEquals(device.keyNo(1), api.exchanges.last().json.text("keyId"))
        assertEquals(api.challengeNo(2), api.exchanges.last().json.text("challenge"))
        assertEquals(api.tokenNo(2), api.creates.single().attestation)
        assertEquals(mapOf(keyName to device.keyNo(1)), store.values)
    }

    @Test
    fun aKeyThatExistsButIsThenUnknownIsForgotten() = runTest {
        val api = api()
        api.onExchange = { request, _ -> if (request.path == REGISTER) refused("key_exists") else refused("unknown_key") }

        ios(api).createLink("/product/42")

        assertEquals(listOf(CHALLENGE, REGISTER, CHALLENGE, ASSERT, LINKS), api.all.map { it.path })
        assertNull(api.creates.single().attestation)
        assertTrue(store.values.isEmpty())
    }

    // --- Apple unavailable -----------------------------------------------------------------------

    @Test
    fun whenAppleIsUnavailableTheKeyIsKeptAndAttestedAgainNextTime() = runTest {
        val api = api()
        device.onAttestKey = { FakeAppAttest.failing(AppAttestFailure.ServerUnavailable) }
        val strybok = ios(api)

        strybok.createLink("/product/1")
        assertEquals(listOf(CHALLENGE, LINKS), api.all.map { it.path })
        assertNull(api.creates.single().attestation)
        assertEquals(mapOf(pendingName to device.keyNo(1)), store.values)

        // Not on every share: the next attempt is a minute later.
        strybok.createLink("/product/2")
        assertEquals(1, api.challenges.size)

        delay(60.seconds)
        device.onAttestKey = null
        strybok.createLink("/product/3")
        assertEquals(
            listOf("generateKey", "attestKey:${device.keyNo(1)}", "attestKey:${device.keyNo(1)}"),
            device.calls,
        )
        assertEquals(device.keyNo(1), api.exchanges.single().json.text("keyId"))
        assertEquals(api.challengeNo(2), api.exchanges.single().json.text("challenge"))
        assertEquals(api.tokenNo(1), api.creates.last().attestation)
        assertEquals(mapOf(keyName to device.keyNo(1)), store.values)
    }

    @Test
    fun aKeyAppleRefusesToAttestIsThrownAway() = runTest {
        for (failure in listOf(AppAttestFailure.InvalidKey, AppAttestFailure.Other)) {
            val device = FakeAppAttest()
            val store = MemoryKeyIdStore()
            val api = api()
            device.onAttestKey = { FakeAppAttest.failing(failure) }
            val strybok = sdk(api, attestor = AppAttestAttestor(device, store))

            strybok.createLink("/product/1")
            assertNull(api.creates.single().attestation)
            assertTrue(store.values.isEmpty(), failure.name)

            delay(60.seconds)
            device.onAttestKey = null
            strybok.createLink("/product/2")
            assertEquals(device.keyNo(2), api.exchanges.single().json.text("keyId"))
        }
    }

    @Test
    fun appAttestCallsThatNeverReturnAreGivenUpOn() = runTest {
        val api = api()
        device.onAttestKey = { awaitCancellation() }
        val strybok = ios(api)

        strybok.createLink("/product/1")
        assertEquals(30_000, currentTime)
        assertNull(api.creates.single().attestation)
        // No answer from Apple is not a reason to throw the key away.
        assertEquals(mapOf(pendingName to device.keyNo(1)), store.values)

        store.values.clear()
        store.set(keyName, device.keyNo(7))
        device.onGenerateAssertion = { awaitCancellation() }
        delay(60.seconds)
        strybok.createLink("/product/2")
        assertNull(api.creates.last().attestation)
        assertEquals(mapOf(keyName to device.keyNo(7)), store.values)
    }

    // --- unavailable -----------------------------------------------------------------------------

    @Test
    fun whereAppAttestIsNotSupportedNothingIsAskedAndTheRequestGoesOutWithoutAToken() = runTest {
        val api = api()
        device.isSupported = false // Simulator, some extensions and Macs.
        val strybok = ios(api)

        strybok.createLink("/product/42")
        api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED") }
        assertFailsWith<StrybokAttestationRequiredException> { strybok.createLink("/product/42") }

        assertEquals(listOf(LINKS, LINKS), api.all.map { it.path })
        assertTrue(api.creates.none { ATTESTATION_HEADER in it.headers })
        assertTrue(device.calls.isEmpty())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun aDeviceThatCannotMakeAKeyCannotAttest() = runTest {
        val api = api()
        device.onGenerateKey = { FakeAppAttest.failing(AppAttestFailure.Other) }

        ios(api).createLink("/product/42")

        assertEquals(listOf(CHALLENGE, LINKS), api.all.map { it.path })
        assertNull(api.creates.single().attestation)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun withoutAChallengeAppAttestIsNotTouched() = runTest {
        val api = api()
        api.onChallenge = { errorAnswer(503, "ATTESTATION_NOT_CONFIGURED") }
        ios(api).createLink("/product/42")
        assertTrue(device.calls.isEmpty())
        assertNull(api.creates.single().attestation)

        // 401: the key is wrong or switched off.
        api.onChallenge = { errorAnswer(401, "UNAUTHENTICATED") }
        assertFailsWith<StrybokUnauthorizedException> { ios(api).createLink("/product/42") }
        assertEquals(1, api.creates.size)
    }

    // --- stored key id ---------------------------------------------------------------------------

    @Test
    fun theKeyIdIsKeptPerClientKeyAndApiHostWithoutStoringEither() = runTest {
        val api = api()
        val otherKey = "pk_ZZZZ456789abcdefghijABCDEFGHIJ99"
        ios(api).createLink("/product/1")
        ios(api, clientKey = otherKey).createLink("/product/1")
        sdk(
            AttestApi { wallClockOf(this) }, attestor = AppAttestAttestor(device, store),
            apiBaseUrl = "https://staging.example.com",
        ).createLink("/product/1")

        assertEquals(
            mapOf(
                "key.${scopeOf()}" to device.keyNo(1),
                "key.${scopeOf(clientKey = otherKey)}" to device.keyNo(2),
                "key.${scopeOf(apiBaseUrl = "https://staging.example.com")}" to device.keyNo(3),
            ),
            store.values,
        )
        assertEquals(3, store.values.keys.distinct().size)
        for (name in store.values.keys) {
            assertFalse(name.contains(CLIENT_KEY) || name.contains(otherKey) || name.contains("strybok.com"), name)
            assertEquals(4 + 64, name.length) // "key." and a SHA-256 in hex.
        }
        assertNotEquals(scopeOf(), scopeOf(clientKey = otherKey))

        // Each scope finds its own key again.
        device.calls.clear()
        ios(api, clientKey = otherKey).createLink("/product/2")
        assertEquals(listOf("generateAssertion:${device.keyNo(2)}"), device.calls)
    }

    @Test
    fun slowAppAttestCallsAreNotCutOffByTheRequestTimeout() = runTest {
        // The request timeout bounds requests; App Attest calls have their own bound.
        val api = api()
        device.onAttestKey = { keyId ->
            delay(500.milliseconds) // Longer than the 60 ms request timeout of the test SDK.
            device.attestationOf(keyId)
        }
        ios(api).createLink("/product/42")
        assertEquals(api.tokenNo(1), api.creates.single().attestation)
    }
}
