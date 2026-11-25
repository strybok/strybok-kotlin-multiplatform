package com.strybok.kmp

/**
 * A link resolved by the Strybok API.
 *
 * [routePath], [payload] and [campaign] are plain data chosen by whoever created or shared the
 * link. Treat them like any other untrusted input: map them onto routes your app already knows,
 * never execute them, and use campaign values as labels only.
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
    /**
     * Campaign values of this open: the link's defaults, overridden by the `utm_*` parameters
     * of the tapped URL. Never `null`; [LinkCampaign.isEmpty] when there are none.
     */
    public val campaign: LinkCampaign = LinkCampaign.EMPTY,
    /**
     * `true` when this link was not tapped just now but restored after the app was installed
     * (see the `deferredLinks` option of [Strybok]); `false` for every link that arrived the
     * normal way. A deferred link can arrive seconds after the first screen was shown and is
     * never returned by [Strybok.getInitialLink].
     */
    public val isDeferred: Boolean = false,
    /**
     * `true` only for a link the service **guessed** from the network address (iOS, the
     * `allowProbabilisticMatching` option). **Check it before acting on the link**: on a shared
     * network the guess can be another person's link, so such a link must never by itself sign
     * anyone in, accept an invitation, apply a personal discount or show personal content.
     */
    public val isProbabilisticMatch: Boolean = false,
) {
    override fun toString(): String = "ResolvedDeepLink($routePath, linkId: $linkId)"
}
