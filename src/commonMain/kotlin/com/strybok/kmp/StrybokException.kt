package com.strybok.kmp

/** Base class of every error reported by the SDK. */
public sealed class StrybokException(
    message: String,
    /**
     * The link that was being handled, when known. Always `null` for errors of
     * [Strybok.createLink], which does not handle an incoming link.
     */
    public val url: String?,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The URL is not a Strybok link of this app: not `https`, a host outside `allowedHosts`,
 * credentials or a non-default port, or a path that is not a single slug (with the
 * `parameterLinks` option: nor a route path or a Firebase long link).
 */
public class UnsupportedLinkException(message: String, url: String?) : StrybokException(message, url)

/** The service does not know this link, or it is disabled or expired (HTTP 404). */
public class LinkNotFoundException(url: String?) :
    StrybokException("No active link matches this URL", url)

/** The request could not be completed (offline, DNS, connection reset) after all retries. */
public class StrybokNetworkException(message: String, url: String?, cause: Throwable? = null) :
    StrybokException(message, url, cause)

/** The service did not answer in time, after all retries. */
public class StrybokTimeoutException internal constructor(message: String, url: String?) :
    StrybokException(message, url) {
    public constructor(url: String?) : this("The resolve request timed out", url)
}

/** The service answered with an unexpected status code or body. */
public class StrybokServerException(
    message: String,
    url: String?,
    /** HTTP status of the answer; `200` (or `201` for [Strybok.createLink]) when the body was malformed. */
    public val statusCode: Int,
) : StrybokException(message, url)

/**
 * [Strybok.createLink] only: no `clientKey` is configured (reported before any network call),
 * or the service refused the key (HTTP 401) — it is wrong or was replaced, or the owner
 * switched link creation off. The service does not say which. Retrying does not help; the
 * app's configuration or the dashboard settings need a change.
 */
public class StrybokUnauthorizedException(message: String) : StrybokException(message, null)

/**
 * [Strybok.createLink] only: the route is not covered by the route patterns the owner listed
 * for the application in the dashboard (HTTP 403). Retrying does not help.
 */
public class RouteNotAllowedException(
    /** The route that was refused, as passed to [Strybok.createLink]. */
    public val routePath: String,
) : StrybokException("The route is not covered by the application's route patterns", null)

/**
 * [Strybok.createLink] only: too many requests from this device's address or for this app, or
 * the app's daily limit of new links is reached (HTTP 429). Temporary: let the user try again
 * later. The SDK never retries it on its own.
 */
public class StrybokRateLimitedException(
    /** Seconds to wait before trying again, from the `Retry-After` header; `null` when the service did not say. */
    public val retryAfterSeconds: Long?,
) : StrybokException("Too many requests; try again later", null)

/** One problem reported by the service for a rejected [Strybok.createLink] request. */
public class StrybokValidationIssue(
    /** The request field the problem is about, for example `title` or `payload`; may be empty. */
    public val path: String,
    public val message: String,
) {
    override fun equals(other: Any?): Boolean =
        other is StrybokValidationIssue && path == other.path && message == other.message

    override fun hashCode(): Int = 31 * path.hashCode() + message.hashCode()

    override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
}

/**
 * [Strybok.createLink] only: the request was rejected, either by the SDK before sending it (a
 * route that does not start with a single `/` or is longer than 512 characters, a payload that
 * is not plain JSON data) or by the service (HTTP 400, 413 or 422). Retrying the same request
 * does not help.
 */
public class StrybokValidationException(
    /** The service's explanation, or the SDK's own for a request rejected before sending. */
    message: String,
    /** The individual problems reported by the service; empty when there are none. */
    public val issues: List<StrybokValidationIssue>,
) : StrybokException(message, null)

/**
 * [Strybok.createLink] only: the owner of the application only accepts links from apps that
 * proved they are genuine (attestation mode "Required"), and this request carried no proof —
 * the `attestation` option is off, or this device or build cannot attest (a simulator, no
 * Google Play services, not installed from Google Play, a desktop JVM). Retrying does not
 * help. On such a device this is expected: tell the user that sharing is not available here
 * rather than show an error.
 */
public class StrybokAttestationRequiredException :
    StrybokException("This application only accepts links from apps that passed attestation", null)

/**
 * [Strybok.createLink] only: the service refused the attestation token, the SDK attested again
 * and the fresh token was refused as well (or a fresh one could not be obtained). Retrying does
 * not help; check the attestation set-up of the application in the dashboard.
 */
public class StrybokAttestationFailedException(
    /** The service's reason word (`invalid`, `expired` or `exhausted`); `null` when it gave none. */
    public val reason: String?,
) : StrybokException("The attestation token was refused", null)
