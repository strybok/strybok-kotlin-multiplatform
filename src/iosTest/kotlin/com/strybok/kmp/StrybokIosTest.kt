package com.strybok.kmp

import com.strybok.kmp.internal.NSUrlSessionTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSURL
import platform.Foundation.NSUserActivity
import platform.Foundation.NSUserActivityTypeBrowsingWeb

@OptIn(ExperimentalCoroutinesApi::class)
class StrybokIosTest {
    @Test
    fun handleUrlForwardsOurLinksAndRejectsOthers() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = PushLinkSource())
        val received = collectLinks(sdk)
        sdk.start()

        assertTrue(sdk.handleUrl(NSURL(string = "$INVITE?token=secret")))
        assertFalse(sdk.handleUrl(NSURL(string = "https://evil.example.com/invite777")))
        assertFalse(sdk.handleUrl(NSURL(string = "myapp://open")))
        advanceUntilIdle()

        assertEquals(listOf("$INVITE?token=secret"), received.map { it.url })
        assertEquals(1, api.resolves.size)
        sdk.dispose()
    }

    @Test
    fun handleUserActivityTakesUniversalLinksOnly() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, source = PushLinkSource())
        val received = collectLinks(sdk)
        sdk.start()

        val web = NSUserActivity(activityType = NSUserActivityTypeBrowsingWeb)
        web.webpageURL = NSURL(string = INVITE)
        assertTrue(sdk.handleUserActivity(web))

        val empty = NSUserActivity(activityType = NSUserActivityTypeBrowsingWeb)
        assertFalse(sdk.handleUserActivity(empty))

        val other = NSUserActivity(activityType = "com.example.handoff")
        assertFalse(sdk.handleUserActivity(other))
        advanceUntilIdle()

        assertEquals(listOf(INVITE), received.map { it.url })
        sdk.dispose()
    }

    /** No HTTP server is available here, so only the failure path of NSURLSession is exercised. */
    @Test
    fun theNsUrlSessionTransportReportsARefusedConnectionAsAnException() = runTest {
        val error = assertFailsWith<Exception> {
            NSUrlSessionTransport.execute(
                HttpRequest("GET", "https://127.0.0.1:1/v1/public/resolve", mapOf("Accept" to "application/json"), null, 5.seconds),
            )
        }
        assertFalse(error is HttpTimeoutException, "$error")
    }
}
