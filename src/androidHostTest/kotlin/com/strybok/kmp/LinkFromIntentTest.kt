package com.strybok.kmp

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Host tests run against the stub android.jar, where `Intent` methods are not implemented, so
 * the decision logic behind `handleIntent` is tested through [linkFromIntent].
 */
class LinkFromIntentTest {
    @Test
    fun takesTheUrlOfAFreshViewIntent() {
        assertEquals(INVITE, linkFromIntent(Intent.ACTION_VIEW, INVITE, 0))
        assertEquals(INVITE, linkFromIntent(Intent.ACTION_VIEW, INVITE, Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Test
    fun ignoresOtherIntents() {
        assertNull(linkFromIntent(Intent.ACTION_MAIN, null, 0))
        assertNull(linkFromIntent(Intent.ACTION_MAIN, INVITE, 0))
        assertNull(linkFromIntent(Intent.ACTION_VIEW, null, 0))
        assertNull(linkFromIntent(null, INVITE, 0))
    }

    @Test
    fun ignoresAnIntentReplayedFromTheRecentsScreen() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertNull(linkFromIntent(Intent.ACTION_VIEW, INVITE, flags))
    }
}
