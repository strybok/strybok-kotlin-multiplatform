package com.strybok.kmp.internal

import com.strybok.kmp.ParameterLinks

/**
 * The parts of a link the API needs: lower-cased host, the path as received and what follows it
 * in [lookup].
 */
internal class LinkUrl(
    val host: String,
    /** The path exactly as received (`/` for an empty one): for a slug link `/` and the slug. */
    val path: String,
    /** The campaign query of the tapped URL: empty, or `?utm_…=…`. */
    val campaignQuery: String = "",
    /**
     * The query exactly as received, without `?` and without the fragment. Never sent anywhere;
     * only read for the click id of a pasted link.
     */
    val rawQuery: String = "",
    /**
     * What [lookup] has after the path: [campaignQuery], or with the `parameterLinks` option the
     * payload pairs (for a long link `link`, `apn` and `ibi`) followed by the campaign pairs.
     */
    private val lookupQuery: String = campaignQuery,
) {
    /** The slug of a single-slug path (`AbC` of `/AbC`); `null` for any other path. */
    val slug: String? get() = if (isSlugPath(path)) path.substring(1) else null

    /**
     * What is sent to the API. Of the query only the campaign parameters (and with the
     * `parameterLinks` option the configured payload keys) are part of it; the rest of the query
     * and the fragment never are.
     */
    val lookup: String get() = "https://$host$path$lookupQuery"
}

private const val SCHEME_PREFIX = "https://"
private const val MAX_SLUG_LENGTH = 64

/**
 * Strict parser for Strybok links; deliberately independent of platform URL classes, which
 * disagree on edge cases and normalise paths. Returns `null` unless all of these hold:
 *
 * - scheme is `https` (case-insensitive, as schemes are);
 * - host, lower-cased, is in [allowedHosts] (already lower-case);
 * - no user info, and no port other than an explicit `443`;
 * - the path is exactly `/` followed by 1..64 characters of `[A-Za-z0-9_-]` — or, only with
 *   [parameterLinks], a route path or a Firebase long link (SDK contract, "Links without an API
 *   call"), the path taken exactly as received.
 *
 * Query and fragment are allowed and do not decide anything here (except the `link` parameter of
 * a long link); of the query only the campaign parameters are kept ([campaignQuery]), and with
 * [parameterLinks] the configured payload keys. Whitespace, control characters and backslashes
 * anywhere make the URL invalid: no well-formed link contains them, and URL parsers treat them
 * inconsistently.
 *
 * Deferred links are always stored links, so pasted links and the URLs of deferred-link answers
 * are parsed without [parameterLinks].
 */
internal fun parseLinkUrl(url: String, allowedHosts: Set<String>, parameterLinks: ParameterLinks? = null): LinkUrl? {
    if (!url.regionMatches(0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length, ignoreCase = true)) return null
    for (c in url) {
        if (c <= ' ' || c == '\\' || c == '\u007f') return null
    }

    val authorityStart = SCHEME_PREFIX.length
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
    // No path at all. Only the option reads an empty path as `/`.
    if (parameterLinks == null && (authorityEnd == -1 || url[authorityEnd] != '/')) return null
    val pathStart = if (authorityEnd == -1) url.length else authorityEnd
    val authority = url.substring(authorityStart, pathStart)
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

    val pathEnd = url.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it == -1) url.length else it }
    val path = url.substring(pathStart, pathEnd)
    val queryEnd = url.indexOf('#', pathEnd).let { if (it == -1) url.length else it }
    val rawQuery = if (pathEnd < queryEnd && url[pathEnd] == '?') url.substring(pathEnd + 1, queryEnd) else ""
    val campaign = campaignQuery(rawQuery)
    if (isSlugPath(path)) {
        val payload = parameterLinks?.let { payloadPairs(it.payloadKeys, rawQuery) }.orEmpty()
        return LinkUrl(host, path, campaign, rawQuery, lookupQuery(payload, campaign))
    }
    val options = parameterLinks ?: return null

    val routePath = path.ifEmpty { "/" }
    if (routePath == "/") {
        val link = firstQueryValue(rawQuery, "link")
        if (link != null) {
            // A Firebase long link: ours only when `link` is a deep link.
            val pairs = longLinkPairs(link, rawQuery, options.payloadKeys) ?: return null
            return LinkUrl(host, routePath, campaign, rawQuery, lookupQuery(pairs, campaign))
        }
    }
    if (!isRoutePath(routePath)) return null
    val payload = payloadPairs(options.payloadKeys, rawQuery)
    return LinkUrl(host, routePath, campaign, rawQuery, lookupQuery(payload, campaign))
}

/** `^/[A-Za-z0-9_-]{1,64}$` */
private fun isSlugPath(path: String): Boolean =
    path.length in 2..MAX_SLUG_LENGTH + 1 && path[0] == '/' &&
        (1 until path.length).all { index -> path[index].let { it.isAsciiLetterOrDigit() || it == '_' || it == '-' } }

/** `?` and [pairs] followed by the pairs of [campaignQuery] (`""` or `?…`); `""` when both are empty. */
private fun lookupQuery(pairs: String, campaignQuery: String): String = when {
    pairs.isEmpty() -> campaignQuery
    campaignQuery.isEmpty() -> "?$pairs"
    else -> "?$pairs&${campaignQuery.substring(1)}"
}

private const val MAX_ROUTE_PATH_LENGTH = 512

/** What a route path may hold besides letters, digits and escapes. */
private const val PATH_PUNCTUATION = "-._~!\$&'()*+,;=:@/"

/** Paths browsers and crawlers ask for on their own, never a link: these two and everything under them. */
private val RESERVED_PREFIXES = listOf("/.well-known", "/_strybok")

/** Reserved as well, as exactly these paths. */
private val RESERVED_FILES = setOf(
    "/favicon.ico", "/robots.txt", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/sitemap.xml",
    "/ads.txt", "/app-ads.txt", "/security.txt", "/humans.txt", "/manifest.json", "/site.webmanifest",
    "/browserconfig.xml",
)

/**
 * A route path, checked exactly as received (never decoded or normalised): 1–512 characters
 * starting with `/`, only `A–Z a–z 0–9 - . _ ~ ! $ & ' ( ) * + , ; = : @ %` and `/` with every `%`
 * followed by two hexadecimal digits, no `//`, no segment that is `.` or `..` once `%2e` is read
 * as `.`, and not reserved.
 */
internal fun isRoutePath(path: String): Boolean {
    if (path.length !in 1..MAX_ROUTE_PATH_LENGTH || path[0] != '/') return false
    var index = 0
    while (index < path.length) {
        val char = path[index]
        if (char == '%') {
            if (index + 2 >= path.length || hexValue(path[index + 1]) < 0 || hexValue(path[index + 2]) < 0) return false
            index += 3
        } else {
            if (!(char.isAsciiLetterOrDigit() || char in PATH_PUNCTUATION)) return false
            index += 1
        }
    }
    if ("//" in path) return false
    for (segment in path.split('/')) {
        val dots = segment.replace("%2e", ".").replace("%2E", ".")
        if (dots == "." || dots == "..") return false
    }
    if (RESERVED_PREFIXES.any { path == it || path.startsWith("$it/") }) return false
    return path !in RESERVED_FILES
}

private const val MAX_PAYLOAD_VALUE_LENGTH = 256

/**
 * The payload query (SDK contract, "What is sent"): for each of [keys], in their order, its first
 * occurrence in [rawQuery] when the value decodes and is 1–256 UTF-16 code units without control
 * characters, as `key=value` with the value re-encoded; joined with `&`. A first occurrence that
 * fails drops the key: a later one never counts. Never throws.
 */
internal fun payloadPairs(keys: List<String>, rawQuery: String): String = keys.mapNotNull { key ->
    val value = firstQueryValue(rawQuery, key)?.let(::decodeQueryComponent) ?: return@mapNotNull null
    val valid = value.length in 1..MAX_PAYLOAD_VALUE_LENGTH && value.none { it <= '\u001f' || it in '\u007f'..'\u009f' }
    if (valid) "$key=${percentEncode(value)}" else null
}.joinToString("&")

/**
 * `link=…` with the rebuilt deep link, then `apn=…` and `ibi=…` when [rawQuery] has them (their
 * first occurrence, sent as it is, even empty); `null` when the URL is not ours: `link` does not
 * decode or is no deep link, or `apn` or `ibi` does not decode.
 */
private fun longLinkPairs(rawLink: String, rawQuery: String, keys: List<String>): String? {
    val deep = decodeQueryComponent(rawLink)?.let { deepLink(it, keys) } ?: return null
    return buildString {
        append("link=").append(percentEncode(deep))
        for (name in listOf("apn", "ibi")) {
            val raw = firstQueryValue(rawQuery, name) ?: continue
            val value = decodeQueryComponent(raw) ?: return null
            append('&').append(name).append('=').append(percentEncode(value))
        }
    }
}

/**
 * The deep link of a long link rebuilt as `{scheme}://{host}{path}` (both lower-cased, `/` for an
 * empty path) with the payload query of its own query and without its fragment; `null` unless
 * [value] is `http` or `https` (any case), `://` and a host of `A–Z a–z 0–9 . - _` (so no user
 * info, port or IP literal), with no character at or below U+0020, no U+007F and no `\`.
 */
private fun deepLink(value: String, keys: List<String>): String? {
    if (value.any { it <= ' ' || it == '\u007f' || it == '\\' }) return null
    val colon = value.indexOf(':')
    if (colon < 0) return null
    val scheme = value.substring(0, colon).asciiLowercase()
    if (scheme != "http" && scheme != "https" || !value.startsWith("//", colon + 1)) return null
    val hostStart = colon + 3
    val hostEnd = value.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it == -1) value.length else it }
    val host = value.substring(hostStart, hostEnd)
    if (host.isEmpty() || !host.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
    val pathEnd = value.indexOfAny(charArrayOf('?', '#'), hostEnd).let { if (it == -1) value.length else it }
    val queryEnd = value.indexOf('#', pathEnd).let { if (it == -1) value.length else it }
    val query = if (pathEnd < queryEnd && value[pathEnd] == '?') value.substring(pathEnd + 1, queryEnd) else ""
    val payload = payloadPairs(keys, query)
    return "$scheme://${host.asciiLowercase()}${value.substring(hostEnd, pathEnd).ifEmpty { "/" }}" +
        if (payload.isEmpty()) "" else "?$payload"
}

/**
 * Percent-decodes one key or value of a query as UTF-8, `+` being a space. `null` for a `%`
 * without two hexadecimal digits, or bytes that are not well-formed UTF-8. Characters that are
 * not escaped are taken as they are.
 */
internal fun decodeQueryComponent(raw: String): String? {
    val input = raw.encodeToByteArray()
    val bytes = ByteArray(input.size)
    var size = 0
    var index = 0
    while (index < input.size) {
        val byte = input[index]
        when (byte) {
            '+'.code.toByte() -> {
                bytes[size++] = ' '.code.toByte()
                index += 1
            }
            '%'.code.toByte() -> {
                if (index + 2 >= input.size) return null
                val high = hexValue(input[index + 1].toInt().toChar())
                val low = hexValue(input[index + 2].toInt().toChar())
                if (high < 0 || low < 0) return null
                bytes[size++] = (high * 16 + low).toByte()
                index += 3
            }
            else -> {
                bytes[size++] = byte
                index += 1
            }
        }
    }
    return decodeUtf8(bytes, size)
}

/**
 * [bytes] up to [size] as UTF-8, or `null` when they are not well-formed (The Unicode Standard,
 * table 3-7: no overlong forms, no surrogates, nothing above U+10FFFF). Written out rather than
 * left to the platform, so that every target accepts and refuses the same bytes.
 */
private fun decodeUtf8(bytes: ByteArray, size: Int): String? {
    val text = StringBuilder(size)
    var index = 0
    fun continuation(offset: Int, range: IntRange = 0x80..0xBF): Int? =
        (bytes.getOrNull(index + offset)?.toInt()?.and(0xff))?.takeIf { index + offset < size && it in range }
    while (index < size) {
        val first = bytes[index].toInt() and 0xff
        val codePoint: Int
        val length: Int
        when (first) {
            in 0x00..0x7F -> {
                codePoint = first
                length = 1
            }
            in 0xC2..0xDF -> {
                val second = continuation(1) ?: return null
                codePoint = (first and 0x1F shl 6) or (second and 0x3F)
                length = 2
            }
            in 0xE0..0xEF -> {
                val range = when (first) {
                    0xE0 -> 0xA0..0xBF
                    0xED -> 0x80..0x9F
                    else -> 0x80..0xBF
                }
                val second = continuation(1, range) ?: return null
                val third = continuation(2) ?: return null
                codePoint = (first and 0x0F shl 12) or (second and 0x3F shl 6) or (third and 0x3F)
                length = 3
            }
            in 0xF0..0xF4 -> {
                val range = when (first) {
                    0xF0 -> 0x90..0xBF
                    0xF4 -> 0x80..0x8F
                    else -> 0x80..0xBF
                }
                val second = continuation(1, range) ?: return null
                val third = continuation(2) ?: return null
                val fourth = continuation(3) ?: return null
                codePoint = (first and 0x07 shl 18) or (second and 0x3F shl 12) or (third and 0x3F shl 6) or
                    (fourth and 0x3F)
                length = 4
            }
            else -> return null
        }
        if (codePoint < 0x10000) {
            text.append(codePoint.toChar())
        } else {
            val offset = codePoint - 0x10000
            text.append((0xD800 + (offset shr 10)).toChar()).append((0xDC00 + (offset and 0x3FF)).toChar())
        }
        index += length
    }
    return text.toString()
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
