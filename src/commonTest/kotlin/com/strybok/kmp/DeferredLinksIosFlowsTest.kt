package com.strybok.kmp

import com.strybok.kmp.internal.DeferredKind
import com.strybok.kmp.internal.pastedClickId
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two flows of the SDK contract's "Deferred links on iOS": the pasted link (A) and
 * probabilistic matching (B). Both live in common code, so these tests run on every target —
 * the iOS simulator included — against a fake platform and a fake transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredLinksIosFlowsTest {
    private val errors = mutableListOf<StrybokException>()

    private fun ios() = FakeDeferredPlatform(DeferredKind.IOS)

    private suspend fun TestScope.started(sdk: Strybok): Strybok {
        sdk.start()
        advanceUntilIdle()
        return sdk
    }

    private fun Strybok.status(): DeferredLinkStatus = deferredLinkStatus.value

    // === Flow A: the pasted link =================================================================

    @Test
    fun pastingDoesNothingWhileDeferredLinksAreOff() = runTest {
        val api = FakeApi()
        val platform = ios().apply { pasteboardHasUrl = true }
        val sdk = started(launchApp(api, platform, deferredLinks = false, matching = true, errors = errors))

        assertEquals(PastedLinkResult.DISABLED, sdk.redeemPastedLink(COPIED))
        assertFalse(sdk.pasteboardMayContainLink())
        sdk.skipPastedLink()
        advanceUntilIdle()

        assertEquals(DeferredLinkStatus.DISABLED, sdk.status())
        assertEquals(0, platform.pasteboardChecks, "the pasteboard is not even asked about")
        assertTrue(api.redeems.isEmpty() && api.matches.isEmpty())
        assertTrue(platform.store.writes.isEmpty())
        sdk.dispose()
    }

    @Test
    fun pastingIsAnIosFlowOnly() = runTest {
        val api = FakeApi()
        val android = started(launchApp(api, FakeDeferredPlatform(DeferredKind.INSTALL_REFERRER), errors = errors))
        val jvm = started(launchApp(api, null, errors = errors))
        for (sdk in listOf(android, jvm)) {
            assertEquals(PastedLinkResult.DISABLED, sdk.redeemPastedLink(COPIED))
            assertFalse(sdk.pasteboardMayContainLink())
            sdk.skipPastedLink()
            advanceUntilIdle()
            sdk.dispose()
        }
        assertTrue(api.redeems.isEmpty() && api.matches.isEmpty())
    }

    @Test
    fun deliversThePastedLink() = runTest {
        val api = FakeApi()
        val platform = ios()
        val sdk = started(launchApp(api, platform, errors = errors))
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        assertFalse(DeferredLinkStatus.AWAITING_PASTE.isFinal)

        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink("  $COPIED&utm_source=private\n"))
        advanceUntilIdle()

        val request = api.redeems.single()
        assertEquals("POST", request.method)
        assertEquals("$API/v1/public/deferred-links/redeem", request.url)
        assertEquals(mapOf("Content-Type" to "application/json", "Accept" to "application/json"), request.headers)
        assertEquals(setOf("platform", "clickId", "bundleId", "sdkVersion"), request.json.keys)
        assertEquals("ios", request.json["platform"]!!.jsonPrimitive.content)
        assertEquals(CLICK_ID, request.json["clickId"]!!.jsonPrimitive.content)
        assertEquals("com.example.App", request.json["bundleId"]!!.jsonPrimitive.content)
        assertEquals(STRYBOK_SDK_VERSION, request.json["sdkVersion"]!!.jsonPrimitive.content)
        val sent = request.body.orEmpty()
        assertFalse("promo42" in sent, "only the click id is sent, never the pasted URL")
        assertFalse("private" in sent)

        val link = received.single()
        assertTrue(link.isDeferred)
        assertFalse(link.isProbabilisticMatch)
        assertEquals("https://$HOST/promo42", link.url, "the answer's url, without the click id")
        assertEquals("/promo/42", link.routePath)
        assertEquals("instagram", link.campaign.source)

        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        assertEquals(listOf("done=1"), platform.store.writes, "flow A does not count attempts")
        assertTrue(api.events.isEmpty(), "no app_open for a deferred link")
        assertNull(sdk.getInitialLink())
        assertTrue(api.matches.isEmpty())
        assertEquals(emptyList(), errors)
        sdk.dispose()
    }

    @Test
    fun aPastedLinkIsDeliveredEvenAfterANormalLinkInThisLaunch() = runTest {
        val api = FakeApi()
        val sdk = launchApp(api, ios(), linkSource = FakeLinkSource(initialLink = INVITE), errors = errors)
        val received = collectLinks(sdk)
        started(sdk)
        assertEquals(listOf(INVITE), received.map { it.url })

        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        assertEquals(listOf(INVITE, "https://$HOST/promo42"), received.map { it.url })
        assertEquals(listOf(false, true), received.map { it.isDeferred })
        sdk.dispose()
    }

    @Test
    fun whateverElseWasOnTheClipboardIsDroppedWithoutARequest() = runTest {
        val api = FakeApi()
        val platform = ios()
        val sdk = started(launchApp(api, platform, errors = errors))
        val received = collectLinks(sdk)
        val notOurs = listOf(
            "",
            "   ",
            "my password is hunter2",
            "4111 1111 1111 1111",
            // A link of this app, but not a copied one.
            "https://$HOST/promo42",
            "https://$HOST/promo42?utm_source=x",
            "https://$HOST/promo42?strybok_click=",
            "https://$HOST/promo42?strybok_click=${CLICK_ID.dropLast(1)}",
            "https://$HOST/promo42?strybok_click=${CLICK_ID}x",
            "https://$HOST/promo42?strybok_click=bad&strybok_click=$CLICK_ID",
            "https://$HOST/promo42#strybok_click=$CLICK_ID",
            "https://$HOST/promo42?x=1#?strybok_click=$CLICK_ID",
            "https://$HOST/promo42?xstrybok_click=$CLICK_ID",
            // Not a link of this app at all.
            "https://evil.example.com/promo42?strybok_click=$CLICK_ID",
            "http://$HOST/promo42?strybok_click=$CLICK_ID",
            "https://$HOST/a/b?strybok_click=$CLICK_ID",
            "https://$HOST/?strybok_click=$CLICK_ID",
            "https://user@$HOST/promo42?strybok_click=$CLICK_ID",
            "https://$HOST:8443/promo42?strybok_click=$CLICK_ID",
            "strybok_click=$CLICK_ID",
            CLICK_ID,
            "see $COPIED",
            "$COPIED and more",
        )
        for (text in notOurs) {
            assertEquals(PastedLinkResult.NOT_OURS, sdk.redeemPastedLink(text), text)
            assertNull(pastedClickId(text, setOf(HOST)), text)
        }
        advanceUntilIdle()

        assertTrue(api.redeems.isEmpty() && api.matches.isEmpty())
        assertTrue(platform.store.writes.isEmpty(), "nothing is stored")
        assertTrue(received.isEmpty())
        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        assertEquals(emptyList(), errors)
        sdk.dispose()
    }

    @Test
    fun readsTheClickIdOfACopiedLink() {
        val hosts = setOf(HOST)
        assertEquals(CLICK_ID, pastedClickId(COPIED, hosts))
        assertEquals(CLICK_ID, pastedClickId("\n\t $COPIED \r\n", hosts))
        assertEquals(CLICK_ID, pastedClickId("https://MY-APP.strybok.link/promo42?utm_source=a&strybok_click=$CLICK_ID#top", hosts))
        assertEquals(CLICK_ID, pastedClickId("https://$HOST:443/promo42?strybok_click=$CLICK_ID&strybok_click=bad", hosts))
        // A standard query parser decodes the key.
        assertEquals(CLICK_ID, pastedClickId("https://$HOST/promo42?strybok%5Fclick=$CLICK_ID", hosts))
        assertNull(pastedClickId(COPIED, setOf("other.strybok.link")))
    }

    @Test
    fun aRefusedPastedLinkIsNotFoundAndEndsTheStory() = runTest {
        for (status in listOf(404, 400, 410, 422)) {
            val api = FakeApi()
            api.onRedeem = { HttpResponse(status, """{"error":{"code":"DEFERRED_LINK_NOT_FOUND"}}""") }
            val platform = ios()
            val sdk = started(launchApp(api, platform, errors = errors))

            assertEquals(PastedLinkResult.NOT_FOUND, sdk.redeemPastedLink(COPIED), "$status")
            assertEquals(DeferredLinkStatus.NOT_FOUND, sdk.status())
            assertTrue(platform.store.done)
            assertEquals(1, api.redeems.size, "$status is not retried")

            assertEquals(PastedLinkResult.ALREADY_HANDLED, sdk.redeemPastedLink(COPIED))
            assertFalse(sdk.pasteboardMayContainLink())
            assertEquals(1, api.redeems.size)
            sdk.dispose()
        }
    }

    @Test
    fun afterAFailureThatMayPassThePersonCanPasteAgain() = runTest {
        val failures = listOf<suspend (HttpRequest) -> HttpResponse>(
            { HttpResponse(429, "") },
            { HttpResponse(503, "") },
            { HttpResponse(302, "") },
            { throw RuntimeException("offline; clickId=$CLICK_ID") },
            { throw HttpTimeoutException("slow") },
            { awaitCancellation() },
        )
        for ((index, failure) in failures.withIndex()) {
            val api = FakeApi()
            api.onRedeem = failure
            val platform = ios()
            val sdk = started(launchApp(api, platform, maxRetries = 1, errors = errors))
            val received = collectLinks(sdk)

            assertEquals(PastedLinkResult.FAILED, sdk.redeemPastedLink(COPIED), "failure $index")
            // 429 and 302 are answers and are not retried; the others are, with the same body.
            assertEquals(if (index == 0 || index == 2) 1 else 2, api.redeems.size, "failure $index")
            assertEquals(1, api.redeems.map { it.body }.toSet().size)
            assertFalse(platform.store.done, "done is not stored")
            assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())

            api.onRedeem = null
            assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
            assertEquals(1, received.size)
            sdk.dispose()
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun anUnusableAnswerToAPastedLinkFailsForGood() = runTest {
        val unusable = listOf(
            "not json",
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://evil.example.com/promo42","deferred":true}""",
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42"}""",
            """{"linkId":"l","appId":"a","routePath":"x","url":"https://$HOST/promo42","deferred":true}""",
        )
        for (body in unusable) {
            val api = FakeApi()
            api.onRedeem = { HttpResponse(200, body) }
            val platform = ios()
            val sdk = started(launchApp(api, platform, errors = errors))
            val received = collectLinks(sdk)

            assertEquals(PastedLinkResult.FAILED, sdk.redeemPastedLink(COPIED), body)
            assertEquals(DeferredLinkStatus.FAILED, sdk.status(), body)
            assertTrue(platform.store.done, body)
            assertTrue(received.isEmpty(), body)
            assertEquals(PastedLinkResult.ALREADY_HANDLED, sdk.redeemPastedLink(COPIED), body)
            sdk.dispose()
        }
    }

    @Test
    fun aLinkPastedInALaterLaunchIsAlreadyHandled() = runTest {
        val api = FakeApi()
        val platform = ios()
        val first = started(launchApp(api, platform, errors = errors))
        assertEquals(PastedLinkResult.DELIVERED, first.redeemPastedLink(COPIED))
        first.dispose()

        val second = started(launchApp(api, platform, matching = true, errors = errors))
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, second.status())
        assertEquals(PastedLinkResult.ALREADY_HANDLED, second.redeemPastedLink(COPIED))
        assertFalse(second.pasteboardMayContainLink())
        second.skipPastedLink()
        advanceUntilIdle()
        assertEquals(1, api.redeems.size)
        assertTrue(api.matches.isEmpty())
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, second.status())
        second.dispose()
    }

    @Test
    fun twoTapsOnPasteRedeemOnce() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.onRedeem = {
            gate.await()
            redeemed()
        }
        val sdk = started(launchApp(api, ios(), errors = errors))
        val received = collectLinks(sdk)

        val first = async { sdk.redeemPastedLink(COPIED) }
        val second = async { sdk.redeemPastedLink(COPIED) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(PastedLinkResult.DELIVERED, first.await())
        assertEquals(PastedLinkResult.ALREADY_HANDLED, second.await())
        assertEquals(1, api.redeems.size)
        assertEquals(1, received.size)
        sdk.dispose()
    }

    @Test
    fun pastingBeforeStartWorksToo() = runTest {
        val sdk = launchApp(FakeApi(), ios(), errors = errors)
        val received = collectLinks(sdk)
        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        assertEquals(1, received.size)
        started(sdk)
        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        sdk.dispose()
    }

    @Test
    fun aPasteWithoutABundleIdentifierFailsWithoutARequest() = runTest {
        val api = FakeApi()
        val platform = ios().apply { identifier = null }
        val sdk = started(launchApp(api, platform, errors = errors))
        assertEquals(PastedLinkResult.FAILED, sdk.redeemPastedLink(COPIED))
        assertTrue(api.redeems.isEmpty())
        assertFalse(platform.store.done)
        sdk.dispose()
    }

    @Test
    fun pastingAfterDisposeIsRefused() = runTest {
        val api = FakeApi()
        val sdk = started(launchApp(api, ios(), errors = errors))
        sdk.dispose()
        assertFailsWith<IllegalStateException> { sdk.redeemPastedLink(COPIED) }
        assertFalse(sdk.pasteboardMayContainLink())
        sdk.skipPastedLink()
        assertTrue(api.redeems.isEmpty())
    }

    @Test
    fun enablingLaterEnablesThePasteFlow() = runTest {
        val api = FakeApi()
        val sdk = started(launchApp(api, ios(), deferredLinks = false, errors = errors))
        assertEquals(PastedLinkResult.DISABLED, sdk.redeemPastedLink(COPIED))

        sdk.enableDeferredLinks()
        advanceUntilIdle()
        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        sdk.dispose()
    }

    // --- is there anything to paste? -------------------------------------------------------------

    @Test
    fun pasteboardMayContainLinkAsksThePlatformWithoutReadingAnything() = runTest {
        val platform = ios()
        val sdk = started(launchApp(FakeApi(), platform, errors = errors))
        assertFalse(sdk.pasteboardMayContainLink())
        platform.pasteboardHasUrl = true
        assertTrue(sdk.pasteboardMayContainLink())
        assertEquals(2, platform.pasteboardChecks)

        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        assertFalse(sdk.pasteboardMayContainLink(), "false once done is stored")
        assertEquals(2, platform.pasteboardChecks)
        sdk.dispose()
    }

    // === Flow B: probabilistic matching ==========================================================

    @Test
    fun matchingNeedsItsOwnSwitch() = runTest {
        val api = FakeApi()
        val platform = ios()
        val sdk = started(launchApp(api, platform, errors = errors)) // deferredLinks only.

        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        sdk.skipPastedLink()
        assertEquals(PastedLinkResult.NOT_OURS, sdk.redeemPastedLink("something else"))
        advanceUntilIdle()

        assertTrue(api.matches.isEmpty())
        assertEquals(0, platform.pasteboardChecks, "the SDK does not look at the pasteboard by itself")
        assertTrue(platform.store.writes.isEmpty())
        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        sdk.dispose()
    }

    @Test
    fun matchingWithoutDeferredLinksDoesNothing() = runTest {
        val api = FakeApi()
        val platform = ios()
        val sdk = started(launchApp(api, platform, deferredLinks = false, matching = true, errors = errors))
        assertEquals(DeferredLinkStatus.DISABLED, sdk.status())
        assertTrue(api.matches.isEmpty())
        assertTrue(platform.store.writes.isEmpty())
        sdk.dispose()
    }

    @Test
    fun deliversAProbabilisticMatchAndMarksItAsAGuess() = runTest {
        val api = FakeApi()
        val platform = ios()
        val sdk = launchApp(api, platform, matching = true, errors = errors)
        val received = collectLinks(sdk)
        assertEquals(DeferredLinkStatus.CHECKING, sdk.status())
        started(sdk)

        val request = api.matches.single()
        assertEquals("POST", request.method)
        assertEquals("$API/v1/public/deferred-links/match", request.url)
        assertEquals(mapOf("Content-Type" to "application/json", "Accept" to "application/json"), request.headers)
        assertEquals(
            setOf("platform", "bundleId", "deviceFamily", "locale", "sdkVersion"), request.json.keys,
            "nothing else about the device is ever sent",
        )
        assertEquals("ios", request.json["platform"]!!.jsonPrimitive.content)
        assertEquals("com.example.App", request.json["bundleId"]!!.jsonPrimitive.content)
        assertEquals("iphone", request.json["deviceFamily"]!!.jsonPrimitive.content)
        assertEquals("en-GB", request.json["locale"]!!.jsonPrimitive.content)
        assertEquals(STRYBOK_SDK_VERSION, request.json["sdkVersion"]!!.jsonPrimitive.content)

        val link = received.single()
        assertTrue(link.isDeferred)
        assertTrue(link.isProbabilisticMatch)
        assertEquals("https://$HOST/promo42", link.url)

        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        assertEquals(listOf("attempts=1", "done=1"), platform.store.writes)
        assertEquals(1, platform.pasteboardChecks)
        assertTrue(api.events.isEmpty(), "no app_open for a deferred link")
        assertTrue(api.redeems.isEmpty())
        assertNull(sdk.getInitialLink())
        assertEquals(emptyList(), errors)
        sdk.dispose()
    }

    @Test
    fun matchingRunsOnlyAfterStartAndNeverTwice() = runTest {
        val api = FakeApi()
        val sdk = launchApp(api, ios(), matching = true, errors = errors)
        advanceUntilIdle()
        assertTrue(api.matches.isEmpty(), "not before start")
        started(sdk)
        sdk.start()
        sdk.skipPastedLink()
        advanceUntilIdle()
        assertEquals(1, api.matches.size)
        sdk.dispose()
    }

    @Test
    fun waitsForThePasteWhenThePasteboardMayHoldALink() = runTest {
        val api = FakeApi()
        val platform = ios().apply { pasteboardHasUrl = true }
        val sdk = started(launchApp(api, platform, matching = true, errors = errors))

        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        assertTrue(api.matches.isEmpty())
        assertTrue(platform.store.writes.isEmpty(), "no attempt is counted while waiting")

        sdk.skipPastedLink()
        assertEquals(DeferredLinkStatus.CHECKING, sdk.status())
        sdk.skipPastedLink()
        advanceUntilIdle()
        sdk.skipPastedLink()
        advanceUntilIdle()

        assertEquals(1, api.matches.size)
        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        sdk.dispose()
    }

    @Test
    fun aPasteThatIsNotOursLetsMatchingStart() = runTest {
        val api = FakeApi()
        val platform = ios().apply { pasteboardHasUrl = true }
        val sdk = started(launchApp(api, platform, matching = true, errors = errors))
        val received = collectLinks(sdk)

        assertEquals(PastedLinkResult.NOT_OURS, sdk.redeemPastedLink("https://example.com/article"))
        advanceUntilIdle()

        assertTrue(api.redeems.isEmpty())
        assertEquals(1, api.matches.size)
        assertTrue(received.single().isProbabilisticMatch)
        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        sdk.dispose()
    }

    @Test
    fun aPastedLinkOfOursMeansMatchingNeverRuns() = runTest {
        val api = FakeApi()
        val platform = ios().apply { pasteboardHasUrl = true }
        val sdk = started(launchApp(api, platform, matching = true, errors = errors))
        val received = collectLinks(sdk)

        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        sdk.skipPastedLink()
        advanceUntilIdle()

        assertTrue(api.matches.isEmpty())
        assertFalse(received.single().isProbabilisticMatch)
        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        assertEquals(listOf("done=1"), platform.store.writes)
        sdk.dispose()
    }

    @Test
    fun aRefusedPasteAlsoEndsTheWaitWithoutMatching() = runTest {
        val api = FakeApi()
        api.onRedeem = { HttpResponse(404, "") }
        val platform = ios().apply { pasteboardHasUrl = true }
        val sdk = started(launchApp(api, platform, matching = true, errors = errors))

        assertEquals(PastedLinkResult.NOT_FOUND, sdk.redeemPastedLink(COPIED))
        sdk.skipPastedLink()
        advanceUntilIdle()
        assertTrue(api.matches.isEmpty(), "flow A got a definite answer: neither flow does anything again")
        assertEquals(DeferredLinkStatus.NOT_FOUND, sdk.status())
        sdk.dispose()
    }

    @Test
    fun aPasteThatFailedOnTheNetworkKeepsWaiting() = runTest {
        val api = FakeApi()
        api.onRedeem = { HttpResponse(503, "") }
        val platform = ios().apply { pasteboardHasUrl = true }
        val sdk = started(launchApp(api, platform, matching = true, maxRetries = 0, errors = errors))

        assertEquals(PastedLinkResult.FAILED, sdk.redeemPastedLink(COPIED))
        advanceUntilIdle()
        assertTrue(api.matches.isEmpty())
        assertEquals(DeferredLinkStatus.AWAITING_PASTE, sdk.status())
        sdk.dispose()
    }

    @Test
    fun aLinkPastedWhileTheMatchIsInFlightWinsAndNothingIsDeliveredTwice() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.onMatch = {
            gate.await()
            redeemed(url = "https://$HOST/guess", matched = true)
        }
        val platform = ios()
        val sdk = launchApp(api, platform, matching = true, errors = errors)
        val received = collectLinks(sdk)
        sdk.start()
        runCurrent()
        assertEquals(1, api.matches.size)

        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("https://$HOST/promo42"), received.map { it.url })
        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        assertEquals(PastedLinkResult.ALREADY_HANDLED, sdk.redeemPastedLink(COPIED))
        sdk.dispose()
    }

    @Test
    fun aMatchDeliveredFirstMakesALaterPasteAlreadyHandled() = runTest {
        val api = FakeApi()
        val sdk = started(launchApp(api, ios(), matching = true, errors = errors))
        val received = collectLinks(sdk)
        assertEquals(PastedLinkResult.ALREADY_HANDLED, sdk.redeemPastedLink(COPIED))
        assertEquals(1, received.size)
        assertTrue(api.redeems.isEmpty())
        sdk.dispose()
    }

    @Test
    fun matchingDoesNothingOnceDoneIsStored() = runTest {
        val api = FakeApi()
        val platform = ios()
        val first = started(launchApp(api, platform, matching = true, errors = errors))
        first.dispose()

        val second = launchApp(api, platform, matching = true, errors = errors)
        val received = collectLinks(second)
        started(second)
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, second.status())
        assertEquals(1, api.matches.size)
        assertTrue(received.isEmpty())
        assertEquals(1, platform.pasteboardChecks, "not even the pasteboard question is asked again")
        second.dispose()
    }

    @Test
    fun threeLaunchesThatCannotMatchThenFailed() = runTest {
        val api = FakeApi()
        api.onMatch = { HttpResponse(503, "") }
        val platform = ios()
        repeat(3) { launchNumber ->
            val sdk = started(launchApp(api, platform, matching = true, maxRetries = 1, errors = errors))
            assertEquals(DeferredLinkStatus.PENDING, sdk.status())
            assertEquals(launchNumber + 1, platform.store.attempts)
            assertFalse(platform.store.done)
            sdk.dispose()
        }
        assertEquals(6, api.matches.size, "each launch retries with the same body")
        assertEquals(1, api.matches.map { it.body }.toSet().size)

        val fourth = started(launchApp(api, platform, matching = true, errors = errors))
        assertEquals(DeferredLinkStatus.FAILED, fourth.status())
        assertEquals(6, api.matches.size, "the fourth launch asks nobody")
        assertEquals(listOf("attempts=1", "attempts=2", "attempts=3", "done=1"), platform.store.writes)
        fourth.dispose()
    }

    @Test
    fun countsTheAttemptBeforeTheMatchRequest() = runTest {
        val api = FakeApi()
        val platform = ios()
        var seen = -1
        api.onMatch = {
            seen = platform.store.attempts
            redeemed(matched = true)
        }
        started(launchApp(api, platform, matching = true, errors = errors)).dispose()
        assertEquals(1, seen)
    }

    @Test
    fun aMatchKilledInFlightHasUsedAnAttempt() = runTest {
        val api = FakeApi()
        api.onMatch = { awaitCancellation() }
        val platform = ios()
        val first = launchApp(api, platform, matching = true, errors = errors)
        first.start()
        runCurrent()
        first.dispose() // The process dies here.
        advanceUntilIdle()
        assertEquals(listOf("attempts=1"), platform.store.writes)

        // A match is used up by the request that gets it: the next launch usually gets a 404.
        api.onMatch = { HttpResponse(404, "") }
        val second = started(launchApp(api, platform, matching = true, errors = errors))
        assertEquals(DeferredLinkStatus.NOT_FOUND, second.status())
        assertEquals(listOf("attempts=1", "attempts=2", "done=1"), platform.store.writes)
        second.dispose()
    }

    @Test
    fun aMatchKilledRightAfterDoneWasStoredNeverDelivers() = runTest {
        val api = FakeApi()
        val platform = ios()
        val first = launchApp(api, platform, matching = true, errors = errors)
        val lost = collectLinks(first)
        platform.store.afterSet = { name -> if (name == "done") first.dispose() }
        first.start()
        advanceUntilIdle()
        assertTrue(platform.store.done)
        assertTrue(lost.isEmpty())

        platform.store.afterSet = null
        val second = launchApp(api, platform, matching = true, errors = errors)
        val again = collectLinks(second)
        started(second)
        assertEquals(DeferredLinkStatus.NOT_FIRST_LAUNCH, second.status())
        assertTrue(again.isEmpty())
        assertEquals(1, api.matches.size)
        second.dispose()
    }

    @Test
    fun aNormalLinkWinsOverAMatchBeforeTheRequest() = runTest {
        val api = FakeApi()
        val platform = ios()
        val sdk = launchApp(
            api, platform, matching = true, linkSource = FakeLinkSource(initialLink = INVITE), errors = errors,
        )
        val received = collectLinks(sdk)
        started(sdk)

        assertEquals(DeferredLinkStatus.SUPERSEDED, sdk.status())
        assertTrue(api.matches.isEmpty())
        assertEquals(listOf(INVITE), received.map { it.url })
        assertEquals(listOf("attempts=1", "done=1"), platform.store.writes)
        sdk.dispose()
    }

    @Test
    fun aNormalLinkWinsOverAMatchThatIsInFlight() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.onMatch = {
            gate.await()
            redeemed(matched = true)
        }
        val links = FakeLinkSource()
        val platform = ios()
        val sdk = launchApp(api, platform, matching = true, linkSource = links, errors = errors)
        val received = collectLinks(sdk)
        sdk.start()
        runCurrent()
        assertEquals(1, api.matches.size)

        links.emit(INVITE)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DeferredLinkStatus.SUPERSEDED, sdk.status())
        assertEquals(listOf(INVITE), received.map { it.url })
        assertTrue(platform.store.done)
        sdk.dispose()
    }

    @Test
    fun aDeviceThatIsNeitherPhoneNorTabletIsNotMatched() = runTest {
        for (broken in listOf<FakeDeferredPlatform.() -> Unit>({ family = null }, { language = null }, { identifier = null })) {
            val api = FakeApi()
            val platform = ios().apply(broken)
            val sdk = started(launchApp(api, platform, matching = true, errors = errors))
            assertEquals(DeferredLinkStatus.UNAVAILABLE, sdk.status())
            assertTrue(api.matches.isEmpty())
            assertTrue(platform.store.done)
            sdk.dispose()
        }
    }

    @Test
    fun everyDeviceFamilyIsSentAsItIs() = runTest {
        for (family in listOf("iphone", "ipad", "ipod")) {
            val api = FakeApi()
            val platform = ios().apply {
                this.family = family
                language = "zh-Hans-CN"
            }
            started(launchApp(api, platform, matching = true, errors = errors)).dispose()
            assertEquals(family, api.matches.single().json["deviceFamily"]!!.jsonPrimitive.content)
            assertEquals("zh-Hans-CN", api.matches.single().json["locale"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun answersOfTheMatchEndpoint() = runTest {
        val outcomes = listOf<Pair<suspend (HttpRequest) -> HttpResponse, DeferredLinkStatus>>(
            // The usual answer: nothing fitted, or more than one did.
            Pair({ HttpResponse(404, """{"error":{"code":"DEFERRED_LINK_NOT_FOUND"}}""") }, DeferredLinkStatus.NOT_FOUND),
            Pair({ HttpResponse(422, "") }, DeferredLinkStatus.NOT_FOUND),
            // Not marked as a guess: this is not an answer of the match endpoint.
            Pair({ redeemed(matched = false) }, DeferredLinkStatus.FAILED),
            Pair({ redeemed(url = "https://evil.example.com/promo42", matched = true) }, DeferredLinkStatus.FAILED),
            Pair({ HttpResponse(200, "<html>") }, DeferredLinkStatus.FAILED),
            Pair({ HttpResponse(429, "") }, DeferredLinkStatus.PENDING),
            Pair({ HttpResponse(500, "") }, DeferredLinkStatus.PENDING),
            Pair({ throw RuntimeException("offline") }, DeferredLinkStatus.PENDING),
            Pair({ awaitCancellation() }, DeferredLinkStatus.PENDING),
        )
        for ((index, outcome) in outcomes.withIndex()) {
            val (answer, expected) = outcome
            val api = FakeApi()
            api.onMatch = answer
            val platform = ios()
            val sdk = launchApp(api, platform, matching = true, maxRetries = 1, errors = errors)
            val received = collectLinks(sdk)
            started(sdk)

            assertEquals(expected, sdk.status(), "outcome $index")
            assertEquals(expected.isFinal, platform.store.done, "outcome $index")
            assertTrue(received.isEmpty(), "outcome $index")
            // 4xx, 429 and 200 are answers and are not retried.
            assertEquals(if (index < 6) 1 else 2, api.matches.size, "outcome $index")
            sdk.dispose()
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun onlyOneInstanceInAProcessMatches() = runTest {
        val api = FakeApi()
        val platform = ios().apply { claims = false }
        val sdk = started(launchApp(api, platform, matching = true, errors = errors))
        assertEquals(DeferredLinkStatus.PENDING, sdk.status())
        assertTrue(api.matches.isEmpty())
        assertEquals(0, platform.pasteboardChecks)
        // The paste flow is the person's own action and still works.
        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        sdk.dispose()
    }

    @Test
    fun enablingLaterRunsTheMatchOnce() = runTest {
        val api = FakeApi()
        val sdk = started(launchApp(api, ios(), deferredLinks = false, matching = true, errors = errors))
        assertTrue(api.matches.isEmpty())
        sdk.enableDeferredLinks()
        sdk.enableDeferredLinks()
        advanceUntilIdle()
        assertEquals(1, api.matches.size)
        assertEquals(DeferredLinkStatus.DELIVERED, sdk.status())
        sdk.dispose()
    }
}
