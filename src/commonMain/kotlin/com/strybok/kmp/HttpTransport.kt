package com.strybok.kmp

import kotlin.time.Duration

/** One HTTP call made by the SDK. */
public class HttpRequest(
    /** `GET` or `POST`. */
    public val method: String,
    /** Absolute URL, already encoded. */
    public val url: String,
    public val headers: Map<String, String>,
    /** UTF-8 request body, or `null`. */
    public val body: String?,
    /**
     * Time allowed for the call. The SDK enforces it by cancelling [HttpTransport.execute];
     * transports may additionally pass it on to their HTTP client.
     */
    public val timeout: Duration,
) {
    override fun toString(): String = "HttpRequest($method $url)"
}

/** The answer to an [HttpRequest]. */
public class HttpResponse(
    public val statusCode: Int,
    /** Response body decoded as UTF-8; empty when there is none. */
    public val body: String,
)

/**
 * Performs HTTP calls for the SDK. The default implementation uses `HttpURLConnection` on
 * Android/JVM and `NSURLSession` on iOS; replace it in tests or to route calls through the
 * HTTP client of your app.
 *
 * Implementations must be safe to call concurrently, must return every received response
 * (whatever its status code), must honour coroutine cancellation, and report failures by
 * throwing: [HttpTimeoutException] for a timeout, any other exception for a transport failure.
 */
public fun interface HttpTransport {
    public suspend fun execute(request: HttpRequest): HttpResponse
}

/** Thrown by an [HttpTransport] whose own timeout fired before the SDK's. */
public class HttpTimeoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
