package com.strybok.kmp

import android.content.Intent

/**
 * Hands the link of an `ACTION_VIEW` intent to the SDK. Call it from `Activity.onCreate`
 * (only when `savedInstanceState == null`, so a recreated activity does not replay the link)
 * and from `onNewIntent`.
 *
 * Intents re-delivered when the app is reopened from the recents screen are ignored.
 *
 * @return `true` when the intent carried a link of this app and the SDK took it.
 */
public fun Strybok.handleIntent(intent: Intent?): Boolean {
    if (intent == null) return false
    val url = linkFromIntent(intent.action, intent.dataString, intent.flags) ?: return false
    return handleLink(url)
}

/** The URL of a link intent, or `null` when the intent is not a fresh link tap. */
internal fun linkFromIntent(action: String?, data: String?, flags: Int): String? {
    if (action != Intent.ACTION_VIEW || data == null) return null
    if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    return data
}
