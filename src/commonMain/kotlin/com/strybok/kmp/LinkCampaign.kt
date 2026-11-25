package com.strybok.kmp

/**
 * Campaign values of an opened link — the usual `utm_source`, `utm_medium`, `utm_campaign`,
 * `utm_term` and `utm_content` of web analytics. Each one is `null` when it has no value.
 *
 * They are the defaults the owner set on the link, overridden by the `utm_*` parameters of the
 * URL that was tapped. Whoever shares a link can write any value into its URL, so treat them as
 * labels (for your analytics, say), never as something to trust or to act on.
 */
public class LinkCampaign(
    public val source: String? = null,
    public val medium: String? = null,
    public val campaign: String? = null,
    public val term: String? = null,
    public val content: String? = null,
) {
    /** `true` when the link has no campaign value at all. */
    public val isEmpty: Boolean
        get() = source == null && medium == null && campaign == null && term == null && content == null

    override fun equals(other: Any?): Boolean =
        other is LinkCampaign &&
            source == other.source &&
            medium == other.medium &&
            campaign == other.campaign &&
            term == other.term &&
            content == other.content

    override fun hashCode(): Int = listOf(source, medium, campaign, term, content).hashCode()

    override fun toString(): String =
        "LinkCampaign(source: $source, medium: $medium, campaign: $campaign, term: $term, content: $content)"

    public companion object {
        /** No campaign values. */
        public val EMPTY: LinkCampaign = LinkCampaign()
    }
}
