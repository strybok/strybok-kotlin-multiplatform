package com.strybok.kmp

/**
 * A link resolved by the Strybok API.
 *
 * [routePath] and [payload] are plain data chosen by whoever created the link. Treat them like
 * any other untrusted input: map them onto routes your app already knows, never execute them.
 */
public class ResolvedDeepLink internal constructor(
    /** The full URL that opened the app, exactly as received (including query and fragment). */
    public val url: String,
    public val linkId: String,
    public val appId: String,
    /** In-app route, always starting with a single `/` (for example `/invite/abc123`). */
    public val routePath: String,
    /**
     * JSON object attached to the link, decoded to plain values: `String`, `Boolean`, `Long`,
     * `Double`, `List`, `Map` and `null`. Empty when the link has no payload.
     */
    public val payload: Map<String, Any?>,
    /** [payload] as JSON text (`{}` when empty), for apps that prefer their own decoder. */
    public val payloadJson: String,
) {
    override fun toString(): String = "ResolvedDeepLink($routePath, linkId: $linkId)"
}
