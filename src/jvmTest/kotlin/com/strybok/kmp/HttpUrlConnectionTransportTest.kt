package com.strybok.kmp

import com.strybok.kmp.internal.HttpUrlConnectionTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** The real transport against a local HTTP server (JVM only; Android shares the same code). */
class HttpUrlConnectionTransportTest {
    private class Recorded(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Recorded>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestURI.rawQuery,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                body,
            )
            when (exchange.requestURI.path) {
                "/v1/public/resolve" -> exchange.reply(200, """{"linkId":"link_1","appId":"app_1","routePath":"/invite/café","payload":{"n":1}}""")
                "/v1/public/events" -> exchange.reply(202, "")
                "/v1/public/links" -> exchange.reply(
                    201,
                    """{"linkId":"link_9","url":"https://$HOST/Ab3dE9x","slug":"Ab3dE9x","routePath":"/product/é"}""",
                )
                "/limited/v1/public/links" -> exchange.reply(
                    429,
                    """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Please retry in a minute.","details":{}}}""",
                    mapOf("Retry-After" to "60"),
                )
                "/missing" -> exchange.reply(404, """{"error":"nope"}""")
                "/slow" -> {
                    Thread.sleep(3_000)
                    exchange.reply(200, "late")
                }
                else -> exchange.reply(500, "boom")
            }
        }
        server.start()
    }

    @AfterTest
    fun stopServer() = server.stop(0)

    private fun HttpExchange.reply(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    private fun request(path: String, method: String = "GET", body: String? = null, timeout: Duration = 5.seconds) =
        HttpRequest(
            method = method,
            url = "$base$path",
            headers = if (body == null) mapOf("Accept" to "application/json") else mapOf("Content-Type" to "application/json"),
            body = body,
            timeout = timeout,
        )

    @Test
    fun getReturnsStatusAndUtf8Body() = runBlocking {
        val response = HttpUrlConnectionTransport.execute(request("/v1/public/resolve?url=https%3A%2F%2Fa.b%2Fc"))
        assertEquals(200, response.statusCode)
        assertTrue("/invite/café" in response.body)
        val recorded = requests.single()
        assertEquals("GET", recorded.method)
        assertEquals("url=https%3A%2F%2Fa.b%2Fc", recorded.query)
        assertEquals("application/json", recorded.headers["accept"])
    }

    @Test
    fun postSendsTheBody() = runBlocking {
        val response = HttpUrlConnectionTransport.execute(request("/v1/public/events", "POST", """{"type":"app_open","x":"é"}"""))
        assertEquals(202, response.statusCode)
        assertEquals("", response.body)
        val recorded = requests.single()
        assertEquals("POST", recorded.method)
        assertEquals("""{"type":"app_open","x":"é"}""", recorded.body)
        assertEquals("application/json", recorded.headers["content-type"])
    }

    @Test
    fun postSendsTheClientKeyHeaderAndResponseHeadersAreReturned() = runBlocking {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "X-Strybok-Key" to CLIENT_KEY,
        )
        val body = """{"routePath":"/product/é"}"""
        val created = HttpUrlConnectionTransport.execute(
            HttpRequest("POST", "$base/v1/public/links", headers, body, 5.seconds),
        )
        assertEquals(201, created.statusCode)
        assertTrue("/product/é" in created.body)
        val recorded = requests.single()
        assertEquals("POST", recorded.method)
        assertEquals(CLIENT_KEY, recorded.headers["x-strybok-key"])
        assertEquals("application/json", recorded.headers["content-type"])
        assertEquals("application/json", recorded.headers["accept"])
        assertNull(recorded.headers["authorization"])
        assertEquals(body, recorded.body)

        val limited = HttpUrlConnectionTransport.execute(
            HttpRequest("POST", "$base/limited/v1/public/links", headers, body, 5.seconds),
        )
        assertEquals(429, limited.statusCode)
        assertEquals("60", limited.header("retry-after"))
        assertEquals("60", limited.header("Retry-After"))
        assertNull(limited.header("X-Missing"))
        assertTrue("RATE_LIMITED" in limited.body)
    }

    @Test
    fun createLinkEndToEndWithDefaultTransportAndRealTime(): Unit = runBlocking {
        val sdk = Strybok(apiBaseUrl = "$base/", allowedHosts = setOf(HOST), clientKey = CLIENT_KEY)
        val limited = Strybok(apiBaseUrl = "$base/limited", allowedHosts = setOf(HOST), clientKey = CLIENT_KEY)
        try {
            withTimeout(10.seconds) {
                val link = sdk.createLink("/product/é", mapOf("name" to "café"), title = "Red shoes")
                assertEquals("https://$HOST/Ab3dE9x", link.url)
                assertEquals("Ab3dE9x", link.slug)
                assertEquals("/product/é", link.routePath)
                assertEquals("link_9", link.linkId)

                val error = assertFailsWith<StrybokRateLimitedException> { limited.createLink("/product/é") }
                assertEquals(60L, error.retryAfterSeconds)
            }
            assertEquals(2, requests.size, "429 is not retried")
            val request = requests.first()
            assertEquals("/v1/public/links", request.path)
            assertEquals(CLIENT_KEY, request.headers["x-strybok-key"])
            assertEquals("""{"routePath":"/product/é","payload":{"name":"café"},"title":"Red shoes"}""", request.body)
        } finally {
            sdk.dispose()
            limited.dispose()
        }
    }

    @Test
    fun errorStatusesAreReturnedNotThrown() = runBlocking {
        val notFound = HttpUrlConnectionTransport.execute(request("/missing"))
        assertEquals(404, notFound.statusCode)
        assertEquals("""{"error":"nope"}""", notFound.body)
        assertEquals(500, HttpUrlConnectionTransport.execute(request("/other")).statusCode)
    }

    @Test
    fun aRefusedConnectionThrows(): Unit = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val error = assertFailsWith<Exception> {
            HttpUrlConnectionTransport.execute(
                HttpRequest("GET", "http://127.0.0.1:$closedPort/x", emptyMap(), null, 5.seconds),
            )
        }
        assertTrue(error !is HttpTimeoutException, "$error")
    }

    @Test
    fun aStalledServerHitsTheRequestTimeout(): Unit = runBlocking {
        val elapsed = measureTime {
            assertFailsWith<HttpTimeoutException> {
                HttpUrlConnectionTransport.execute(request("/slow", timeout = 200.milliseconds))
            }
        }
        assertTrue(elapsed < 2.seconds, "took $elapsed")
    }

    @Test
    fun cancellationDoesNotWaitForTheServer(): Unit = runBlocking {
        val elapsed = measureTime {
            assertNull(withTimeoutOrNull(200.milliseconds) { HttpUrlConnectionTransport.execute(request("/slow")) })
        }
        assertTrue(elapsed < 2.seconds, "took $elapsed")
    }

    @Test
    fun endToEndWithDefaultTransportAndRealTime(): Unit = runBlocking {
        val sdk = Strybok(apiBaseUrl = "$base/", allowedHosts = setOf(HOST))
        try {
            withTimeout(10.seconds) {
                assertTrue(sdk.handleLink("$INVITE?token=secret"))
                sdk.start()
                val link = sdk.links.first()
                assertEquals("/invite/café", link.routePath)
                assertEquals(mapOf<String, Any?>("n" to 1L), link.payload)
                assertEquals("$INVITE?token=secret", link.url)
                while (requests.none { it.path == "/v1/public/events" }) Thread.sleep(10)
            }
            val resolve = requests.single { it.path == "/v1/public/resolve" }
            assertEquals("url=https%3A%2F%2Fmy-app.strybok.link%2Finvite777", resolve.query)
            val event = requests.single { it.path == "/v1/public/events" }
            assertTrue(""""platform":"unknown"""" in event.body, event.body)
        } finally {
            sdk.dispose()
        }
    }
}
