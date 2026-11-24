package com.strybok.kmp

import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `createLink`, row by row of the "Creating links" section of the SDK contract
 * (docs/sdk-contract.md), on virtual time against a fake transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateLinkTest {
    private fun creating(onCreate: suspend (HttpRequest) -> HttpResponse): FakeApi =
        FakeApi().apply { this.onCreate = onCreate }

    // --- request ---------------------------------------------------------------------------

    @Test
    fun created201ReturnsTheLinkAndSendsTheKeyInItsOwnHeader() = runTest {
        val api = FakeApi()
        val link = sdk(api).createLink("/product/42")
        advanceUntilIdle()

        assertEquals("https://$HOST/Ab3dE9x", link.url)
        assertEquals("Ab3dE9x", link.slug)
        assertEquals("/product/42", link.routePath)
        assertEquals("link_9", link.linkId)

        val request = api.creates.single()
        assertEquals("POST", request.method)
        assertEquals("$API/v1/public/links", request.url)
        assertEquals(
            mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "X-Strybok-Key" to CLIENT_KEY,
            ),
            request.headers,
        )
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals(60.milliseconds, request.timeout)
        assertTrue(api.resolves.isEmpty())
        assertTrue(api.events.isEmpty(), "creating a link is not an app open")
    }

    @Test
    fun ok200MeansAnIdenticalLinkAlreadyExistedAndIsASuccessToo() = runTest {
        val api = creating { created(status = 200, slug = "Old1234") }
        val link = sdk(api).createLink("/product/42")
        assertEquals("https://$HOST/Old1234", link.url)
        assertEquals("Old1234", link.slug)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun argumentsThatWereNotGivenAreNotSent() = runTest {
        val api = FakeApi()
        sdk(api).createLink("/product/42")
        assertEquals("""{"routePath":"/product/42"}""", api.creates.single().body)

        sdk(api).createLink("/product/42", title = "Red shoes")
        assertEquals("""{"routePath":"/product/42","title":"Red shoes"}""", api.creates.last().body)

        sdk(api).createLink("/product/42", payload = emptyMap(), imageUrl = "https://cdn.example.com/a.png")
        assertEquals(
            """{"routePath":"/product/42","payload":{},"imageUrl":"https://cdn.example.com/a.png"}""",
            api.creates.last().body,
        )
        assertFalse(api.creates.any { "null" in it.body!! })
    }

    @Test
    fun sendsEveryGivenFieldAsJson() = runTest {
        val api = FakeApi()
        sdk(api).createLink(
            routePath = "/product/42?ref=share",
            payload = linkedMapOf(
                "productId" to 42,
                "big" to 9_007_199_254_740_993L,
                "price" to 9.5,
                "sale" to true,
                "note" to null,
                "tags" to listOf("a", 1, false, null),
                "sizes" to arrayOf("s", "m"),
                "nested" to mapOf("quote" to "say \"hi\"\\\n\t", "unicode" to "café ☕"),
            ),
            title = "Red shoes",
            description = "Look at these",
            imageUrl = "https://cdn.example.com/shoes.png",
        )
        val request = api.creates.single()
        assertEquals(
            """{"routePath":"/product/42?ref=share","payload":{"productId":42,"big":9007199254740993,""" +
                """"price":9.5,"sale":true,"note":null,"tags":["a",1,false,null],"sizes":["s","m"],""" +
                """"nested":{"quote":"say \"hi\"\\\n\t","unicode":"café ☕"}},""" +
                """"title":"Red shoes","description":"Look at these","imageUrl":"https://cdn.example.com/shoes.png"}""",
            request.body,
        )
        val json = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(setOf("routePath", "payload", "title", "description", "imageUrl"), json.keys)
        val nested = json.getValue("payload").jsonObject.getValue("nested").jsonObject
        assertEquals("say \"hi\"\\\n\t", nested.getValue("quote").jsonPrimitive.content)
    }

    @Test
    fun aJsonObjectOfKotlinxSerializationIsAcceptedAsPayload() = runTest {
        val api = FakeApi()
        val payload = buildJsonObject {
            put("productId", 42)
            put("note", JsonNull)
            put("nested", buildJsonObject { put("ok", true) })
        }
        sdk(api).createLink("/product/42", payload)
        sdk(api).createLink("/product/42", mapOf("element" to JsonPrimitive("x")))
        assertEquals(
            """{"routePath":"/product/42","payload":{"productId":42,"note":null,"nested":{"ok":true}}}""",
            api.creates.first().body,
        )
        assertEquals("""{"routePath":"/product/42","payload":{"element":"x"}}""", api.creates.last().body)
    }

    @Test
    fun keepsAPathPrefixOfTheApiBaseUrl() = runTest {
        val api = FakeApi()
        sdk(api, apiBaseUrl = "https://example.com/strybok/").createLink("/a")
        assertEquals("https://example.com/strybok/v1/public/links", api.creates.single().url)
    }

    @Test
    fun theRouteOnlyOverloadSendsOnlyTheRoute() = runTest {
        val api = FakeApi()
        val create: suspend (String) -> CreatedLink = sdk(api)::createLink
        assertEquals("Ab3dE9x", create("/product/42").slug)
        assertEquals("""{"routePath":"/product/42"}""", api.creates.single().body)
    }

    // --- before any network call -------------------------------------------------------------

    @Test
    fun withoutAClientKeyItFailsAsUnauthorizedWithoutAnyNetworkCall() = runTest {
        val api = FakeApi()
        for (key in listOf(null, "", "   ")) {
            val error = assertFailsWith<StrybokUnauthorizedException> {
                sdk(api, clientKey = key).createLink("/product/42")
            }
            assertNull(error.url)
        }
        // The missing key is reported first, whatever else is wrong.
        assertFailsWith<StrybokUnauthorizedException> { sdk(api, clientKey = null).createLink("no-slash") }
        // The public constructor has no key by default.
        val plain = Strybok(apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api)
        assertFailsWith<StrybokUnauthorizedException> { plain.createLink("/product/42") }
        assertTrue(api.creates.isEmpty())
        assertEquals(0, currentTime)
    }

    @Test
    fun aKeyThatCannotBeAHeaderValueIsRefusedWithoutAnyNetworkCall() = runTest {
        val api = FakeApi()
        for (key in listOf("pk_abc\r\nX-Evil: 1", "pk_abc def", "pk_ключ")) {
            val error = assertFailsWith<StrybokUnauthorizedException> {
                sdk(api, clientKey = key).createLink("/a")
            }
            assertFalse(key in error.toString())
        }
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun theKeyIsTrimmedAndTheSwiftConstructorTakesIt() = runTest {
        val api = FakeApi()
        sdk(api, clientKey = "  $CLIENT_KEY\n").createLink("/a")
        assertEquals(CLIENT_KEY, api.creates.single().headers["X-Strybok-Key"])

        // Compiles and keeps the defaults; its platform transport is not exercised here.
        val swift = Strybok(API, setOf(HOST), CLIENT_KEY, null)
        assertEquals(2, swift.maxRetries)
        assertEquals(5.seconds, swift.timeout)
        swift.dispose()
    }

    @Test
    fun anInvalidRouteFailsValidationWithoutAnyNetworkCall() = runTest {
        val api = FakeApi()
        val sdk = sdk(api)
        for (route in listOf("", "product/42", "//evil.example.com/x", "https://example.com/x", "/" + "a".repeat(512))) {
            val error = assertFailsWith<StrybokValidationException>(route) { sdk.createLink(route) }
            assertEquals("routePath", error.issues.single().path)
            assertNull(error.url)
        }
        assertTrue(api.creates.isEmpty())

        sdk.createLink("/" + "a".repeat(511)) // 512 characters are allowed.
        sdk.createLink("/")
        assertEquals(2, api.creates.size)
    }

    @Test
    fun aPayloadThatIsNotJsonDataFailsValidationWithoutAnyNetworkCall() = runTest {
        val api = FakeApi()
        val sdk = sdk(api)
        val cyclic = HashMap<String, Any?>().also { it["self"] = it }
        val payloads = listOf<Map<String, Any?>>(
            mapOf("when" to 5.seconds),
            mapOf("ratio" to Double.NaN),
            mapOf("ratio" to Float.POSITIVE_INFINITY),
            mapOf("list" to listOf(mapOf(1 to "numeric key"))),
            mapOf("char" to 'c'),
            cyclic,
        )
        for (payload in payloads) {
            val error = assertFailsWith<StrybokValidationException> { sdk.createLink("/a", payload) }
            assertEquals("payload", error.issues.single().path)
        }
        assertTrue(api.creates.isEmpty())
    }

    // --- response ------------------------------------------------------------------------------

    @Test
    fun aSuccessAnswerThatCannotBeReadIsAServerErrorAndIsNotRetried() = runTest {
        val bodies = listOf(
            "",
            "<html>ok</html>",
            "[]",
            "\"https://$HOST/x\"",
            """{"url":"https://$HOST/x","slug":"x","routePath":"/a"}""", // no linkId
            """{"linkId":"l","slug":"x","routePath":"/a"}""", // no url
            """{"linkId":"l","url":"https://$HOST/x","routePath":"/a"}""", // no slug
            """{"linkId":"l","url":"https://$HOST/x","slug":"x"}""", // no routePath
            """{"linkId":7,"url":"https://$HOST/x","slug":"x","routePath":"/a"}""",
            """{"linkId":"l","url":"https://$HOST/x","slug":null,"routePath":"/a"}""",
            """{"linkId":"l","url":"http://$HOST/x","slug":"x","routePath":"/a"}""", // not HTTPS
            """{"linkId":"l","url":"javascript:alert(1)","slug":"x","routePath":"/a"}""",
            """{"linkId":"l","url":"//$HOST/x","slug":"x","routePath":"/a"}""",
        )
        for (status in listOf(200, 201)) {
            for (body in bodies) {
                val api = creating { HttpResponse(status, body) }
                val error = assertFailsWith<StrybokServerException>(body) { sdk(api).createLink("/a") }
                assertEquals(status, error.statusCode, body)
                assertEquals(1, api.creates.size, body)
            }
        }
    }

    @Test
    fun unauthorized401IsNotRetried() = runTest {
        val api = creating {
            HttpResponse(401, """{"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","details":{}}}""")
        }
        val error = assertFailsWith<StrybokUnauthorizedException> { sdk(api).createLink("/product/42") }
        assertNull(error.url)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun forbidden403IsRouteNotAllowedWithTheRouteAndIsNotRetried() = runTest {
        val api = creating {
            HttpResponse(403, """{"error":{"code":"ROUTE_NOT_ALLOWED","message":"Not allowed","details":{}}}""")
        }
        val error = assertFailsWith<RouteNotAllowedException> { sdk(api).createLink("/admin/secret") }
        assertEquals("/admin/secret", error.routePath)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun tooMany429IsRateLimitedWithTheRetryAfterSecondsAndIsNeverRetried() = runTest {
        val body = """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Please retry in a minute.","details":{}}}"""
        val cases = listOf<Pair<Map<String, String>, Long?>>(
            mapOf("Retry-After" to "60") to 60L,
            mapOf("retry-after" to " 43200 ") to 43_200L, // header names are case-insensitive
            mapOf("RETRY-AFTER" to "0") to 0L,
            emptyMap<String, String>() to null,
            mapOf("Retry-After" to "Wed, 25 Nov 2026 00:00:00 GMT") to null,
            mapOf("Retry-After" to "-5") to null,
            mapOf("Retry-After" to "soon") to null,
            mapOf("Retry-After" to "99999999999999999999999") to null,
        )
        for ((headers, expected) in cases) {
            val api = creating { HttpResponse(429, body, headers) }
            val error = assertFailsWith<StrybokRateLimitedException> { sdk(api).createLink("/product/42") }
            assertEquals(expected, error.retryAfterSeconds, "$headers")
            assertEquals(1, api.creates.size)
        }
        assertEquals(0, currentTime, "no pause, no retry")
    }

    @Test
    fun badRequest400And413And422AreValidationErrorsWithTheServersMessageAndIssues() = runTest {
        val body = """{"error":{"code":"VALIDATION_ERROR","message":"The request is invalid","details":{"issues":[""" +
            """{"path":"title","message":"Too big: expected string to have <=120 characters"},""" +
            """{"path":"imageUrl","message":"Must be a public HTTPS URL"},{"message":"no path"},{"path":"x"},"junk"]}}}"""
        for (status in listOf(400, 413, 422)) {
            val api = creating { HttpResponse(status, body) }
            val error = assertFailsWith<StrybokValidationException> { sdk(api).createLink("/product/42") }
            assertEquals("The request is invalid", error.message)
            assertEquals(
                listOf(
                    StrybokValidationIssue("title", "Too big: expected string to have <=120 characters"),
                    StrybokValidationIssue("imageUrl", "Must be a public HTTPS URL"),
                    StrybokValidationIssue("", "no path"),
                ),
                error.issues,
            )
            assertEquals("title: Too big: expected string to have <=120 characters", error.issues.first().toString())
            assertEquals(1, api.creates.size)
        }
    }

    @Test
    fun aValidationAnswerWithoutAReadableBodyStillIsAValidationError() = runTest {
        val bodies = listOf("", "Payload Too Large", "[]", """{"error":"nope"}""", """{"error":{"details":{"issues":{}}}}""")
        for (body in bodies) {
            val api = creating { HttpResponse(413, body) }
            val error = assertFailsWith<StrybokValidationException> { sdk(api).createLink("/product/42") }
            assertEquals("The service rejected the request (HTTP 413)", error.message)
            assertTrue(error.issues.isEmpty())
        }
    }

    @Test
    fun anyOtherStatusBelow500IsAServerErrorWithTheStatusAndIsNotRetried() = runTest {
        for (status in listOf(404, 405, 409, 418, 204, 202, 302)) {
            val api = creating { HttpResponse(status, "{}") }
            val error = assertFailsWith<StrybokServerException> { sdk(api).createLink("/product/42") }
            assertEquals(status, error.statusCode)
            assertNull(error.url)
            assertEquals(1, api.creates.size)
        }
    }

    // --- timeout and retries -------------------------------------------------------------------

    @Test
    fun retriesServerErrorsAndThenSucceeds() = runTest {
        var calls = 0
        val api = creating { if (++calls < 3) HttpResponse(503, "") else created() }
        assertEquals("Ab3dE9x", sdk(api).createLink("/product/42").slug)
        assertEquals(3, api.creates.size)
    }

    @Test
    fun reportsTheLastServerErrorAfterAllRetries() = runTest {
        var calls = 0
        val api = creating { HttpResponse(if (++calls < 3) 500 else 502, "") }
        val error = assertFailsWith<StrybokServerException> { sdk(api).createLink("/product/42") }
        assertEquals(502, error.statusCode)
        assertEquals(3, api.creates.size)

        val once = creating { HttpResponse(500, "") }
        assertFailsWith<StrybokServerException> { sdk(once, maxRetries = 0).createLink("/product/42") }
        assertEquals(1, once.creates.size)
    }

    @Test
    fun retriesTransportFailuresAndThenSucceeds() = runTest {
        var calls = 0
        val api = creating {
            if (++calls < 3) throw RuntimeException("connection reset")
            created()
        }
        assertEquals("link_9", sdk(api).createLink("/product/42").linkId)
        assertEquals(3, api.creates.size)
    }

    @Test
    fun givesUpWithANetworkExceptionAfterMaxRetries() = runTest {
        val api = creating { throw RuntimeException("offline") }
        val error = assertFailsWith<StrybokNetworkException> { sdk(api, maxRetries = 1).createLink("/product/42") }
        assertEquals("offline", error.cause?.message)
        assertNull(error.url)
        assertEquals(2, api.creates.size)
    }

    @Test
    fun timesOutWithATypedExceptionAfterRetrying() = runTest {
        val api = creating { awaitCancellation() }
        val error = assertFailsWith<StrybokTimeoutException> { sdk(api, maxRetries = 1).createLink("/product/42") }
        assertNull(error.url)
        assertEquals("The create-link request timed out", error.message)
        assertEquals(2, api.creates.size)
        assertEquals(60 + 1 + 60L, currentTime, "two attempts of 60 ms and one pause of 1 ms")
    }

    @Test
    fun aTimeoutReportedByTheTransportIsATimeoutToo() = runTest {
        val api = creating { throw HttpTimeoutException("read timed out") }
        assertFailsWith<StrybokTimeoutException> { sdk(api, maxRetries = 1).createLink("/product/42") }
        assertEquals(2, api.creates.size)
    }

    @Test
    fun aTimeoutFollowedByAnAnswerSucceeds() = runTest {
        var calls = 0
        val api = creating { if (++calls == 1) awaitCancellation() else created(status = 200) }
        assertEquals("Ab3dE9x", sdk(api).createLink("/product/42").slug)
        assertEquals(2, api.creates.size)
    }

    @Test
    fun waitsRetryDelayBeforeTheFirstRetryAndDoublesItEachTime() = runTest {
        val times = mutableListOf<Long>()
        val api = creating {
            times += currentTime
            HttpResponse(503, "")
        }
        assertFailsWith<StrybokServerException> {
            sdk(api, maxRetries = 3, retryDelay = 100.milliseconds).createLink("/product/42")
        }
        assertEquals(listOf(0L, 100L, 300L, 700L), times)
    }

    @Test
    fun everyRetrySendsExactlyTheSameRequest() = runTest {
        var calls = 0
        val api = creating {
            when (++calls) {
                1 -> throw RuntimeException("connection reset")
                2 -> HttpResponse(503, "")
                3 -> awaitCancellation()
                else -> created()
            }
        }
        sdk(api, maxRetries = 3).createLink(
            "/product/42",
            payload = mapOf("b" to 1, "a" to listOf(1.5, "x"), "c" to mapOf("z" to null)),
            title = "Red shoes",
            description = "Look at these",
            imageUrl = "https://cdn.example.com/shoes.png",
        )
        assertEquals(4, api.creates.size)
        val first = api.creates.first()
        assertEquals(
            """{"routePath":"/product/42","payload":{"b":1,"a":[1.5,"x"],"c":{"z":null}},"title":"Red shoes",""" +
                """"description":"Look at these","imageUrl":"https://cdn.example.com/shoes.png"}""",
            first.body,
        )
        for (retry in api.creates.drop(1)) {
            assertEquals(first.body, retry.body)
            assertEquals(first.headers, retry.headers)
            assertEquals(first.url, retry.url)
            assertEquals(first.method, retry.method)
        }
    }

    // --- errors --------------------------------------------------------------------------------

    @Test
    fun errorsNeverContainTheClientKeyOrThePayload() = runTest {
        val secret = "s3cr3t-payload-value"
        val payload = mapOf("token" to secret)
        val answers: List<suspend (HttpRequest) -> HttpResponse> = listOf(
            { _ -> HttpResponse(200, "not json") },
            { _ -> HttpResponse(401, "") },
            { _ -> HttpResponse(403, "") },
            { _ -> HttpResponse(429, "", mapOf("Retry-After" to "60")) },
            { _ -> HttpResponse(422, """{"error":{"message":"The request is invalid","details":{"issues":[{"path":"payload","message":"Too big"}]}}}""") },
            { _ -> HttpResponse(404, "") },
            { _ -> HttpResponse(500, "") },
            { _ -> throw RuntimeException("connection reset") },
            { _ -> throw HttpTimeoutException("read timed out") },
            { _ -> awaitCancellation() },
        )
        val errors = mutableListOf<StrybokException>()
        for (answer in answers) {
            errors += assertFailsWith<StrybokException> {
                sdk(creating(answer), maxRetries = 0).createLink("/product/42", payload)
            }
        }
        // Pre-flight failures.
        errors += assertFailsWith<StrybokException> { sdk(FakeApi()).createLink("product/42", payload) }
        errors += assertFailsWith<StrybokException> {
            sdk(FakeApi()).createLink("/a", mapOf("token" to secret, "bad" to Any()))
        }
        errors += assertFailsWith<StrybokException> {
            sdk(FakeApi()).createLink("/a", mapOf(secret to mapOf(7 to secret)))
        }
        assertEquals(
            listOf(
                StrybokServerException::class, StrybokUnauthorizedException::class, RouteNotAllowedException::class,
                StrybokRateLimitedException::class, StrybokValidationException::class, StrybokServerException::class,
                StrybokServerException::class, StrybokNetworkException::class, StrybokTimeoutException::class,
                StrybokTimeoutException::class, StrybokValidationException::class,
                StrybokValidationException::class, StrybokValidationException::class,
            ),
            errors.map { it::class },
        )
        for (error in errors) {
            val text = buildString {
                var current: Throwable? = error
                while (current != null) {
                    append(current.toString()).append('\n').append(current.message).append('\n')
                    current = current.cause
                }
                append(error.url)
                if (error is StrybokValidationException) append(error.issues)
            }
            assertFalse(CLIENT_KEY in text, text)
            assertFalse(secret in text, text)
        }
        val request = HttpRequest("POST", "$API/v1/public/links", mapOf("X-Strybok-Key" to CLIENT_KEY), secret, 1.seconds)
        assertEquals("HttpRequest(POST $API/v1/public/links)", request.toString())
    }

    @Test
    fun errorsGoToTheCallerAndNeverToOnError() = runTest {
        val errors = mutableListOf<StrybokException>()
        val api = creating { HttpResponse(403, "") }
        val sdk = sdk(api, errors = errors)
        assertFailsWith<RouteNotAllowedException> { sdk.createLink("/admin") }
        assertFailsWith<StrybokValidationException> { sdk.createLink("admin") }
        advanceUntilIdle()
        assertTrue(errors.isEmpty())
    }

    // --- never on the start path ---------------------------------------------------------------

    @Test
    fun worksWithoutStartAndWithoutTouchingIncomingLinks() = runTest {
        val api = FakeApi()
        val source = FakeLinkSource(initialLink = INVITE)
        val sdk = sdk(api, source = source)
        assertEquals("https://$HOST/Ab3dE9x", sdk.createLink("/product/42").url)
        advanceUntilIdle()
        assertTrue(api.resolves.isEmpty(), "createLink must not start the SDK")
        assertTrue(api.events.isEmpty())
        assertEquals(0, currentTime)

        // The launch link is still there for a later start.
        assertEquals("/invite/abc123", sdk.getInitialLink()?.routePath)
        sdk.dispose()
    }

    @Test
    fun worksWithoutAnyAllowedHost() = runTest {
        val api = FakeApi()
        val sdk = sdk(api, allowedHosts = emptySet())
        assertEquals("https://$HOST/Ab3dE9x", sdk.createLink("/product/42").url)
    }

    @Test
    fun startIncomingLinksAndResolveNeverCallTheCreateEndpoint() = runTest {
        val api = FakeApi()
        val source = FakeLinkSource(initialLink = INVITE)
        val sdk = sdk(api, source = source)
        val received = collectLinks(sdk)
        sdk.start()
        source.emit("https://$HOST/other")
        advanceUntilIdle()
        sdk.resolve(INVITE)
        advanceUntilIdle()

        assertEquals(2, received.size)
        assertEquals(2, api.events.size)
        assertTrue(api.creates.isEmpty())
        assertFalse(api.resolves.any { "X-Strybok-Key" in it.headers })
        assertFalse(api.eventRequests.any { "X-Strybok-Key" in it.headers })
        sdk.dispose()
    }

    @Test
    fun neitherWaitsForALaunchLinkThatIsStillResolvingNorDelaysIt() = runTest {
        val api = FakeApi { awaitCancellation() }
        val sdk = sdk(api, source = FakeLinkSource(initialLink = INVITE), timeout = 10.seconds, maxRetries = 0)
        val starting = launch { sdk.start() }
        runCurrent()
        assertEquals(1, api.resolves.size)

        assertEquals("Ab3dE9x", sdk.createLink("/product/42").slug)
        assertEquals(0, currentTime, "createLink did not wait for the launch link")
        assertTrue(starting.isActive)

        // And the other way round: start completes while a createLink call hangs.
        val slow = FakeApi().apply { onCreate = { awaitCancellation() } }
        val other = sdk(slow, source = FakeLinkSource(initialLink = INVITE), timeout = 10.seconds, maxRetries = 0)
        val create = launch { runCatching { other.createLink("/product/42") } }
        runCurrent()
        assertEquals(1, slow.creates.size)
        assertEquals("/invite/abc123", other.getInitialLink()?.routePath)
        assertTrue(create.isActive)
        assertEquals(0, currentTime)

        advanceUntilIdle()
        sdk.dispose()
        other.dispose()
    }

    // --- lifecycle -----------------------------------------------------------------------------

    @Test
    fun afterDisposeItThrowsIllegalStateException() = runTest {
        val api = FakeApi()
        val sdk = sdk(api)
        sdk.dispose()
        assertFailsWith<IllegalStateException> { sdk.createLink("/product/42") }
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun disposedBetweenAttemptsItStopsRetrying() = runTest {
        lateinit var sdk: Strybok
        val api = creating {
            sdk.dispose()
            HttpResponse(503, "")
        }
        sdk = sdk(api)
        assertFailsWith<IllegalStateException> { sdk.createLink("/product/42") }
        assertEquals(1, api.creates.size)
    }
}
