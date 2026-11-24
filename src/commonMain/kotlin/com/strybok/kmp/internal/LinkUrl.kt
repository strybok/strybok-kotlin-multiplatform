package com.strybok.kmp.internal

/** The parts of a link the API needs: lower-cased host and slug. */
internal class LinkUrl(val host: String, val slug: String) {
    /** What is sent to the API. Query and fragment are never part of it. */
    val lookup: String get() = "https://$host/$slug"
}

private const val SCHEME_PREFIX = "https://"
private const val MAX_SLUG_LENGTH = 64

/**
 * Strict parser for Strybok links; deliberately independent of platform URL classes, which
 * disagree on edge cases. Returns `null` unless all of these hold:
 *
 * - scheme is `https` (case-insensitive, as schemes are);
 * - host, lower-cased, is in [allowedHosts] (already lower-case);
 * - no user info, and no port other than an explicit `443`;
 * - the path is exactly `/` followed by 1..64 characters of `[A-Za-z0-9_-]`.
 *
 * Query and fragment are allowed and ignored. Whitespace, control characters and backslashes
 * anywhere make the URL invalid: no well-formed link contains them, and URL parsers treat
 * them inconsistently.
 */
internal fun parseLinkUrl(url: String, allowedHosts: Set<String>): LinkUrl? {
    if (!url.regionMatches(0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length, ignoreCase = true)) return null
    for (c in url) {
        if (c <= ' ' || c == '\\' || c == '\u007f') return null
    }

    val authorityStart = SCHEME_PREFIX.length
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
    if (authorityEnd == -1 || url[authorityEnd] != '/') return null // No path at all.
    val authority = url.substring(authorityStart, authorityEnd)
    if ('@' in authority) return null

    val colon = authority.indexOf(':')
    val rawHost = if (colon == -1) authority else authority.substring(0, colon)
    if (colon != -1 && authority.substring(colon + 1) != "443") return null
    // Only plain DNS names: this also excludes IPv6 literals and percent-encoded hosts.
    if (rawHost.isEmpty() || !rawHost.all { it.isAsciiLetterOrDigit() || it == '-' || it == '.' }) {
        return null
    }
    val host = rawHost.asciiLowercase()
    if (host !in allowedHosts) return null

    val pathEnd = url.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it == -1) url.length else it }
    val slug = url.substring(authorityEnd + 1, pathEnd)
    if (slug.isEmpty() || slug.length > MAX_SLUG_LENGTH) return null
    if (!slug.all { it.isAsciiLetterOrDigit() || it == '_' || it == '-' }) return null
    return LinkUrl(host, slug)
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** Locale-independent and limited to ASCII, unlike `String.lowercase()`. */
internal fun String.asciiLowercase(): String =
    buildString(length) { for (c in this@asciiLowercase) append(if (c in 'A'..'Z') c + 32 else c) }

/** Validates the API base URL and removes trailing slashes; a path prefix is preserved. */
internal fun normalizeApiBaseUrl(apiBaseUrl: String): String {
    val trimmed = apiBaseUrl.trim()
    val lower = trimmed.asciiLowercase()
    require(lower.startsWith("https://") || lower.startsWith("http://")) {
        "apiBaseUrl must be an absolute http(s) URL, e.g. https://api.strybok.com"
    }
    require(trimmed.none { it <= ' ' || it == '?' || it == '#' }) {
        "apiBaseUrl must not contain whitespace, a query or a fragment"
    }
    require(trimmed.substringAfter("://").substringBefore('/').isNotEmpty()) { "apiBaseUrl has no host" }
    return trimmed.trimEnd('/')
}

private const val HEX = "0123456789ABCDEF"

/** Percent-encodes everything except RFC 3986 unreserved characters. */
internal fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c.isAsciiLetterOrDigit() || c == '-' || c == '.' || c == '_' || c == '~') {
            append(c)
        } else {
            append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0x0f])
        }
    }
}
