package com.strybok.kmp.internal

import com.strybok.kmp.HttpRequest
import com.strybok.kmp.HttpResponse
import com.strybok.kmp.HttpTimeoutException
import com.strybok.kmp.HttpTransport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue

/** Responses of the API are tiny; anything larger than this is not ours. */
private const val MAX_BODY_BYTES = 1 shl 20

/**
 * [HttpTransport] over `NSURLSession`. The session is ephemeral: no cookies, credentials or
 * cache are written to disk. Cancelling the coroutine cancels the task.
 */
@OptIn(BetaInteropApi::class)
internal object NSUrlSessionTransport : HttpTransport {
    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration())
    }

    override suspend fun execute(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val url = NSURL.URLWithString(request.url)
            if (url == null) {
                continuation.resumeWithException(IllegalArgumentException("Invalid URL: ${request.url}"))
                return@suspendCancellableCoroutine
            }
            val urlRequest = NSMutableURLRequest.requestWithURL(url).apply {
                setHTTPMethod(request.method)
                setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                setTimeoutInterval(request.timeout.inWholeMilliseconds / 1000.0)
                request.headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
                request.body?.let { body ->
                    @Suppress("CAST_NEVER_SUCCEEDS")
                    setHTTPBody((body as NSString).dataUsingEncoding(NSUTF8StringEncoding))
                }
            }
            val task = session.dataTaskWithRequest(urlRequest) { data, response, error ->
                val http = response as? NSHTTPURLResponse
                when {
                    error != null -> continuation.resumeWithException(
                        if (error.domain == NSURLErrorDomain && error.code == NSURLErrorTimedOut) {
                            HttpTimeoutException(error.localizedDescription)
                        } else {
                            Exception("${error.domain} ${error.code}: ${error.localizedDescription}")
                        },
                    )
                    http == null -> continuation.resumeWithException(Exception("Not an HTTP response"))
                    data != null && data.length.toLong() > MAX_BODY_BYTES ->
                        continuation.resumeWithException(Exception("Response body is too large"))
                    else -> {
                        val body = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding) }
                        continuation.resume(HttpResponse(http.statusCode.toInt(), body?.toString() ?: ""))
                    }
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}
