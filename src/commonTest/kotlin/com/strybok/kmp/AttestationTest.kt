package com.strybok.kmp

import com.strybok.kmp.internal.Attestor
import com.strybok.kmp.internal.IntegrityAttestor
import com.strybok.kmp.internal.clientDataHashOf
import com.strybok.kmp.internal.requestHashOf
import com.strybok.kmp.internal.sha256
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val PAYLOAD_SECRET = "payload-secret-value"

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * The optional attestation of `createLink`, row by row of "Attestation (optional)" in the SDK
 * contract (docs/sdk-contract.md): everything that is the same on both platforms,
 * exercised through the Play Integrity flow. Google Play is replaced by [FakeIntegrity] and the
 * service by [AttestApi]: no real Play Integrity or App Attest request is made here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttestationTest {
    private val integrity = FakeIntegrity()

    private fun TestScope.api(): AttestApi = AttestApi { wallClockOf(this) }

    private fun TestScope.attested(
        api: AttestApi,
        attestor: Attestor? = IntegrityAttestor(integrity),
        maxRetries: Int = 2,
        timeout: Duration = 60.milliseconds,
        errors: MutableList<StrybokException>? = null,
        source: LinkSource = FakeLinkSource(),
    ): Strybok = sdk(
        api, source = source, attestor = attestor, maxRetries = maxRetries, timeout = timeout, errors = errors,
    )

    private fun invalid(reason: String) = errorAnswer(401, "ATTESTATION_INVALID", """{"reason":"$reason"}""")

    // --- hashing -----------------------------------------------------------------------------

    @Test
    fun sha256MatchesTheStandardTestVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(ByteArray(0)).hex())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).hex(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).hex(),
        )
    }

    @Test
    fun requestHashAndClientDataHashMatchTheContract() {
        // The example of the contract: challenge `abc`.
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", requestHashOf("abc"))
        assertEquals(43, requestHashOf("x".repeat(43)).length)
        assertEquals(32, clientDataHashOf("abc").size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", clientDataHashOf("abc").hex())
        // UTF-8, not UTF-16.
        assertEquals(sha256(byteArrayOf(0xc3.toByte(), 0xa9.toByte())).hex(), clientDataHashOf("é").hex())
    }

    // --- off by default ------------------------------------------------------------------------

    @Test
    fun attestationIsOffByDefaultAndThenNothingIsAttestedOrSent() = runTest {
        val api = api()
        // The public constructor, with a provider but without `attestation = true`.
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api, clientKey = CLIENT_KEY,
            integrityTokenProvider = integrity, coroutineContext = StandardTestDispatcher(testScheduler),
        )
        strybok.createLink("/product/42")
        strybok.createLink("/product/43")

        assertEquals(listOf("/v1/public/links", "/v1/public/links"), api.all.map { it.path })
        assertTrue(api.creates.none { ATTESTATION_HEADER in it.headers })
        assertTrue(integrity.hashes.isEmpty())
        strybok.dispose()
    }

    @Test
    fun nothingIsAttestedByStartIncomingLinksOrResolve() = runTest {
        val api = api()
        val source = FakeLinkSource(initialLink = INVITE)
        val strybok = attested(api, source = source)
        val received = collectLinks(strybok)
        strybok.start()
        source.emit("https://$HOST/other")
        strybok.resolve(INVITE)
        advanceUntilIdle()

        assertEquals(2, received.size)
        assertTrue(api.challenges.isEmpty() && api.exchanges.isEmpty() && api.creates.isEmpty())
        assertTrue(integrity.hashes.isEmpty())
        strybok.dispose()
    }

    // --- flow ----------------------------------------------------------------------------------

    @Test
    fun theFirstCreateLinkAsksForAChallengeAProofAndATokenThenSendsTheToken() = runTest {
        val api = api()
        val link = attested(api, timeout = 70.milliseconds).createLink("/product/42", mapOf("productId" to 42))
        assertEquals("https://$HOST/Ab3dE9x", link.url)

        assertEquals(
            listOf("/v1/public/attest/challenge", "/v1/public/attest/android", "/v1/public/links"),
            api.all.map { it.path },
        )
        val challenge = api.challenges.single()
        assertEquals("POST", challenge.method)
        assertNull(challenge.body)
        assertEquals(mapOf("Accept" to "application/json", "X-Strybok-Key" to CLIENT_KEY), challenge.headers)
        assertEquals(70.milliseconds, challenge.timeout)

        assertEquals(listOf(requestHashOf(api.challengeNo(1))), integrity.hashes)

        val exchange = api.exchanges.single()
        assertEquals("POST", exchange.method)
        assertEquals(
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "X-Strybok-Key" to CLIENT_KEY),
            exchange.headers,
        )
        assertEquals(setOf("integrityToken", "challenge"), exchange.json.keys)
        assertEquals(api.challengeNo(1), exchange.json.text("challenge"))
        assertEquals(integrity.tokenFor(integrity.hashes.single()), exchange.json.text("integrityToken"))
        assertEquals(70.milliseconds, exchange.timeout)

        val create = api.creates.single()
        assertEquals(api.tokenNo(1), create.attestation)
        assertEquals(CLIENT_KEY, create.headers["X-Strybok-Key"])
        assertEquals("""{"routePath":"/product/42","payload":{"productId":42}}""", create.body)
    }

    // --- token cache ---------------------------------------------------------------------------

    @Test
    fun laterCallsReuseTheTokenWithoutAttestingAgain() = runTest {
        val api = api()
        val strybok = attested(api)
        repeat(3) { strybok.createLink("/product/$it") }
        delay(30.minutes)
        strybok.createLink("/product/later")

        assertEquals(1, api.challenges.size)
        assertEquals(1, api.exchanges.size)
        assertEquals(1, integrity.hashes.size)
        assertEquals(List(4) { api.tokenNo(1) }, api.creates.map { it.attestation })
    }

    @Test
    fun theTokenIsUsedUntil60SecondsBeforeItExpiresThenANewOneIsMade() = runTest {
        val api = api()
        val strybok = attested(api)
        strybok.createLink("/product/1")
        assertEquals(0, currentTime) // The fakes answer at once, so the token expires at exactly one hour.

        delay(1.hours - 60.seconds - 1.milliseconds)
        strybok.createLink("/product/2")
        assertEquals(1, api.exchanges.size)

        delay(1.milliseconds) // Exactly 60 seconds before expiresAt.
        strybok.createLink("/product/3")
        assertEquals(2, api.challenges.size)
        assertEquals(2, api.exchanges.size)
        assertEquals(listOf(api.tokenNo(1), api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
        assertEquals(requestHashOf(api.challengeNo(2)), integrity.hashes.last())
    }

    @Test
    fun aWallClockThatJumpsDoesNotKeepAnExpiredTokenAlive() = runTest {
        var offset = Duration.ZERO
        val wall = { wallClockOf(this) + offset }
        val api = AttestApi(wall)
        val strybok = sdk(api, attestor = IntegrityAttestor(integrity), wallClock = wall)
        strybok.createLink("/product/1")

        offset = (-5).hours // Set back by the user: the monotonic clock still counts.
        delay(1.hours)
        strybok.createLink("/product/2")
        assertEquals(2, api.exchanges.size)

        offset += 10.hours // Set forward: past the service's expiresAt.
        strybok.createLink("/product/3")
        assertEquals(3, api.exchanges.size)
    }

    @Test
    fun aTokenThatLooksExpiredToThisDeviceIsUsedForTheCallItWasMadeForOnly() = runTest {
        val api = api()
        api.onExchange = { _, n -> api.grant(n, expiresAt = wallClockOf(this) - 1.hours) } // Device clock far ahead.
        val strybok = attested(api)
        strybok.createLink("/product/1")
        strybok.createLink("/product/2")

        assertEquals(listOf(api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
    }

    @Test
    fun concurrentCallsShareOneAttestation() = runTest {
        val api = api()
        val gate = CompletableDeferred<Unit>()
        integrity.answer = { hash ->
            gate.await()
            integrity.tokenFor(hash)
        }
        val strybok = attested(api)
        val calls = List(8) { async { strybok.createLink("/product/$it") } }
        runCurrent()
        assertEquals(1, api.challenges.size)
        assertTrue(api.creates.isEmpty())
        gate.complete(Unit)
        calls.awaitAll()

        assertEquals(1, api.challenges.size)
        assertEquals(1, api.exchanges.size)
        assertEquals(1, integrity.hashes.size)
        assertEquals(List(8) { api.tokenNo(1) }, api.creates.map { it.attestation })
    }

    @Test
    fun aCallerThatGivesUpDoesNotCancelTheAttestationOfTheOthers() = runTest {
        val api = api()
        val gate = CompletableDeferred<Unit>()
        integrity.answer = { hash ->
            gate.await()
            integrity.tokenFor(hash)
        }
        val strybok = attested(api)
        val first = async { strybok.createLink("/product/1") }
        runCurrent()
        val second = async { strybok.createLink("/product/2") }
        runCurrent()
        first.cancel()
        runCurrent()
        gate.complete(Unit)

        assertEquals("link_9", second.await().linkId)
        assertEquals(1, api.exchanges.size)
        assertEquals(listOf(api.tokenNo(1)), api.creates.map { it.attestation })
    }

    @Test
    fun eachInstanceHasItsOwnToken() = runTest {
        val api = api()
        attested(api).createLink("/product/1")
        attested(api).createLink("/product/1")
        assertEquals(listOf(api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
    }

    // --- ATTESTATION_INVALID: attest again, once -------------------------------------------------

    @Test
    fun aRefusedTokenIsReplacedAndTheSameRequestIsSentAgainOnce() = runTest {
        for (reason in listOf("expired", "invalid", "exhausted")) {
            val api = api()
            api.onCreate = { if (it.attestation == api.tokenNo(1)) invalid(reason) else created() }
            // maxRetries is 0: attesting again does not count against it.
            val strybok = attested(api, maxRetries = 0)

            val link = strybok.createLink("/product/42", mapOf("productId" to 42), title = "Red shoes")
            assertEquals("link_9", link.linkId)

            assertEquals(listOf(api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation }, reason)
            assertEquals(api.creates[0].body, api.creates[1].body)
            assertEquals(2, api.challenges.size)
            assertNotEquals(api.exchanges[0].json.text("challenge"), api.exchanges[1].json.text("challenge"))

            // The new token is the cached one from now on.
            strybok.createLink("/product/43")
            assertEquals(api.tokenNo(2), api.creates.last().attestation)
            assertEquals(2, api.exchanges.size)
        }
    }

    @Test
    fun aFreshTokenThatIsRefusedAsWellFailsWithAttestationFailed() = runTest {
        val api = api()
        api.onCreate = { invalid(if (it.attestation == api.tokenNo(1)) "expired" else "exhausted") }
        val strybok = attested(api)

        val error = assertFailsWith<StrybokAttestationFailedException> { strybok.createLink("/product/42") }
        assertEquals("exhausted", error.reason)
        assertNull(error.url)
        assertEquals(2, api.creates.size)
        assertEquals(2, api.exchanges.size)

        // The next call starts over rather than reusing a refused token.
        api.onCreate = { created() }
        strybok.createLink("/product/42")
        assertEquals(api.tokenNo(3), api.creates.last().attestation)
    }

    @Test
    fun whenAttestingAgainIsNotPossibleTheCallFailsWithAttestationFailed() = runTest {
        val api = api()
        api.onCreate = { invalid("expired") }
        integrity.answer = { hash -> if (integrity.hashes.size == 1) integrity.tokenFor(hash) else null }

        val error = assertFailsWith<StrybokAttestationFailedException> { attested(api).createLink("/product/42") }
        assertEquals("expired", error.reason)
        assertEquals(1, api.creates.size) // Nothing is sent without a token after a refusal.
        assertEquals(2, api.challenges.size)
    }

    @Test
    fun callsRefusedTogetherAttestAgainTogether() = runTest {
        val api = api()
        val strybok = attested(api)
        strybok.createLink("/product/0") // Token 1 is cached.
        api.onCreate = {
            if (it.attestation == api.tokenNo(1)) {
                delay(10.milliseconds) // Both are on their way before either is refused.
                invalid("exhausted")
            } else {
                created()
            }
        }
        List(2) { async { strybok.createLink("/product/${it + 1}") } }.awaitAll()

        assertEquals(2, api.exchanges.size) // One for token 1, one shared for token 2.
        assertEquals(listOf(api.tokenNo(2), api.tokenNo(2)), api.creates.takeLast(2).map { it.attestation })
    }

    @Test
    fun theReasonIsOnlyPassedOnWhenItIsOneOfTheServicesWords() = runTest {
        for ((details, expected) in listOf(
            """{"reason":"expired"}""" to "expired",
            """{}""" to null,
            """{"reason":42}""" to null,
            """{"reason":"sat1.session-token-2.signature"}""" to null,
            """{"reason":"Something long and free-form that is not a reason word"}""" to null,
        )) {
            val api = api()
            api.onCreate = { errorAnswer(401, "ATTESTATION_INVALID", details) }
            val error = assertFailsWith<StrybokAttestationFailedException> { attested(api).createLink("/a") }
            assertEquals(expected, error.reason, details)
        }
    }

    // --- cannot attest: send without the header, report the service's answer ----------------------

    private class Case(val name: String, val attestor: Attestor?, val arrange: AttestApi.() -> Unit = {})

    private fun unavailableCases(): List<Case> {
        fun provider(answer: suspend (String) -> String?) =
            IntegrityAttestor(FakeIntegrity().also { it.answer = answer })
        val working = IntegrityAttestor(FakeIntegrity())
        fun service(name: String, arrange: AttestApi.() -> Unit) = Case(name, working, arrange)
        return listOf(
            Case("the platform cannot attest", null),
            Case("provider returns null", provider { null }),
            Case("provider returns an empty token", provider { "" }),
            Case("provider throws", provider { throw IllegalStateException("PLAY_STORE_NOT_FOUND") }),
            Case("provider throws an error", provider { throw NotImplementedError("no Play library") }),
            service("challenge: 503 not configured") { onChallenge = { errorAnswer(503, "ATTESTATION_NOT_CONFIGURED") } },
            service("challenge: 429") { onChallenge = { errorAnswer(429, "RATE_LIMITED") } },
            service("challenge: 500") { onChallenge = { HttpResponse(500, "") } },
            service("challenge: 404") { onChallenge = { HttpResponse(404, "") } },
            service("challenge: network") { onChallenge = { throw IllegalStateException("offline") } },
            service("challenge: transport timeout") { onChallenge = { throw HttpTimeoutException("slow") } },
            service("challenge: no answer in time") { onChallenge = { awaitCancellation() } },
            service("challenge: not JSON") { onChallenge = { HttpResponse(200, "<html>") } },
            service("challenge: no challenge") { onChallenge = { HttpResponse(200, """{"challenge":7}""") } },
            service("exchange: 403 refused") {
                onExchange = { _, _ -> errorAnswer(403, "ATTESTATION_FAILED", """{"reason":"app_not_recognized"}""") }
            },
            service("exchange: 409 not configured") {
                onExchange = { _, _ -> errorAnswer(409, "ATTESTATION_NOT_CONFIGURED", """{"scope":"application"}""") }
            },
            service("exchange: 429") { onExchange = { _, _ -> errorAnswer(429, "RATE_LIMITED") } },
            service("exchange: 503 unavailable") {
                onExchange = { _, _ -> errorAnswer(503, "ATTESTATION_UNAVAILABLE", """{"reason":"quota"}""") }
            },
            service("exchange: 422") { onExchange = { _, _ -> errorAnswer(422, "VALIDATION_ERROR") } },
            service("exchange: network") { onExchange = { _, _ -> throw IllegalStateException("offline") } },
            service("exchange: no answer in time") { onExchange = { _, _ -> awaitCancellation() } },
            service("exchange: no token") {
                onExchange = { _, _ -> HttpResponse(200, """{"expiresAt":"2026-11-24T11:00:00Z"}""") }
            },
            service("exchange: token is not a string") {
                onExchange = { _, _ -> HttpResponse(200, """{"token":1,"expiresAt":"2026-11-24T11:00:00Z"}""") }
            },
            service("exchange: no expiresAt") { onExchange = { _, _ -> HttpResponse(200, """{"token":"sat1.a.b"}""") } },
            service("exchange: unreadable expiresAt") {
                onExchange = { _, _ -> HttpResponse(200, """{"token":"sat1.a.b","expiresAt":"soon"}""") }
            },
            service("exchange: token unfit for a header") {
                onExchange = { _, _ -> HttpResponse(200, """{"token":"sat1 a\nb","expiresAt":"2026-11-24T11:00:00Z"}""") }
            },
        )
    }

    @Test
    fun aDeviceThatCannotAttestSendsTheRequestWithoutATokenAndGetsTheServicesAnswer() = runTest {
        for (case in unavailableCases()) {
            val api = api().apply(case.arrange)
            val strybok = attested(api, attestor = case.attestor)

            // Mode "off" or "preferred": served.
            assertEquals("link_9", strybok.createLink("/product/42").linkId, case.name)
            // Mode "required": refused, and that is what the app sees.
            api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED") }
            assertFailsWith<StrybokAttestationRequiredException>(case.name) { strybok.createLink("/product/42") }

            assertEquals(2, api.creates.size, case.name)
            assertTrue(api.creates.none { ATTESTATION_HEADER in it.headers }, case.name)
            // Attestation requests are never retried, and not repeated for the second call.
            assertTrue(api.challenges.size <= 1 && api.exchanges.size <= 1, case.name)
        }
    }

    @Test
    fun whenThePlatformCannotAttestNoAttestationRequestIsMadeAtAll() = runTest {
        val api = api()
        attested(api, attestor = null).createLink("/product/42")
        assertEquals(listOf("/v1/public/links"), api.all.map { it.path })
    }

    @Test
    fun afterAFailedAttemptTheNextOneWaits60Seconds() = runTest {
        val api = api()
        integrity.answer = { null }
        val strybok = attested(api)
        strybok.createLink("/product/1")
        assertEquals(1, api.challenges.size)

        delay(60.seconds - 1.milliseconds)
        strybok.createLink("/product/2")
        assertEquals(1, api.challenges.size)
        assertEquals(1, integrity.hashes.size)

        delay(1.milliseconds)
        integrity.answer = { hash -> integrity.tokenFor(hash) }
        strybok.createLink("/product/3")
        assertEquals(2, api.challenges.size)
        assertEquals(listOf(null, null, api.tokenNo(1)), api.creates.map { it.attestation })
    }

    @Test
    fun aProviderThatNeverAnswersIsGivenUpOnAfter30Seconds() = runTest {
        val api = api()
        integrity.answer = { awaitCancellation() }
        val link = attested(api).createLink("/product/42")

        assertEquals("link_9", link.linkId)
        assertEquals(30_000, currentTime)
        assertNull(api.creates.single().attestation)
        assertTrue(api.exchanges.isEmpty())
    }

    @Test
    fun disposeWhileAttestingEndsTheCallWithoutSendingAnything() = runTest {
        val api = api()
        integrity.answer = { awaitCancellation() }
        val strybok = attested(api)
        val call = async { runCatching { strybok.createLink("/product/42") } }
        runCurrent()
        strybok.dispose()

        assertTrue(call.await().exceptionOrNull() is IllegalStateException)
        assertTrue(api.creates.isEmpty())
    }

    // --- answers of the attestation endpoints: 401 -------------------------------------------------

    @Test
    fun aClientKeyRefusedByAnAttestationEndpointFailsWithUnauthorized() = runTest {
        val api = api()
        api.onChallenge = { errorAnswer(401, "UNAUTHENTICATED") }
        val strybok = attested(api)
        assertFailsWith<StrybokUnauthorizedException> { strybok.createLink("/product/42") }
        assertTrue(api.creates.isEmpty())
        assertTrue(integrity.hashes.isEmpty())

        // Not "cannot attest": the next call asks again instead of pausing.
        api.onChallenge = api.defaultChallenge
        api.onExchange = { _, _ -> errorAnswer(401, "UNAUTHENTICATED") }
        assertFailsWith<StrybokUnauthorizedException> { strybok.createLink("/product/42") }
        assertEquals(2, api.challenges.size)
        assertTrue(api.creates.isEmpty())
    }

    // --- createLink answers that concern attestation -----------------------------------------------

    @Test
    fun attestationRequiredIsItsOwnErrorAlsoWithAttestationSwitchedOff() = runTest {
        val api = api()
        api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED") }
        val error = assertFailsWith<StrybokAttestationRequiredException> {
            attested(api, attestor = null).createLink("/product/42")
        }
        assertNull(error.url)
        assertEquals(1, api.creates.size) // Not retried.
        assertTrue(api.challenges.isEmpty())
    }

    @Test
    fun other401AnswersStayUnauthorized() = runTest {
        val api = api()
        for (body in listOf(
            """{"error":{"code":"UNAUTHENTICATED","message":"Authentication is required"}}""",
            """{"error":{"code":"SOMETHING_NEW"}}""",
            """{"error":"nope"}""",
            "",
            "<html>",
        )) {
            api.onCreate = { HttpResponse(401, body) }
            assertFailsWith<StrybokUnauthorizedException>(body) { attested(api).createLink("/product/42") }
        }
        // One attestation per instance, no second one because of the 401.
        assertEquals(5, api.exchanges.size)
        assertEquals(5, api.creates.size)
    }

    @Test
    fun attestationInvalidWithoutATokenToReplaceIsAttestationFailedAndIsNotRetried() = runTest {
        val api = api()
        api.onCreate = { invalid("invalid") }
        val error = assertFailsWith<StrybokAttestationFailedException> {
            attested(api, attestor = null).createLink("/product/42")
        }
        assertEquals("invalid", error.reason)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun rateLimitedForTheUnattestedAllowanceIsRateLimitedLikeEvery429() = runTest {
        val api = api()
        integrity.answer = { null }
        api.onCreate = {
            HttpResponse(
                429,
                """{"error":{"code":"RATE_LIMITED","message":"Daily limit","details":{"scope":"unattested"}}}""",
                mapOf("Retry-After" to "120"),
            )
        }
        val error = assertFailsWith<StrybokRateLimitedException> { attested(api).createLink("/product/42") }
        assertEquals(120L, error.retryAfterSeconds)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun notConfigured503IsAServerErrorAndIsRetriedLikeAny5xx() = runTest {
        val api = api()
        api.onCreate = { errorAnswer(503, "ATTESTATION_NOT_CONFIGURED", """{"scope":"service"}""") }
        val error = assertFailsWith<StrybokServerException> { attested(api, maxRetries = 2).createLink("/product/42") }
        assertEquals(503, error.statusCode)
        assertEquals(List(3) { api.tokenNo(1) }, api.creates.map { it.attestation })
        assertEquals(1, api.exchanges.size)
    }

    @Test
    fun a5xxIsRetriedWithTheSameTokenAndBodyBeforeAndAfterAttestingAgain() = runTest {
        val api = api()
        var answers = 0
        api.onCreate = {
            when (++answers) {
                1, 3 -> HttpResponse(502, "")
                2 -> invalid("expired")
                else -> created()
            }
        }
        attested(api, maxRetries = 1).createLink("/product/42")
        assertEquals(
            listOf(api.tokenNo(1), api.tokenNo(1), api.tokenNo(2), api.tokenNo(2)),
            api.creates.map { it.attestation },
        )
        assertEquals(1, api.creates.map { it.body }.distinct().size)
    }

    // --- errors and incoming links -------------------------------------------------------------------

    @Test
    fun errorsNeverContainTheClientKeyATokenAProofTheChallengeOrThePayload() = runTest {
        val api = api()
        val errors = mutableListOf<StrybokException>()
        suspend fun collect(strybok: Strybok) {
            try {
                strybok.createLink("/product/42", mapOf("secret" to PAYLOAD_SECRET))
            } catch (e: StrybokException) {
                errors += e
            }
        }
        val leaky = """{"reason":"${api.tokenNo(1)}","token":"${api.tokenNo(1)}","key":"$CLIENT_KEY"}"""
        api.onCreate = { errorAnswer(401, "ATTESTATION_INVALID", leaky) }
        collect(attested(api))
        api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED", leaky) }
        collect(attested(api))
        api.onCreate = { errorAnswer(401, "UNAUTHENTICATED", leaky) }
        collect(attested(api))
        api.onCreate = { errorAnswer(503, "ATTESTATION_NOT_CONFIGURED", leaky) }
        collect(attested(api, maxRetries = 0))
        api.onCreate = { created() }
        api.onExchange = { _, _ -> errorAnswer(401, "UNAUTHENTICATED", leaky) }
        collect(attested(api))

        assertEquals(
            listOf(
                StrybokAttestationFailedException::class, StrybokAttestationRequiredException::class,
                StrybokUnauthorizedException::class, StrybokServerException::class, StrybokUnauthorizedException::class,
            ),
            errors.map { it::class },
        )
        val secrets = listOf(CLIENT_KEY, PAYLOAD_SECRET, "sat1.", "session-token", "integrity.token", "challenge-")
        for (error in errors) {
            val text = listOf(
                error.message, error.toString(), error.url, error.cause?.toString(),
                (error as? StrybokAttestationFailedException)?.reason,
            ).joinToString(" ")
            for (secret in secrets) assertFalse(text.contains(secret), "$text contains $secret")
        }
    }

    @Test
    fun incomingLinksAreDeliveredWhateverAttestationDoesAndItsErrorsNeverReachOnError() = runTest {
        val api = api()
        api.onChallenge = { throw IllegalStateException("offline") }
        api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED") }
        val incoming = mutableListOf<StrybokException>()
        val source = FakeLinkSource(initialLink = INVITE)
        val strybok = attested(api, errors = incoming, source = source)
        val received = collectLinks(strybok)

        assertFailsWith<StrybokAttestationRequiredException> { strybok.createLink("/product/42") }
        strybok.start()
        source.emit("https://$HOST/other")
        advanceUntilIdle()

        assertEquals(listOf(INVITE, "https://$HOST/other"), received.map { it.url })
        assertTrue(incoming.isEmpty())
        strybok.dispose()
    }
}
