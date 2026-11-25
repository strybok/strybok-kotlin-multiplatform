package com.strybok.kmp

import com.strybok.kmp.internal.DeferredKind
import com.strybok.kmp.internal.findClickId
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * The install referrer flow of the SDK contract ("Deferred links (optional, Android)"). It lives
 * in common code, so these tests run on every target against a fake platform, fake sources and a
 * fake transport, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredLinksTest {
    private val errors = mutableListOf<StrybokException>()

    private fun android() = FakeDeferredPlatform(DeferredKind.INSTALL_REFERRER)

    /** Starts without a launch link, lets the check run and returns the status it ended with. */
    private suspend fun TestScope.check(sdk: Strybok): DeferredLinkStatus {
        sdk.start()
        advanceUntilIdle()
        assertEquals(emptyList(), errors, "the check reports nothing to onError")
        return sdk.deferredLinkStatus.value
    }

    // --- off by default ------------------------------------------------------------------------

    @Test
    fun isOffByDefaultAndThenReadsSendsAndStoresNothing() = runTest {
        val api = FakeApi()
        val platform = android()
        val source = FakeSource(referrer())
        val sdk = launchApp(api, platform, source, deferredLinks = false, errors = errors)
        val received = collectLinks(sdk)

        assertEquals(DeferredLinkStatus.DISABLED, check(sdk))
        assertEquals(0, source.calls)
        assertTrue(api.redeems.isEmpty())
        assertTrue(platform.store.writes.isEmpty())
        assertTrue(received.isEmpty())

        // The public constructor has it off as well.
        val plain = Strybok(apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api)
        assertEquals(DeferredLinkStatus.DISABLED, plain.deferredLinkStatus.value)
        plain.dispose()
        sdk.dispose()
    }

    @Test
    fun aTargetWithoutDeferredLinksIgnoresTheOption() = runTest {
        val api = FakeApi()
        val source = FakeSource(referrer())
        val sdk = launchApp(api, null, source, errors = errors)

        assertEquals(DeferredLinkStatus.DISABLED, check(sdk))
        if (EXPECTED_PLATFORM == "android") {
            // Android without a context is a mistake of the developer, and says so.
            assertFailsWith<IllegalStateException> { sdk.enableDeferredLinks() }
        } else {
            sdk.enableDeferredLinks()
        }
        advanceUntilIdle()
        assertEquals(DeferredLinkStatus.DISABLED, sdk.deferredLinkStatus.value)
        assertEquals(0, source.calls)
        assertTrue(api.redeems.isEmpty())
        sdk.dispose()
    }

    // --- the happy path ------------------------------------------------------------------------

    @Test
    fun deliversTheLinkOfTheClickIdFoundInTheReferrer() = runTest {
        val api = FakeApi()
        val platform = android()
        val source = FakeSource(referrer("utm_source=google-play&strybok_click=$CLICK_ID&utm_medium=organic"))
        val sdk = launchApp(api, platform, source, errors = errors)
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.CHECKING, sdk.deferredLinkStatus.value)

        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))

        val request = api.redeems.single()
        assertEquals("POST", request.method)
        assertEquals("$API/v1/public/deferred-links/redeem", request.url)
        assertEquals(mapOf("Content-Type" to "application/json", "Accept" to "application/json"), request.headers)
        assertEquals(setOf("platform", "clickId", "packageName", "sdkVersion"), request.json.keys)
        assertEquals("android", request.json["platform"]!!.jsonPrimitive.content)
        assertEquals(CLICK_ID, request.json["clickId"]!!.jsonPrimitive.content)
        assertEquals("com.example.app", request.json["packageName"]!!.jsonPrimitive.content)
        assertEquals(STRYBOK_SDK_VERSION, request.json["sdkVersion"]!!.jsonPrimitive.content)

        val link = received.single()
        assertTrue(link.isDeferred)
        assertFalse(link.isProbabilisticMatch)
        assertEquals("https://$HOST/promo42", link.url)
        assertEquals("link_7", link.linkId)
        assertEquals("/promo/42", link.routePath)
        assertEquals(mapOf<String, Any?>("code" to "SPRING"), link.payload)
        assertEquals("instagram", link.campaign.source)

        assertEquals(listOf("attempts=1", "done=1"), platform.store.writes)
        assertTrue(DeferredLinkStatus.DELIVERED.isFinal)
        assertTrue(api.events.isEmpty(), "no app_open for a deferred link")
        assertTrue(api.resolves.isEmpty())
        assertEquals(1, source.calls)
        sdk.dispose()
    }

    @Test
    fun aNormalLinkIsNotDeferred() = runTest {
        val link = sdk(FakeApi()).resolve(INVITE)
        assertFalse(link.isDeferred)
        assertFalse(link.isProbabilisticMatch)
    }

    @Test
    fun queuesTheLinkUntilAListenerAttachesAndNeverReturnsItFromGetInitialLink() = runTest {
        val sdk = launchApp(FakeApi(), android(), FakeSource(referrer()), errors = errors)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))

        assertNull(sdk.getInitialLink())
        val received = collectLinks(sdk)
        advanceUntilIdle()
        assertTrue(received.single().isDeferred)
        sdk.dispose()
    }

    @Test
    fun startTheLaunchLinkAndCreateLinkDoNotWaitForASourceThatHangs() = runTest {
        val api = FakeApi()
        val release = CompletableDeferred<InstallReferrerResult>()
        var asked = false
        val source = InstallReferrerSource {
            asked = true
            release.await()
        }
        val sdk = launchApp(api, android(), source, sourceTimeout = 3600.seconds, errors = errors)

        sdk.start()
        assertNull(sdk.getInitialLink())
        assertEquals("https://$HOST/Ab3dE9x", sdk.createLink("/product/42").url)
        runCurrent()
        assertTrue(asked)
        assertEquals(DeferredLinkStatus.CHECKING, sdk.deferredLinkStatus.value)
        assertEquals(0, currentTime, "nothing waited")

        release.complete(referrer("nothing=here"))
        advanceUntilIdle()
        assertEquals(DeferredLinkStatus.NO_REFERRER, sdk.deferredLinkStatus.value)
        sdk.dispose()
    }

    @Test
    fun theCheckRunsOnlyAfterTheLaunchLinkWasHandled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi {
            gate.await()
            ok()
        }
        val source = FakeSource(referrer())
        val sdk = launchApp(api, android(), source, linkSource = FakeLinkSource(initialLink = INVITE), errors = errors)
        runCurrent()
        assertEquals(0, source.calls, "not before start")

        val starting = launch { sdk.start() }
        runCurrent()
        assertEquals(1, api.resolves.size)
        assertEquals(0, source.calls, "not while the launch link resolves")
        assertEquals(DeferredLinkStatus.CHECKING, sdk.deferredLinkStatus.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(starting.isCompleted)
        assertEquals(DeferredLinkStatus.SUPERSEDED, sdk.deferredLinkStatus.value)
        assertEquals(1, source.calls)
        sdk.dispose()
    }

    // --- state, attempts and restarts ----------------------------------------------------------------

    @Test
    fun countsTheAttemptBeforeTheFirstSourceIsAsked() = runTest {
        val platform = android()
        var seen = -1
        val source = FakeSource(referrer())
        source.onAsked = { seen = platform.store.attempts }

        assertEquals(DeferredLinkStatus.DELIVERED, check(launchApp(FakeApi(), platform, source, errors = errors)))
        assertEquals(1, seen)
        assertEquals("attempts=1", platform.store.writes.first())
    }

    @Test
    fun threeLaunchesThatCannotFinishThenFailed() = runTest {
        val api = FakeApi()
        val platform = android()
        val source = FakeSource(InstallReferrerResult.UnavailableForNow)

        repeat(3) { launchNumber ->
            val sdk = launchApp(api, platform, source, errors = errors)
            assertEquals(DeferredLinkStatus.PENDING, check(sdk))
            assertEquals(launchNumber + 1, platform.store.attempts)
            assertFalse(platform.store.done)
            sdk.dispose()
        }
        assertFalse(DeferredLinkStatus.PENDING.isFinal)
        val fourth = launchApp(api, platform, source, errors = errors)
        assertEquals(DeferredLinkStatus.FAILED, check(fourth))
        fourth.dispose()

        assertEquals(3, source.calls, "the fourth launch asks nobody")
        assertEquals(listOf("attempts=1", "attempts=2", "attempts=3", "done=1"), platform.store.writes)

        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, check(launchApp(api, platform, source, errors = errors)))
        assertEquals(3, source.calls)
        assertTrue(api.redeems.isEmpty())
    }

    @Test
    fun aLaterLaunchDoesNothingOnceDoneIsStored() = runTest {
        val api = FakeApi()
        val platform = android()
        val source = FakeSource(referrer())
        val first = launchApp(api, platform, source, errors = errors)
        assertEquals(DeferredLinkStatus.DELIVERED, check(first))
        first.dispose()

        val second = launchApp(api, platform, source, errors = errors)
        val received = collectLinks(second)
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, check(second))

        assertEquals(1, source.calls)
        assertEquals(1, api.redeems.size)
        assertTrue(received.isEmpty())
        assertEquals(listOf("attempts=1", "done=1"), platform.store.writes)
        second.dispose()
    }

    @Test
    fun anUpdatedAppIsNotAskedAboutAnOldReferrer() = runTest {
        val api = FakeApi()
        val platform = android().apply { updated = true }
        val source = FakeSource(referrer())

        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, check(launchApp(api, platform, source, errors = errors)))
        assertEquals(0, source.calls)
        assertTrue(api.redeems.isEmpty())
        assertEquals(listOf("done=1"), platform.store.writes)
    }

    @Test
    fun aLaunchKilledWhileASourceIsAskedHasUsedAnAttempt() = runTest {
        val api = FakeApi()
        val platform = android()
        val first = launchApp(api, platform, InstallReferrerSource { awaitCancellation() }, errors = errors)
        first.start()
        runCurrent()
        first.dispose() // The process dies here.
        advanceUntilIdle()

        assertEquals(1, platform.store.attempts)
        assertFalse(platform.store.done)

        val second = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val received = collectLinks(second)
        assertEquals(DeferredLinkStatus.DELIVERED, check(second))
        assertEquals(1, received.size)
        assertEquals(listOf("attempts=1", "attempts=2", "done=1"), platform.store.writes)
        second.dispose()
    }

    @Test
    fun aLaunchKilledRightAfterDoneWasStoredNeverDelivers() = runTest {
        val api = FakeApi()
        val platform = android()
        val first = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val lost = collectLinks(first)
        // Killed between the write and the delivery. The answer had arrived: done stays stored.
        platform.store.afterSet = { name -> if (name == "done") first.dispose() }
        first.start()
        advanceUntilIdle()
        assertTrue(platform.store.done)
        assertTrue(lost.isEmpty())

        platform.store.afterSet = null
        val second = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val again = collectLinks(second)
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, check(second))
        assertTrue(again.isEmpty(), "lost, never delivered twice")
        assertEquals(1, api.redeems.size)
        second.dispose()
    }

    @Test
    fun aLaunchKilledBeforeTheAnswerArrivedAsksAgainAndDeliversOnce() = runTest {
        val api = FakeApi()
        val platform = android()
        val first = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val lost = collectLinks(first)
        api.onRedeem = {
            first.dispose() // The answer is on its way when the process dies.
            redeemed()
        }
        first.start()
        advanceUntilIdle()
        assertEquals(1, api.redeems.size)
        assertFalse(platform.store.done, "done is not stored before the answer")
        assertTrue(lost.isEmpty())

        api.onRedeem = null // The service repeats its answer for two minutes.
        val second = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val received = collectLinks(second)
        assertEquals(DeferredLinkStatus.DELIVERED, check(second))
        assertEquals(1, received.size)
        assertEquals(api.redeems[0].body, api.redeems[1].body)
        assertEquals(listOf("attempts=1", "attempts=2", "done=1"), platform.store.writes)
        second.dispose()
    }

    @Test
    fun stopsWithoutAskingWhenTheAttemptCannotBeStored() = runTest {
        val platform = android().apply { store.broken += "attempts" }
        val source = FakeSource(referrer())
        assertEquals(DeferredLinkStatus.PENDING, check(launchApp(FakeApi(), platform, source, errors = errors)))
        assertEquals(0, source.calls)
    }

    @Test
    fun doesNotDeliverWhenDoneCannotBeStored() = runTest {
        val api = FakeApi()
        val platform = android().apply { store.broken += "done" }
        val sdk = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.PENDING, check(sdk))
        assertTrue(received.isEmpty())
        assertEquals(1, api.redeems.size)
        sdk.dispose()
    }

    @Test
    fun onlyOneInstanceInAProcessRunsTheCheck() = runTest {
        val api = FakeApi()
        val platform = android().apply { claims = false }
        val source = FakeSource(referrer())
        assertEquals(DeferredLinkStatus.PENDING, check(launchApp(api, platform, source, errors = errors)))
        platform.store.values["done"] = "1"
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, check(launchApp(api, platform, source, errors = errors)))
        assertEquals(0, source.calls)
        assertTrue(api.redeems.isEmpty())
        assertTrue(platform.store.writes.isEmpty())
    }

    // --- sources -------------------------------------------------------------------------------

    @Test
    fun noSourcesAtAllIsUnavailable() = runTest {
        val api = FakeApi()
        val platform = android()
        assertEquals(DeferredLinkStatus.UNAVAILABLE, check(launchApp(api, platform, errors = errors)))
        assertTrue(platform.store.done)
        assertTrue(api.redeems.isEmpty())
        assertTrue(DeferredLinkStatus.UNAVAILABLE.isFinal)
    }

    @Test
    fun sourcesThatCanNeverAnswerAreUnavailable() = runTest {
        val platform = android()
        val first = FakeSource(InstallReferrerResult.Unavailable)
        val second = FakeSource(InstallReferrerResult.Unavailable)
        assertEquals(
            DeferredLinkStatus.UNAVAILABLE,
            check(launchApp(FakeApi(), platform, first, second, errors = errors)),
        )
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertTrue(platform.store.done)
    }

    @Test
    fun aReferrerWithoutAClickIdIsTheOrdinaryInstall() = runTest {
        val api = FakeApi()
        val platform = android()
        val sources = arrayOf(
            FakeSource(InstallReferrerResult.Unavailable),
            FakeSource(referrer("utm_source=google-play&utm_medium=organic")),
            FakeSource(referrer("")),
        )
        assertEquals(DeferredLinkStatus.NO_REFERRER, check(launchApp(api, platform, *sources, errors = errors)))
        assertEquals(listOf(1, 1, 1), sources.map { it.calls })
        assertTrue(platform.store.done)
        assertTrue(api.redeems.isEmpty())
        assertTrue(DeferredLinkStatus.NO_REFERRER.isFinal)
    }

    @Test
    fun oneSourceThatCouldNotBeAskedKeepsTheCheckPending() = runTest {
        val platform = android()
        val sdk = launchApp(
            FakeApi(), platform,
            FakeSource(referrer("utm_source=google-play")),
            FakeSource(InstallReferrerResult.UnavailableForNow),
            FakeSource(InstallReferrerResult.Unavailable),
            errors = errors,
        )
        assertEquals(DeferredLinkStatus.PENDING, check(sdk))
        assertFalse(platform.store.done)
        assertEquals(listOf("attempts=1"), platform.store.writes)
    }

    @Test
    fun asksTheSourcesInOrderAndStopsAtTheFirstId() = runTest {
        val api = FakeApi()
        val order = mutableListOf<String>()
        val play = FakeSource(InstallReferrerResult.Unavailable).apply { onAsked = { order += "play" } }
        val galaxy = FakeSource(InstallReferrerResult.UnavailableForNow).apply { onAsked = { order += "galaxy" } }
        val appGallery = FakeSource(referrer()).apply { onAsked = { order += "appgallery" } }
        val never = FakeSource(referrer("strybok_click=dc_${"z".repeat(32)}")).apply { onAsked = { order += "never" } }

        val sdk = launchApp(api, android(), play, galaxy, appGallery, never, errors = errors)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        assertEquals(listOf("play", "galaxy", "appgallery"), order)
        assertEquals(CLICK_ID, api.redeems.single().json["clickId"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSourceThatThrowsCountsAsUnavailableForNow() = runTest {
        val platform = android()
        val throwing = InstallReferrerSource { throw IllegalStateException("referrer=strybok_click=$CLICK_ID") }
        val next = FakeSource(InstallReferrerResult.Unavailable)
        assertEquals(
            DeferredLinkStatus.PENDING,
            check(launchApp(FakeApi(), platform, throwing, next, errors = errors)),
        )
        assertEquals(1, next.calls, "the next source is still asked")
        assertFalse(platform.store.done)
    }

    @Test
    fun aSourceIsAbandonedAfterTenSeconds() = runTest {
        val platform = android()
        var cancelled = false
        val slow = InstallReferrerSource {
            try {
                awaitCancellation()
            } finally {
                cancelled = true // Where a real source closes its connection.
            }
        }
        val next = FakeSource(InstallReferrerResult.Unavailable)
        val sdk = launchApp(FakeApi(), platform, slow, next, errors = errors)

        assertEquals(DeferredLinkStatus.PENDING, check(sdk))
        assertEquals(10_000, currentTime)
        assertTrue(cancelled)
        assertEquals(1, next.calls)
        assertFalse(platform.store.done)
    }

    @Test
    fun aSourceThatAnswersJustInTimeIsUsed() = runTest {
        val source = InstallReferrerSource {
            delay(9.seconds)
            referrer()
        }
        assertEquals(DeferredLinkStatus.DELIVERED, check(launchApp(FakeApi(), android(), source, errors = errors)))
    }

    // --- a normal link wins --------------------------------------------------------------------

    @Test
    fun aLaunchLinkOfThisAppSupersedesTheDeferredLinkWithoutARequest() = runTest {
        val api = FakeApi()
        val platform = android()
        val source = FakeSource(referrer())
        val sdk = launchApp(api, platform, source, linkSource = FakeLinkSource(initialLink = INVITE), errors = errors)
        val received = collectLinks(sdk)

        assertEquals(DeferredLinkStatus.SUPERSEDED, check(sdk))
        assertTrue(api.redeems.isEmpty())
        assertEquals(listOf(INVITE), received.map { it.url })
        assertFalse(received.single().isDeferred)
        assertTrue(platform.store.done)
        assertEquals(1, source.calls)
        assertTrue(DeferredLinkStatus.SUPERSEDED.isFinal)
        sdk.dispose()
    }

    @Test
    fun aLinkThatArrivesWhileTheRequestIsInFlightSupersedesTheDeferredLink() = runTest {
        val api = FakeApi()
        val platform = android()
        val gate = CompletableDeferred<Unit>()
        api.onRedeem = {
            gate.await()
            redeemed()
        }
        val links = FakeLinkSource()
        val sdk = launchApp(api, platform, FakeSource(referrer()), linkSource = links, errors = errors)
        val received = collectLinks(sdk)
        sdk.start()
        runCurrent()
        assertEquals(1, api.redeems.size)

        links.emit(INVITE)
        runCurrent()
        assertEquals(listOf(INVITE), received.map { it.url })
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DeferredLinkStatus.SUPERSEDED, sdk.deferredLinkStatus.value)
        assertEquals(listOf(INVITE), received.map { it.url }, "never queued behind the other link")
        assertTrue(platform.store.done)
        sdk.dispose()
    }

    @Test
    fun aNormalLinkThatFailsToResolveStillSupersedes() = runTest {
        val api = FakeApi { HttpResponse(404, "") }
        val failures = mutableListOf<StrybokException>()
        val sdk = launchApp(
            api, android(), FakeSource(referrer()),
            linkSource = FakeLinkSource(initialLink = INVITE), errors = failures,
        )
        sdk.start()
        advanceUntilIdle()
        assertEquals(DeferredLinkStatus.SUPERSEDED, sdk.deferredLinkStatus.value)
        assertTrue(api.redeems.isEmpty())
        assertEquals(1, failures.size, "only the normal link's own error")
        sdk.dispose()
    }

    @Test
    fun aLinkOfAnotherAppDoesNotSupersede() = runTest {
        val sdk = launchApp(
            FakeApi(), android(), FakeSource(referrer()),
            linkSource = FakeLinkSource(initialLink = "https://elsewhere.example.com/invite777"), errors = errors,
        )
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        assertTrue(received.single().isDeferred)
        sdk.dispose()
    }

    // --- answers of the service ------------------------------------------------------------------

    @Test
    fun unusable200AnswersFailTheCheckAndDeliverNothing() = runTest {
        val unusable = listOf(
            "not json",
            "[]",
            // A link on a host that is not ours.
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://evil.example.com/promo42","deferred":true}""",
            // Not a link at all.
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/a/b","deferred":true}""",
            """{"linkId":"l","appId":"a","routePath":"/x","deferred":true}""",
            // Not marked as deferred.
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42"}""",
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":"true"}""",
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":false}""",
            // Not valid as for resolving.
            """{"linkId":"l","appId":"a","routePath":"//evil.example.com","url":"https://$HOST/promo42","deferred":true}""",
            """{"appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":true}""",
        )
        for (body in unusable) {
            val api = FakeApi()
            api.onRedeem = { HttpResponse(200, body) }
            val platform = android()
            val sdk = launchApp(api, platform, FakeSource(referrer()), errors = errors)
            val received = collectLinks(sdk)
            assertEquals(DeferredLinkStatus.FAILED, check(sdk), body)
            assertTrue(received.isEmpty(), body)
            assertTrue(platform.store.done, body)
            assertEquals(1, api.redeems.size, body)
            sdk.dispose()
        }
        assertTrue(DeferredLinkStatus.FAILED.isFinal)
    }

    @Test
    fun unknownFieldsOfTheAnswerAreIgnored() = runTest {
        val api = FakeApi()
        api.onRedeem = {
            HttpResponse(
                200,
                """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":true,"later":{"a":1}}""",
            )
        }
        val sdk = launchApp(api, android(), FakeSource(referrer()), errors = errors)
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        assertTrue(received.single().payload.isEmpty())
        assertTrue(received.single().campaign.isEmpty)
        sdk.dispose()
    }

    @Test
    fun aRefusedIdIsNotFoundAndIsNotAskedForAgain() = runTest {
        for (status in listOf(404, 400, 401, 403, 410, 413, 422)) {
            val api = FakeApi()
            api.onRedeem = { HttpResponse(status, """{"error":{"code":"DEFERRED_LINK_NOT_FOUND"}}""") }
            val platform = android()
            val sdk = launchApp(api, platform, FakeSource(referrer()), errors = errors)
            assertEquals(DeferredLinkStatus.NOT_FOUND, check(sdk), "$status")
            assertTrue(platform.store.done, "$status")
            assertEquals(1, api.redeems.size, "$status is not retried")
            sdk.dispose()
        }
        assertTrue(DeferredLinkStatus.NOT_FOUND.isFinal)
    }

    @Test
    fun tooManyRequestsIsNotRetriedInThisLaunchAndKeepsTheCheckPending() = runTest {
        val api = FakeApi()
        api.onRedeem = { HttpResponse(429, "", mapOf("Retry-After" to "1")) }
        val platform = android()
        assertEquals(
            DeferredLinkStatus.PENDING,
            check(launchApp(api, platform, FakeSource(referrer()), errors = errors)),
        )
        assertEquals(1, api.redeems.size)
        assertFalse(platform.store.done)
    }

    @Test
    fun serverErrorsNetworkFailuresAndTimeoutsAreRetriedWithTheSameBodyThenPending() = runTest {
        val failures = listOf<suspend (HttpRequest) -> HttpResponse>(
            { HttpResponse(503, "") },
            { throw RuntimeException("offline; clickId=$CLICK_ID") },
            { throw HttpTimeoutException("slow") },
            { awaitCancellation() }, // No answer within the timeout.
        )
        for (failure in failures) {
            val api = FakeApi()
            api.onRedeem = failure
            val platform = android()
            val sdk = launchApp(api, platform, FakeSource(referrer()), maxRetries = 2, errors = errors)
            assertEquals(DeferredLinkStatus.PENDING, check(sdk))
            assertEquals(3, api.redeems.size)
            assertEquals(1, api.redeems.map { it.body }.toSet().size)
            assertFalse(platform.store.done)
            sdk.dispose()
        }
    }

    @Test
    fun anAnswerThatIsNeitherSuccessNorRefusalKeepsTheCheckPending() = runTest {
        val api = FakeApi()
        api.onRedeem = { HttpResponse(302, "") }
        val platform = android()
        assertEquals(
            DeferredLinkStatus.PENDING,
            check(launchApp(api, platform, FakeSource(referrer()), errors = errors)),
        )
        assertFalse(platform.store.done)
    }

    @Test
    fun aRetryThatSucceedsDelivers() = runTest {
        val api = FakeApi()
        var calls = 0
        api.onRedeem = { if (++calls == 1) HttpResponse(502, "") else redeemed() }
        val sdk = launchApp(api, android(), FakeSource(referrer()), errors = errors)
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        assertEquals(1, received.size)
        assertEquals(api.redeems[0].body, api.redeems[1].body)
        sdk.dispose()
    }

    @Test
    fun aPendingLaunchIsFollowedByALaunchThatDelivers() = runTest {
        val api = FakeApi()
        api.onRedeem = { HttpResponse(503, "") }
        val platform = android()
        val first = launchApp(api, platform, FakeSource(referrer()), maxRetries = 0, errors = errors)
        assertEquals(DeferredLinkStatus.PENDING, check(first))
        first.dispose()

        api.onRedeem = null
        val second = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val received = collectLinks(second)
        assertEquals(DeferredLinkStatus.DELIVERED, check(second))
        assertEquals(1, received.size)
        assertEquals(listOf("attempts=1", "attempts=2", "done=1"), platform.store.writes)
        second.dispose()
    }

    // --- switching on later, status, dispose -----------------------------------------------------

    @Test
    fun enableDeferredLinksAfterStartRunsTheCheckOnce() = runTest {
        val api = FakeApi()
        val source = FakeSource(referrer())
        val sdk = launchApp(api, android(), source, deferredLinks = false, errors = errors)
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.DISABLED, check(sdk))
        assertEquals(0, source.calls)

        sdk.enableDeferredLinks()
        assertEquals(DeferredLinkStatus.CHECKING, sdk.deferredLinkStatus.value)
        sdk.enableDeferredLinks()
        advanceUntilIdle()
        sdk.enableDeferredLinks()
        advanceUntilIdle()

        assertEquals(DeferredLinkStatus.DELIVERED, sdk.deferredLinkStatus.value)
        assertEquals(1, source.calls)
        assertEquals(1, api.redeems.size)
        assertEquals(1, received.size)
        sdk.dispose()
        sdk.enableDeferredLinks() // Harmless after dispose.
    }

    @Test
    fun enableDeferredLinksBeforeStartWaitsForStart() = runTest {
        val source = FakeSource(referrer())
        val sdk = launchApp(FakeApi(), android(), source, deferredLinks = false, errors = errors)
        sdk.enableDeferredLinks()
        advanceUntilIdle()
        assertEquals(DeferredLinkStatus.CHECKING, sdk.deferredLinkStatus.value)
        assertEquals(0, source.calls)

        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        assertEquals(1, source.calls)
        sdk.dispose()
    }

    @Test
    fun callingStartAgainDoesNotRunTheCheckAgain() = runTest {
        val api = FakeApi()
        val source = FakeSource(referrer())
        val sdk = launchApp(api, android(), source, errors = errors)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        sdk.start()
        assertNull(sdk.getInitialLink())
        advanceUntilIdle()
        assertEquals(1, source.calls)
        assertEquals(1, api.redeems.size)
        sdk.dispose()
    }

    @Test
    fun theStatusCanBeObservedAsAFlowAndWithAListener() = runTest {
        val sdk = launchApp(FakeApi(), android(), FakeSource(referrer()), errors = errors)
        val flowed = mutableListOf<DeferredLinkStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sdk.deferredLinkStatus.toList(flowed) }
        val heard = mutableListOf<DeferredLinkStatus>()
        val cancelled = mutableListOf<DeferredLinkStatus>()
        sdk.addDeferredLinkStatusListener { heard += it }
        val subscription = sdk.addDeferredLinkStatusListener { cancelled += it }
        runCurrent()
        subscription.cancel()

        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        assertEquals(listOf(DeferredLinkStatus.CHECKING, DeferredLinkStatus.DELIVERED), flowed)
        assertEquals(listOf(DeferredLinkStatus.CHECKING, DeferredLinkStatus.DELIVERED), heard)
        assertEquals(listOf(DeferredLinkStatus.CHECKING), cancelled)
        sdk.dispose()
    }

    @Test
    fun nothingIsDeliveredAfterDispose() = runTest {
        val api = FakeApi()
        api.onRedeem = { awaitCancellation() }
        val platform = android()
        val sdk = launchApp(api, platform, FakeSource(referrer()), errors = errors)
        val received = collectLinks(sdk)
        sdk.start()
        runCurrent()
        assertEquals(1, api.redeems.size)
        sdk.dispose()
        advanceUntilIdle()
        assertTrue(received.isEmpty())
        assertFalse(platform.store.done)
        assertEquals(emptyList(), errors)
    }

    // --- nothing sensitive leaves the check ---------------------------------------------------------

    @Test
    fun neitherTheReferrerNorTheClickIdShowsUpInWhatTheSdkPrints() = runTest {
        val secret = "strybok_click=$CLICK_ID&utm_source=private"
        assertFalse("strybok" in InstallReferrerResult.Referrer(secret).toString())
        assertFalse(CLICK_ID in InstallReferrerResult.Referrer(secret).toString())
        assertEquals(secret, InstallReferrerResult.Referrer(secret).value)

        val api = FakeApi()
        val sdk = launchApp(api, android(), FakeSource(referrer(secret)), errors = errors)
        assertEquals(DeferredLinkStatus.DELIVERED, check(sdk))
        val request = api.redeems.single()
        assertFalse(CLICK_ID in request.toString())
        assertFalse(CLICK_ID in request.url)
        assertFalse(request.headers.values.any { CLICK_ID in it })
        assertFalse("private" in request.body!!, "only the id is sent, not the rest of the referrer")
        for (status in DeferredLinkStatus.entries) assertFalse("dc_" in status.toString())
        sdk.dispose()
    }

    // --- finding the click id --------------------------------------------------------------------

    @Test
    fun findsTheClickIdInAReferrer() {
        assertEquals(CLICK_ID, findClickId("strybok_click=$CLICK_ID"))
        assertEquals(CLICK_ID, findClickId("utm_source=x&strybok_click=$CLICK_ID&utm_medium=y"))
        assertEquals(CLICK_ID, findClickId("gclid=abc=def&&strybok_click=$CLICK_ID&"))
        // The first occurrence counts.
        val other = "dc_" + "Z".repeat(32)
        assertEquals(CLICK_ID, findClickId("strybok_click=$CLICK_ID&strybok_click=$other"))
        assertNull(findClickId("strybok_click=bad&strybok_click=$CLICK_ID"))
    }

    @Test
    fun aMissingOrMalformedClickIdIsNoId() {
        val malformed = listOf(
            "",
            "&",
            "=",
            "utm_source=google-play&utm_medium=organic",
            "strybok_click",
            "strybok_click=",
            "strybok_click=dc_",
            "strybok_click=${CLICK_ID.dropLast(1)}",
            "strybok_click=${CLICK_ID}0",
            "strybok_click=${CLICK_ID.dropLast(1)}-",
            "strybok_click=${CLICK_ID.dropLast(1)}_",
            "strybok_click=${CLICK_ID.dropLast(1)}é",
            "strybok_click=${CLICK_ID.dropLast(3)}%41",
            "strybok_click=DC_${CLICK_ID.drop(3)}",
            "strybok_click=dc-${CLICK_ID.drop(3)}",
            "strybok_click= $CLICK_ID",
            "Strybok_Click=$CLICK_ID",
            "xstrybok_click=$CLICK_ID",
            "strybok_click_2=$CLICK_ID",
            "other=strybok_click=$CLICK_ID",
            " strybok_click=$CLICK_ID",
            "strybok_click=$CLICK_ID;x=1",
        )
        for (referrer in malformed) assertNull(findClickId(referrer), referrer)
    }

    @Test
    fun decodesAReferrerThatCameBackStillEncodedOnce() {
        assertEquals(CLICK_ID, findClickId("strybok_click%3D$CLICK_ID"))
        assertEquals(CLICK_ID, findClickId("strybok_click%3d$CLICK_ID%26utm_source%3Dgoogle"))
        assertEquals(CLICK_ID, findClickId("utm_source%3Dx%26strybok_click%3D$CLICK_ID"))
        // A malformed escape elsewhere does not stop the decoding.
        assertEquals(CLICK_ID, findClickId("a%3D%zz%26strybok_click%3D$CLICK_ID%26b%3D%"))
        // The key itself is compared exactly, also after decoding.
        assertNull(findClickId("STRYBOK_CLICK%3D$CLICK_ID"))
        // Encoded twice stays encoded after one round.
        assertNull(findClickId("strybok_click%253D$CLICK_ID"))
        // The key is there in plain text with a bad value: the encoded copy is not looked at.
        assertNull(findClickId("strybok_click=bad&x=strybok_click%3D$CLICK_ID"))
        // After decoding the key is part of another value.
        assertNull(findClickId("a=strybok_click%3D$CLICK_ID"))
        assertNull(findClickId("strybok_click%3Dbad"))
        // `+` is not a space here, and decoded bytes that are not UTF-8 do no harm.
        assertNull(findClickId("%ff%fe+strybok_click%3D$CLICK_ID"))
    }
}
