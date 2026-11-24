package com.strybok.kmp

import platform.Foundation.NSURL
import platform.Foundation.NSUserActivity
import platform.Foundation.NSUserActivityTypeBrowsingWeb

/**
 * Hands a URL received by the app to the SDK, e.g. from SwiftUI's `onOpenURL`.
 *
 * Swift: `strybok.handleUrl(url: url)`.
 *
 * @return `true` when the URL is a link of this app and the SDK took it.
 */
public fun Strybok.handleUrl(url: NSURL): Boolean {
    val text = url.absoluteString ?: return false
    return handleLink(text)
}

/**
 * Hands a Universal Link activity to the SDK, from `scene(_:continue:)`,
 * `scene(_:willConnectTo:options:)` (`connectionOptions.userActivities`) or
 * `application(_:continue:restorationHandler:)`.
 *
 * Swift: `strybok.handleUserActivity(userActivity: activity)`.
 *
 * @return `true` when the activity carried a link of this app and the SDK took it.
 */
public fun Strybok.handleUserActivity(userActivity: NSUserActivity): Boolean {
    if (userActivity.activityType != NSUserActivityTypeBrowsingWeb) return false
    val url = userActivity.webpageURL ?: return false
    return handleUrl(url)
}
