package com.strybok.kmp

import com.strybok.kmp.internal.NSUrlSessionTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * The real `NSURLSession` transport against a tiny HTTP server on the loopback interface of
 * the simulator: the POST of `createLink` must carry the body and the `X-Strybok-Key` header,
 * and response headers (`Retry-After`) must come back.
 */
@OptIn(ExperimentalForeignApi::class)
class NSUrlSessionCreateLinkTest {
    /** Answers one request per connection with the next of [answers] and records what it received. */
    private class LoopbackServer(private val answers: List<String>) {
        private val listener: Int = socket(AF_INET, SOCK_STREAM, 0)
        val port: Int

        init {
            check(listener >= 0) { "socket() failed" }
            port = memScoped {
                val on = alloc<IntVar>().apply { value = 1 }
                setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, on.ptr, sizeOf<IntVar>().convert())
                val address = alloc<sockaddr_in>().apply {
                    sin_len = sizeOf<sockaddr_in>().convert()
                    sin_family = AF_INET.convert()
                    sin_port = 0u // Any free port.
                    sin_addr.s_addr = 0x0100007Fu // 127.0.0.1 in network byte order.
                }
                check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
                check(listen(listener, 4) == 0) { "listen() failed" }
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                check(getsockname(listener, address.ptr.reinterpret(), length.ptr) == 0) { "getsockname() failed" }
                val networkOrder = address.sin_port.toInt()
                ((networkOrder and 0xFF) shl 8) or ((networkOrder shr 8) and 0xFF)
            }
        }

        /** Blocking: serves every answer in turn and returns the raw requests. */
        fun serve(): List<String> = answers.map { answer ->
            val client = accept(listener, null, null)
            check(client >= 0) { "accept() failed" }
            try {
                val request = readRequest(client)
                val bytes = answer.encodeToByteArray()
                var sent = 0
                while (sent < bytes.size) {
                    val n = bytes.usePinned { send(client, it.addressOf(sent), (bytes.size - sent).convert(), 0) }.toInt()
                    check(n > 0) { "send() failed" }
                    sent += n
                }
                request
            } finally {
                close(client)
            }
        }

        private fun readRequest(client: Int): String {
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (total < buffer.size) {
                val n = buffer.usePinned { recv(client, it.addressOf(total), (buffer.size - total).convert(), 0) }.toInt()
                if (n <= 0) break
                total += n
                val text = buffer.decodeToString(0, total)
                val headersEnd = text.indexOf("\r\n\r\n")
                if (headersEnd < 0) continue
                // The header block is ASCII, so its length in characters is its length in bytes.
                val bodyLength = Regex("content-length: *(\\d+)", RegexOption.IGNORE_CASE)
                    .find(text.substring(0, headersEnd))?.groupValues?.get(1)?.toInt() ?: 0
                if (total - (headersEnd + 4) >= bodyLength) break
            }
            return buffer.decodeToString(0, total)
        }

        fun stop() {
            close(listener)
        }
    }

    private fun http(status: String, body: String, extraHeader: String = ""): String =
        "HTTP/1.1 $status\r\nContent-Type: application/json\r\n$extraHeader" +
            "Content-Length: ${body.encodeToByteArray().size}\r\nConnection: close\r\n\r\n$body"

    private val created = http(
        "201 Created",
        """{"linkId":"link_9","url":"https://$HOST/Ab3dE9x","slug":"Ab3dE9x","routePath":"/product/é"}""",
    )
    private val limited = http(
        "429 Too Many Requests",
        """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Please retry in a minute.","details":{}}}""",
        "Retry-After: 60\r\n",
    )

    private var server: LoopbackServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop()
    }

    private fun start(vararg answers: String): LoopbackServer = LoopbackServer(answers.toList()).also { server = it }

    private fun String.headerLines(): List<String> = substringBefore("\r\n\r\n").split("\r\n").map { it.lowercase() }

    @Test
    fun postCarriesTheBodyAndTheClientKeyHeaderAndResponseHeadersComeBack() = runBlocking {
        val server = start(created, limited)
        val served = CoroutineScope(Dispatchers.IO).async { server.serve() }
        val body = """{"routePath":"/product/é","payload":{"name":"café"}}"""
        val request = HttpRequest(
            method = "POST",
            url = "http://127.0.0.1:${server.port}/v1/public/links",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "X-Strybok-Key" to CLIENT_KEY,
            ),
            body = body,
            timeout = 10.seconds,
        )

        withTimeout(30.seconds) {
            val first = NSUrlSessionTransport.execute(request)
            assertEquals(201, first.statusCode)
            assertTrue("/product/é" in first.body, first.body)

            val second = NSUrlSessionTransport.execute(request)
            assertEquals(429, second.statusCode)
            assertEquals("60", second.header("Retry-After"))
            assertEquals("60", second.header("retry-after"))
            assertNull(second.header("X-Missing"))
            assertTrue("RATE_LIMITED" in second.body, second.body)

            val received = served.await()
            assertEquals(2, received.size)
            for (raw in received) {
                val lines = raw.headerLines()
                assertEquals("post /v1/public/links http/1.1", lines.first())
                assertTrue("x-strybok-key: ${CLIENT_KEY.lowercase()}" in lines, raw)
                assertTrue("content-type: application/json" in lines, raw)
                assertTrue("accept: application/json" in lines, raw)
                assertFalse(lines.any { it.startsWith("authorization:") }, raw)
                assertEquals(body, raw.substringAfter("\r\n\r\n"))
                // The header keeps its exact value (case included).
                assertTrue(CLIENT_KEY in raw.substringBefore("\r\n\r\n"), raw)
            }
        }
    }

    @Test
    fun createLinkEndToEndWithTheDefaultTransportAndRealTime() = runBlocking {
        val server = start(created, limited)
        val served = CoroutineScope(Dispatchers.IO).async { server.serve() }
        // The default transport on iOS is NSUrlSessionTransport.
        val sdk = Strybok(
            apiBaseUrl = "http://127.0.0.1:${server.port}/",
            allowedHosts = setOf(HOST),
            coroutineContext = Dispatchers.Default,
            clientKey = CLIENT_KEY,
        )
        try {
            withTimeout(30.seconds) {
                val link = sdk.createLink("/product/é", mapOf("name" to "café"), title = "Red shoes")
                assertEquals("https://$HOST/Ab3dE9x", link.url)
                assertEquals("Ab3dE9x", link.slug)
                assertEquals("/product/é", link.routePath)
                assertEquals("link_9", link.linkId)

                val error = assertFailsWith<StrybokRateLimitedException> { sdk.createLink("/product/é") }
                assertEquals(60L, error.retryAfterSeconds)

                val received = served.await()
                assertEquals(
                    """{"routePath":"/product/é","payload":{"name":"café"},"title":"Red shoes"}""",
                    received.first().substringAfter("\r\n\r\n"),
                )
                assertEquals("""{"routePath":"/product/é"}""", received.last().substringAfter("\r\n\r\n"))
                assertTrue(received.all { "x-strybok-key: ${CLIENT_KEY.lowercase()}" in it.headerLines() })
            }
        } finally {
            sdk.dispose()
        }
    }
}
