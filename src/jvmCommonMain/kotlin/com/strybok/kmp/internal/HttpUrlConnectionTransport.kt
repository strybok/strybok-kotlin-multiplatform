package com.strybok.kmp.internal

import com.strybok.kmp.HttpRequest
import com.strybok.kmp.HttpResponse
import com.strybok.kmp.HttpTimeoutException
import com.strybok.kmp.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual fun platformHttpTransport(): HttpTransport = HttpUrlConnectionTransport

/** Responses of the API are tiny; anything larger than this is not ours. */
private const val MAX_BODY_BYTES = 1 shl 20

/**
 * [HttpTransport] over `HttpURLConnection`, available on every Android version and JVM
 * without an extra dependency. Blocking calls run on daemon threads; cancelling the coroutine
 * disconnects the socket, so a timeout never waits for a stalled server.
 */
internal object HttpUrlConnectionTransport : HttpTransport {
    private val threadNumber = AtomicInteger()
    private val executor: ExecutorService by lazy {
        Executors.newCachedThreadPool { task ->
            Thread(task, "strybok-http-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connection = try {
                URI(request.url).toURL().openConnection() as HttpURLConnection
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { connection.disconnect() }
            executor.execute {
                val result = runCatching { call(connection, request) }
                connection.disconnect()
                result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = {
                        continuation.resumeWithException(
                            if (it is SocketTimeoutException) HttpTimeoutException("Timed out", it) else it,
                        )
                    },
                )
            }
        }

    private fun call(connection: HttpURLConnection, request: HttpRequest): HttpResponse {
        val timeoutMillis = request.timeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.requestMethod = request.method
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.useCaches = false
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        request.body?.let { body ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val status = connection.responseCode
        val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.use { readLimited(it) } ?: ""
        val headers = buildMap {
            // The status line is listed under a null name.
            connection.headerFields.forEach { (name, values) ->
                if (name != null && values != null) put(name, values.joinToString(", "))
            }
        }
        return HttpResponse(status, body, headers)
    }

    private fun readLimited(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY_BYTES) throw IOException("Response body is too large")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
