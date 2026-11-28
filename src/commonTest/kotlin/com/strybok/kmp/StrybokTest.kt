package com.strybok.kmp

import com.strybok.kmp.testing.FakeLinkSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class StrybokTest {
    // --- resolve ---------------------------------------------------------------------------

    @Test
    fun resolveRefusesForeignLinksWithoutAnyNetworkCall() = runTest {
        val api = FakeApi()
        val error = assertFailsWith<UnsupportedLinkException> {
            sdk(api).resolve("https://evil.example.com/invite777")
        }
        assertEquals("https://evil.example.com/invite777", error.url)
        assertTrue(api.resolves.isEmpty())
    }

    @Test
    fun resolveReturnsRouteAndPayloadAndSendsOnlyHostAndSlugToTheApi() = runTest {
        val api = FakeApi()
        val link = sdk(api).resolve("https://MY-APP.strybok.link/invite777?token=secret#f")
        advanceUntilIdle()

        assertEquals("/invite/abc123", link.routePath)
        assertEquals(mapOf<String, Any?>("invitationId" to "abc123"), link.payload)
        assertEquals("""{"invitationId":"abc123"}""", link.payloadJson)
        assertEquals("link_1", link.linkId)
        assertEquals("app_1", link.appId)
        assertEquals("https://MY-APP.strybok.link/invite777?token=secret#f", link.url)

        val request = api.resolves.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://api.strybok.com/v1/public/resolve?url=https%3A%2F%2Fmy-app.strybok.link%2Finvite777",
            request.url,
        )
        assertEquals(mapOf("Accept" to "application/json"), request.headers)
        assertNull(request.body)
        assertFalse("secret" in request.url)
        assertTrue(api.events.isEmpty(), "explicit resolution is not an app open")
    }

    @Test
    fun keepsAPathPrefixOfTheApiBaseUrl() = runTest {
        val api = FakeApi()
        sdk(api, apiBaseUrl = "https://example.com/strybok/").resolve(INVITE)
        assertTrue(api.resolves.single().url.startsWith("https://example.com/strybok/v1/public/resolve?url="))
    }

    @Test
    fun notFoundBecomesLinkNotFoundExceptionAndIsNotRetried() = runTest {
        val api = FakeApi { HttpResponse(404, """{"error":{"code":"LINK_NOT_FOUND"}}""") }
        val error = assertFailsWith<LinkNotFoundException> { sdk(api).resolve(INVITE) }
        assertEquals(INVITE, error.url)
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun otherClientErrorsAreNotRetried() = runTest {
        val api = FakeApi { HttpResponse(422, "{}") }
        val error = assertFailsWith<StrybokServerException> { sdk(api).resolve(INVITE) }
        assertEquals(422, error.statusCode)
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun retriesNetworkErrorsAndThenSucceeds() = runTest {
        var calls = 0
        val api = FakeApi {
            if (++calls < 3) throw RuntimeException("connection reset")
            ok()
        }
        assertEquals("/invite/abc123", sdk(api).resolve(INVITE).routePath)
        assertEquals(3, calls)
    }

    @Test
    fun givesUpWithANetworkExceptionAfterMaxRetries() = runTest {
        val cause = RuntimeException("offline")
        val api = FakeApi { throw cause }
        val error = assertFailsWith<StrybokNetworkException> { sdk(api, maxRetries = 1).resolve(INVITE) }
        // Not assertSame: coroutine stack-trace recovery may wrap the original on the JVM.
        assertEquals("offline", error.cause?.message)
        assertEquals(INVITE, error.url)
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun retriesServerErrors() = runTest {
        var calls = 0
        val api = FakeApi { if (++calls == 1) HttpResponse(503, "") else ok() }
        sdk(api).resolve(INVITE)
        assertEquals(2, calls)
    }

    @Test
    fun reportsTheLastServerErrorAfterAllRetries() = runTest {
        val api = FakeApi { HttpResponse(503, "") }
        val error = assertFailsWith<StrybokServerException> { sdk(api).resolve(INVITE) }
        assertEquals(503, error.statusCode)
        assertEquals(3, api.resolves.size)
    }

    @Test
    fun timesOutWithATypedException() = runTest {
        val api = FakeApi { awaitCancellation() }
        val error = assertFailsWith<StrybokTimeoutException> { sdk(api, maxRetries = 1).resolve(INVITE) }
        assertEquals(INVITE, error.url)
        assertEquals(2, api.resolves.size)
        assertEquals(60 + 1 + 60L, currentTime, "two attempts of 60 ms and one pause of 1 ms")
    }

    @Test
    fun aTimeoutReportedByTheTransportIsATimeoutToo() = runTest {
        val api = FakeApi { throw HttpTimeoutException("read timed out") }
        assertFailsWith<StrybokTimeoutException> { sdk(api, maxRetries = 1).resolve(INVITE) }
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun retryDelayDoublesForEachFurtherRetry() = runTest {
        val times = mutableListOf<Long>()
        val api = FakeApi {
            times += currentTime
            HttpResponse(500, "")
        }
        assertFailsWith<StrybokServerException> {
            sdk(api, maxRetries = 3, retryDelay = 400.milliseconds).resolve(INVITE)
        }
        assertEquals(listOf(0L, 400L, 1200L, 2800L), times)
    }

    @Test
    fun maxRetriesZeroMeansASingleAttempt() = runTest {
        val api = FakeApi { HttpResponse(500, "") }
        assertFailsWith<StrybokServerException> { sdk(api, maxRetries = 0).resolve(INVITE) }
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun cancellingTheCallerCancelsResolveInsteadOfReportingAnError() = runTest {
        val api = FakeApi { awaitCancellation() }
        val instance = sdk(api, timeout = 5.seconds)
        var failure: Throwable? = null
        val job = launch {
            try {
                instance.resolve(INVITE)
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertIs<CancellationException>(failure)
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun rejectsMalformedOrUnsafeResponses() = runTest {
        for (body in listOf(
            "",
            "not json",
            "[]",
            "null",
            "\"text\"",
            """{"linkId":"l","appId":"a"}""",
            """{"linkId":"l","appId":"a","routePath":"https://evil.example.com"}""",
            """{"linkId":"l","appId":"a","routePath":"//evil.example.com"}""",
            """{"linkId":"l","appId":"a","routePath":"x"}""",
            """{"linkId":"l","appId":"a","routePath":""}""",
            """{"linkId":1,"appId":"a","routePath":"/x"}""",
            """{"linkId":"l","appId":null,"routePath":"/x"}""",
            """{"linkId":"l","appId":"a","routePath":["/x"]}""",
        )) {
            val api = FakeApi { HttpResponse(200, body) }
            val error = assertFailsWith<StrybokServerException>(body) { sdk(api).resolve(INVITE) }
            assertEquals(200, error.statusCode, body)
            assertEquals(1, api.resolves.size, "a malformed answer is not retried: $body")
        }
    }

    @Test
    fun payloadIsEmptyWhenMissingOrNotAnObject() = runTest {
        for (payload in listOf("", ""","payload":null""", ""","payload":[1,2]""", ""","payload":"text"""")) {
            val api = FakeApi { HttpResponse(200, """{"linkId":"l","appId":"a","routePath":"/x"$payload}""") }
            val link = sdk(api).resolve(INVITE)
            assertEquals(emptyMap(), link.payload, payload)
            assertEquals("{}", link.payloadJson, payload)
        }
    }

    @Test
    fun payloadIsDecodedToPlainValues() = runTest {
        val api = FakeApi {
            HttpResponse(
                200,
                """{"linkId":"l","appId":"a","routePath":"/x","payload":""" +
                    """{"s":"1","n":42,"d":1.5,"b":true,"z":null,"list":[1,"a",{"k":false}],"o":{"deep":{"x":-7}}}}""",
            )
        }
        assertEquals(
            mapOf(
                "s" to "1",
                "n" to 42L,
                "d" to 1.5,
                "b" to true,
                "z" to null,
                "list" to listOf(1L, "a", mapOf("k" to false)),
                "o" to mapOf("deep" to mapOf("x" to -7L)),
            ),
            sdk(api).resolve(INVITE).payload,
        )
    }

    @Test
    fun configurationIsValidated() = runTest {
        assertFailsWith<IllegalArgumentException> { sdk(FakeApi(), apiBaseUrl = "api.strybok.com") }
        assertFailsWith<IllegalArgumentException> { sdk(FakeApi(), maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> { sdk(FakeApi(), timeout = 0.seconds) }
    }

    @Test
    fun publicConstructorsUseTheContractDefaults() {
        val errors = mutableListOf<StrybokException>()
        for (sdk in listOf(Strybok(API, setOf(HOST)), Strybok(API, setOf(HOST), onError = { errors += it }))) {
            assertEquals(5.seconds, sdk.timeout)
            assertEquals(2, sdk.maxRetries)
            assertEquals(400.milliseconds, sdk.retryDelay)
            assertEquals(2.seconds, sdk.dedupeWindow)
            assertTrue(sdk.reportAppOpens)
            assertEquals(API, sdk.apiBaseUrl)
            sdk.dispose()
        }
        assertEquals("0.5.0", STRYBOK_SDK_VERSION)
    }

    // --- cold start ------------------------------------------------------------------------

    @Test
    fun deliversTheLaunchLinkOnceEvenIfTheListenerAttachesLate() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = FakeLinkSource(initialLink = INVITE))
        sdk.start()
        advanceUntilIdle()

        val received = collectLinks(sdk)
        val second = collectLinks(sdk)
        advanceUntilIdle()

        assertEquals(listOf("/invite/abc123"), received.map { it.routePath })
        assertTrue(second.isEmpty(), "a queued link goes to the first listener only")
        assertEquals(1, api.resolves.size)
        assertEquals(1, api.events.size, "the app open is reported when the link is queued")
        sdk.dispose()
    }

    @Test
    fun deduplicatesALaunchLinkThatIsReplayedOnTheStream() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = FakeLinkSource(initialLink = INVITE, replayInitialLinkOnStream = true))
        val received = collectLinks(sdk)
        sdk.start()
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(1, api.resolves.size)
        assertEquals(1, api.events.size)
        sdk.dispose()
    }

    @Test
    fun getInitialLinkConsumesTheLaunchLinkSoTheStreamDoesNotRepeatIt() = runTest {
        val sdk = sdk(FakeApi(), source = FakeLinkSource(initialLink = INVITE))
        assertEquals("/invite/abc123", sdk.getInitialLink()?.routePath)
        assertNull(sdk.getInitialLink())

        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertTrue(received.isEmpty())
        sdk.dispose()
    }

    @Test
    fun getInitialLinkReturnsNullOnceTheStreamAlreadyDeliveredIt() = runTest {
        val sdk = sdk(FakeApi(), source = FakeLinkSource(initialLink = INVITE))
        val received = collectLinks(sdk)
        sdk.start()
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertNull(sdk.getInitialLink())
        sdk.dispose()
    }

    @Test
    fun getInitialLinkReturnsNullWhenALateListenerAlreadyReceivedTheQueuedLink() = runTest {
        val sdk = sdk(FakeApi(), source = FakeLinkSource(initialLink = INVITE))
        sdk.start()
        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertNull(sdk.getInitialLink())
        sdk.dispose()
    }

    @Test
    fun getInitialLinkLeavesLaterLinksInTheQueue() = runTest {
        val source = FakeLinkSource(initialLink = INVITE)
        val sdk = sdk(FakeApi(), source = source)
        sdk.start()
        source.emit("https://$HOST/later")
        advanceUntilIdle()

        assertEquals(INVITE, sdk.getInitialLink()?.url)
        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertEquals(listOf("https://$HOST/later"), received.map { it.url })
        sdk.dispose()
    }

    @Test
    fun noLaunchLinkForeignLaunchLinkAndRepeatedStartAreHarmless() = runTest {
        val api = FakeApi()
        val errors = mutableListOf<StrybokException>()
        val sdk = sdk(api, source = FakeLinkSource(initialLink = "https://other.example.com/x"), errors = errors)
        listOf(async { sdk.start() }, async { sdk.start() }).awaitAll()
        sdk.start()
        assertNull(sdk.getInitialLink())
        assertTrue(api.resolves.isEmpty())
        assertTrue(errors.isEmpty())

        val plain = sdk(api)
        plain.start()
        assertNull(plain.getInitialLink())
        sdk.dispose()
        plain.dispose()
    }

    @Test
    fun aFailingLaunchLinkIsReportedThroughOnErrorAndStartCompletes() = runTest {
        val errors = mutableListOf<StrybokException>()
        val sdk = sdk(FakeApi { HttpResponse(404, "") }, source = FakeLinkSource(initialLink = INVITE), errors = errors)
        sdk.start()
        assertIs<LinkNotFoundException>(errors.single())
        assertEquals(INVITE, errors.single().url)
        assertNull(sdk.getInitialLink())
        sdk.dispose()
    }

    @Test
    fun aLinkSourceThatCannotTellTheLaunchLinkIsTreatedAsNoLaunchLink() = runTest {
        val broken = object : LinkSource {
            val delegate = FakeLinkSource()
            override suspend fun getInitialLink(): String? = throw RuntimeException("platform error")
            override val links get() = delegate.links
        }
        val errors = mutableListOf<StrybokException>()
        val sdk = sdk(FakeApi(), source = broken, errors = errors)
        val received = collectLinks(sdk)
        sdk.start()
        assertNull(sdk.getInitialLink())

        broken.delegate.emit(INVITE)
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertTrue(errors.isEmpty())
        sdk.dispose()
    }

    // --- running app -----------------------------------------------------------------------

    @Test
    fun deliversEachForegroundLinkExactlyOnce() = runTest {
        val api = FakeApi { request ->
            val slug = request.url.substringAfterLast("%2F")
            ok(route = "/p/$slug", linkId = "link_$slug")
        }
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source)
        val received = collectLinks(sdk)
        sdk.start()

        source.emit("https://$HOST/first")
        source.emit("https://$HOST/second")
        source.emit("https://evil.example.com/third")
        advanceUntilIdle()

        assertEquals(setOf("/p/first", "/p/second"), received.map { it.routePath }.toSet())
        assertEquals(2, received.size)
        assertEquals(2, api.resolves.size)
        sdk.dispose()
    }

    @Test
    fun dropsAnImmediateDuplicateButHonoursTheSameLinkTappedAgainLater() = runTest {
        val source = FakeLinkSource()
        val api = FakeApi()
        val sdk = sdk(api, source = source)
        val received = collectLinks(sdk)
        sdk.start()

        source.emit(INVITE)
        source.emit(INVITE)
        runCurrent()
        assertEquals(1, received.size)

        // Still inside the window.
        advanceTimeBy(1900.milliseconds)
        source.emit(INVITE)
        runCurrent()
        assertEquals(1, received.size)

        // App paused, user taps the same link again a minute later (resume).
        advanceTimeBy(1.minutes)
        source.emit(INVITE)
        runCurrent()
        assertEquals(2, received.size)
        assertEquals(2, api.resolves.size)
        sdk.dispose()
    }

    @Test
    fun theSameSlugWithADifferentQueryIsNotADuplicate() = runTest {
        val source = FakeLinkSource()
        val sdk = sdk(FakeApi(), source = source)
        val received = collectLinks(sdk)
        sdk.start()
        source.emit("$INVITE?a=1")
        source.emit("$INVITE?a=2")
        advanceUntilIdle()
        assertEquals(listOf("$INVITE?a=1", "$INVITE?a=2"), received.map { it.url })
        sdk.dispose()
    }

    @Test
    fun multipleListenersEachSeeALinkOnce() = runTest {
        val source = FakeLinkSource()
        val sdk = sdk(FakeApi(), source = source)
        val a = collectLinks(sdk)
        val b = collectLinks(sdk)
        val c = mutableListOf<ResolvedDeepLink>()
        sdk.addListener { c += it }
        sdk.start()
        source.emit(INVITE)
        advanceUntilIdle()
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertEquals(1, c.size)
        sdk.dispose()
    }

    @Test
    fun errorsGoToOnErrorAndTheStreamKeepsWorking() = runTest {
        var fail = true
        val errors = mutableListOf<StrybokException>()
        val source = FakeLinkSource()
        val sdk = sdk(
            FakeApi { if (fail) throw RuntimeException("offline") else ok() },
            source = source,
            errors = errors,
            maxRetries = 0,
        )
        val received = collectLinks(sdk)
        sdk.start()

        source.emit("https://$HOST/offline1")
        advanceUntilIdle()
        assertIs<StrybokNetworkException>(errors.single())
        assertEquals("https://$HOST/offline1", errors.single().url)
        assertTrue(received.isEmpty())

        fail = false
        source.emit("https://$HOST/online1")
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals(1, errors.size)
        sdk.dispose()
    }

    @Test
    fun aCancelledListenerLeavesUnconsumedLinksForTheNextOne() = runTest {
        val api = FakeApi { request -> ok(route = "/p/" + request.url.substringAfterLast("%2F")) }
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source)
        sdk.start()
        source.emit("https://$HOST/first")
        advanceUntilIdle()
        source.emit("https://$HOST/second")
        advanceUntilIdle()

        assertEquals("/p/first", sdk.links.first().routePath)
        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertEquals(listOf("/p/second"), received.map { it.routePath })
        sdk.dispose()
    }

    // --- callback listener and pushed links ------------------------------------------------

    @Test
    fun addListenerReceivesQueuedAndLaterLinksUntilCancelled() = runTest {
        val source = FakeLinkSource(initialLink = INVITE)
        val sdk = sdk(FakeApi(), source = source)
        sdk.start()

        val received = mutableListOf<String>()
        val subscription = sdk.addListener { received += it.url }
        assertTrue(received.isEmpty(), "listeners are never called synchronously from addListener")
        advanceUntilIdle()
        assertEquals(listOf(INVITE), received)

        source.emit("https://$HOST/second")
        advanceUntilIdle()
        assertEquals(2, received.size)

        subscription.cancel()
        subscription.cancel()
        advanceUntilIdle()
        source.emit("https://$HOST/third")
        advanceUntilIdle()
        assertEquals(2, received.size)

        // Nobody listened: the link was queued, not lost.
        val late = collectLinks(sdk)
        advanceUntilIdle()
        assertEquals(listOf("https://$HOST/third"), late.map { it.url })
        sdk.dispose()
    }

    @Test
    fun aListenerCancelledBeforeItRanDoesNotSwallowQueuedLinks() = runTest {
        val sdk = sdk(FakeApi(), source = FakeLinkSource(initialLink = INVITE))
        sdk.start()
        sdk.addListener { error("must not be called") }.cancel()
        advanceUntilIdle()
        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertEquals(1, received.size)
        sdk.dispose()
    }

    @Test
    fun aLinkHandedOverBeforeStartIsTheLaunchLink() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = PushLinkSource())
        assertTrue(sdk.handleLink(INVITE))
        assertEquals(INVITE, sdk.getInitialLink()?.url)
        assertNull(sdk.getInitialLink())
        assertEquals(1, api.resolves.size)
        sdk.dispose()
    }

    @Test
    fun linksHandedOverAreDeliveredOnceWhetherBeforeOrAfterStart() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = PushLinkSource())
        val received = collectLinks(sdk)
        assertTrue(sdk.handleLink("https://$HOST/one"))
        assertTrue(sdk.handleLink("https://$HOST/two"))
        sdk.start()
        assertTrue(sdk.handleLink("https://$HOST/three"))
        assertTrue(sdk.handleLink("https://$HOST/three"), "ours, although dropped as a duplicate")
        advanceUntilIdle()

        assertEquals(
            setOf("https://$HOST/one", "https://$HOST/two", "https://$HOST/three"),
            received.map { it.url }.toSet(),
        )
        assertEquals(3, received.size)
        assertEquals(3, api.resolves.size)
        sdk.dispose()
    }

    @Test
    fun handleLinkIgnoresForeignLinksAndNeedsThePushSource() = runTest {
        val api = FakeApi()
        val errors = mutableListOf<StrybokException>()
        val sdk = sdk(api, source = PushLinkSource(), errors = errors)
        sdk.start()
        assertFalse(sdk.handleLink("https://evil.example.com/invite777"))
        assertFalse(sdk.handleLink("myapp://open"))
        advanceUntilIdle()
        assertTrue(api.resolves.isEmpty())
        assertTrue(errors.isEmpty())
        sdk.dispose()
        assertFalse(sdk.handleLink(INVITE))

        val custom = sdk(api, source = FakeLinkSource())
        assertFailsWith<IllegalStateException> { custom.handleLink(INVITE) }
        custom.dispose()
    }

    // --- app_open events -------------------------------------------------------------------

    @Test
    fun sendsOneMinimalBestEffortEventPerDeliveredLink() = runTest {
        val api = FakeApi()
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source)
        collectLinks(sdk)
        sdk.start()
        source.emit(INVITE)
        advanceUntilIdle()

        val request = api.eventRequests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.strybok.com/v1/public/events", request.url)
        assertEquals(mapOf("Content-Type" to "application/json"), request.headers)

        val event = api.events.single()
        assertEquals(setOf("eventId", "linkId", "type", "platform", "sdkVersion"), event.keys)
        assertEquals("app_open", event.getValue("type").jsonPrimitive.content)
        assertEquals("link_1", event.getValue("linkId").jsonPrimitive.content)
        assertEquals("0.5.0", event.getValue("sdkVersion").jsonPrimitive.content)
        assertTrue(event.getValue("platform").jsonPrimitive.content in setOf("ios", "android", "unknown"))
        assertEquals(EXPECTED_PLATFORM, event.getValue("platform").jsonPrimitive.content)
        val eventId = event.getValue("eventId").jsonPrimitive.content
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(eventId),
            eventId,
        )
        sdk.dispose()
    }

    @Test
    fun everyEventHasItsOwnId() = runTest {
        val api = FakeApi()
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source)
        sdk.start()
        source.emit("https://$HOST/one")
        source.emit("https://$HOST/two")
        advanceUntilIdle()
        assertEquals(2, api.events.map { it.getValue("eventId").jsonPrimitive.content }.toSet().size)
        sdk.dispose()
    }

    @Test
    fun aSlowOrFailingEventsEndpointNeverDelaysOrBreaksDelivery() = runTest {
        val variants = listOf<suspend () -> HttpResponse>(
            { awaitCancellation() },
            { throw RuntimeException("down") },
            { HttpResponse(500, "") },
        )
        for (onEvent in variants) {
            val api = FakeApi().apply { this.onEvent = onEvent }
            val source = FakeLinkSource()
            val errors = mutableListOf<StrybokException>()
            val sdk = sdk(api, source = source, errors = errors)
            val received = collectLinks(sdk)
            sdk.start()
            source.emit(INVITE)
            runCurrent() // No virtual time passes: delivery did not wait for the event.
            assertEquals(1, received.size)
            advanceUntilIdle()
            assertEquals(1, api.events.size, "events are never retried")
            assertTrue(errors.isEmpty())
            sdk.dispose()
        }
    }

    @Test
    fun appOpenEventsCanBeTurnedOff() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = FakeLinkSource(initialLink = INVITE), reportAppOpens = false)
        sdk.start()
        advanceUntilIdle()
        assertEquals(1, api.resolves.size)
        assertTrue(api.events.isEmpty())
        sdk.dispose()
    }

    @Test
    fun failedLinksAreNotReportedAsAppOpens() = runTest {
        val api = FakeApi { HttpResponse(404, "") }
        val sdk = sdk(api, source = FakeLinkSource(initialLink = INVITE))
        sdk.start()
        advanceUntilIdle()
        assertTrue(api.events.isEmpty())
        sdk.dispose()
    }

    // --- dispose ---------------------------------------------------------------------------

    @Test
    fun disposeStopsDeliveryClosesTheStreamAndRejectsFurtherUse() = runTest {
        val api = FakeApi()
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source)
        var done = false
        val received = collectLinks(sdk) { done = true }
        val viaListener = mutableListOf<ResolvedDeepLink>()
        sdk.addListener { viaListener += it }
        sdk.start()
        assertTrue(source.hasListener)

        sdk.dispose()
        sdk.dispose() // idempotent
        runCurrent()
        assertFalse(source.hasListener)
        assertTrue(done)

        source.emit(INVITE)
        advanceUntilIdle()
        assertTrue(received.isEmpty())
        assertTrue(viaListener.isEmpty())
        assertTrue(api.resolves.isEmpty())
        assertFailsWith<IllegalStateException> { sdk.resolve(INVITE) }
        assertFailsWith<IllegalStateException> { sdk.start() }
        assertFailsWith<IllegalStateException> { sdk.getInitialLink() }

        // Late listeners complete immediately.
        var lateDone = false
        collectLinks(sdk) { lateDone = true }
        assertTrue(lateDone)
        sdk.addListener { error("must not be called") }.cancel()
    }

    @Test
    fun aLinkStillResolvingWhenDisposedIsNotDelivered() = runTest {
        val pending = CompletableDeferred<HttpResponse>()
        val source = FakeLinkSource()
        val errors = mutableListOf<StrybokException>()
        val api = FakeApi { pending.await() }
        val sdk = sdk(api, source = source, errors = errors, timeout = 5.seconds)
        val received = collectLinks(sdk)
        sdk.start()
        source.emit(INVITE)
        runCurrent()
        assertEquals(1, api.resolves.size)

        sdk.dispose()
        pending.complete(ok())
        advanceUntilIdle()
        assertTrue(received.isEmpty())
        assertTrue(errors.isEmpty())
        assertTrue(api.events.isEmpty())
    }

    @Test
    fun queuedLinksAreDroppedOnDispose() = runTest {
        val sdk = sdk(FakeApi(), source = FakeLinkSource(initialLink = INVITE))
        sdk.start()
        sdk.dispose()
        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertTrue(received.isEmpty())
    }

    @Test
    fun disposeWhileStartingLetsStartReturn() = runTest {
        val sdk = sdk(FakeApi { awaitCancellation() }, source = FakeLinkSource(initialLink = INVITE), timeout = 5.seconds)
        val starting = async { sdk.start() }
        runCurrent()
        assertFalse(starting.isCompleted)
        sdk.dispose()
        starting.await()
    }
}
