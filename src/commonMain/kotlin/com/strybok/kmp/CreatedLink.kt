package com.strybok.kmp

/** A link made by [Strybok.createLink]. */
public class CreatedLink internal constructor(
    /** The short link to share, always `https://`. */
    public val url: String,
    public val slug: String,
    /** The in-app route the link opens, as stored by the service. */
    public val routePath: String,
    public val linkId: String,
) {
    override fun toString(): String = "CreatedLink($url, linkId: $linkId)"
}
