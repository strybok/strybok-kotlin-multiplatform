package com.strybok.kmp.internal

/**
 * The parts of a link the API needs: lower-cased host, slug and the campaign query
 * ([campaignQuery]: empty, or `?utm_…=…`).
 */
internal class LinkUrl(
    val host: String,
    val slug: String,
    val campaignQuery: String = "",
    /**
     * The query exactly as received, without `?` and without the fragment. Never sent anywhere;
     * only read for the click id of a pasted link.
     */
    val rawQuery: String = "",
) {
    /**
     * What is sent to the API. Of the query only the campaign parameters are part of it; the
     * rest of the query and the fragment never are.
     */
    val lookup: String get() = "https://$host/$slug$campaignQuery"
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
 * Query and fragment are allowed and do not decide anything here; of the query only the
 * campaign parameters are kept ([campaignQuery]). Whitespace, control characters and backslashes
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
    val queryEnd = url.indexOf('#', pathEnd).let { if (it == -1) url.length else it }
    val rawQuery = if (pathEnd < queryEnd && url[pathEnd] == '?') url.substring(pathEnd + 1, queryEnd) else ""
    return LinkUrl(host, slug, campaignQuery(rawQuery), rawQuery)
}

private val CAMPAIGN_QUERY_KEYS = listOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")
private const val MAX_CAMPAIGN_VALUE_LENGTH = 64

/**
 * The campaign query of the lookup URL (SDK contract, "Campaign query"): `""`, or `?` followed
 * by those of the five `utm_*` parameters of [rawQuery] (a query string without `?` and
 * without the fragment) whose first occurrence has a valid value, in a fixed order.
 *
 * Every other parameter is dropped, it may hold private data. Never throws.
 */
internal fun campaignQuery(rawQuery: String): String {
    if (rawQuery.isEmpty()) return ""
    val values = arrayOfNulls<String>(CAMPAIGN_QUERY_KEYS.size)
    val seen = BooleanArray(CAMPAIGN_QUERY_KEYS.size)
    for (part in rawQuery.split('&')) {
        val equals = part.indexOf('=')
        val key = decodeUnreserved(if (equals < 0) part else part.substring(0, equals)) ?: continue
        val index = CAMPAIGN_QUERY_KEYS.indexOf(key)
        if (index < 0 || seen[index]) continue
        seen[index] = true // The first occurrence counts, even when its value is dropped.
        val value = if (equals < 0) null else decodeUnreserved(part.substring(equals + 1))
        if (value != null && value.length in 1..MAX_CAMPAIGN_VALUE_LENGTH) values[index] = value
    }
    return buildString {
        for (index in CAMPAIGN_QUERY_KEYS.indices) {
            val value = values[index] ?: continue
            append(if (isEmpty()) '?' else '&').append(CAMPAIGN_QUERY_KEYS[index]).append('=').append(value)
        }
    }
}

/**
 * The raw value of the first parameter of [rawQuery] whose key is [name] (`""` when it has no
 * `=`), or `null` when there is no such parameter. The key is compared after percent-decoding,
 * as a standard query parser would; the value is returned as it stands.
 */
internal fun firstQueryValue(rawQuery: String, name: String): String? {
    if (rawQuery.isEmpty()) return null
    for (part in rawQuery.split('&')) {
        val equals = part.indexOf('=')
        val key = decodeUnreserved(if (equals < 0) part else part.substring(0, equals)) ?: continue
        if (key == name) return if (equals < 0) "" else part.substring(equals + 1)
    }
    return null
}

/**
 * Percent-decodes [raw] (`+` is a space) and returns the result only when it consists of
 * `A–Z a–z 0–9 . _ ~ -`; `null` otherwise.
 *
 * Campaign keys and valid values are made of these characters only, so anything else — a space,
 * a malformed escape, any byte of a multi-byte UTF-8 sequence (valid or not) — can only belong
 * to a key that is not ours or to a value that is dropped. No UTF-8 decoding is needed for that.
 */
private fun decodeUnreserved(raw: String): String? {
    val decoded = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        var char = raw[index]
        if (char == '%') {
            if (index + 2 >= raw.length) return null
            val high = hexValue(raw[index + 1])
            val low = hexValue(raw[index + 2])
            if (high < 0 || low < 0) return null
            char = (high * 16 + low).toChar()
            index += 3
        } else {
            index += 1
        }
        if (!(char.isAsciiLetterOrDigit() || char == '.' || char == '_' || char == '~' || char == '-')) return null
        decoded.append(char)
    }
    return decoded.toString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
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
