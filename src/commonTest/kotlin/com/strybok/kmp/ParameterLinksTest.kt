package com.strybok.kmp

import com.strybok.kmp.internal.DeferredKind
import com.strybok.kmp.internal.parseLinkUrl
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Links without an API call (SDK contract, "Links without an API call"): the `parameterLinks`
// option, which URLs are ours with it, what is sent, and what stays as it was.

/**
 * The shared test vectors of the SDK contract. The build turns its byte-identical copy,
 * src/commonTest/resources/parameter-links-vectors.json, into [PARAMETER_LINK_VECTORS_JSON], so
 * every target reads the same text.
 */
internal object ParameterLinkVectors {
    class Lookup(val name: String, val payloadKeys: List<String>?, val url: String, val lookup: String?)

    class Configuration(val name: String, val payloadKeys: List<String>?, val valid: Boolean)

    private val root: JsonObject = Json.parseToJsonElement(PARAMETER_LINK_VECTORS_JSON).jsonObject

    val allowedHosts: Set<String> = root.getValue("allowedHosts").jsonArray.map { it.jsonPrimitive.content }.toSet()

    val lookups: List<Lookup> = root.getValue("lookups").jsonArray.map { element ->
        val case = element.jsonObject
        Lookup(
            name = case.getValue("name").jsonPrimitive.content,
            payloadKeys = keys(case.getValue("parameterLinks")),
            url = case.getValue("url").jsonPrimitive.content,
            lookup = case.getValue("lookup").let { if (it is JsonNull) null else it.jsonPrimitive.content },
        )
    }

    val configurations: List<Configuration> = root.getValue("configurations").jsonArray.map { element ->
        val case = element.jsonObject
        Configuration(
            name = case.getValue("name").jsonPrimitive.content,
            payloadKeys = keys(case.getValue("parameterLinks")),
            valid = case.getValue("valid").jsonPrimitive.content.toBooleanStrict(),
        )
    }

    /** `payloadKeys` of a `parameterLinks` value; `null` when the option is not set. */
    private fun keys(option: JsonElement): List<String>? =
        if (option is JsonNull) null else option.jsonObject.getValue("payloadKeys").jsonArray.map { it.jsonPrimitive.content }
}

/** The resolve request for [lookup], written out: every byte outside `A-Z a-z 0-9 - . _ ~` as `%XX`. */
internal fun resolveRequestUrl(lookup: String): String = "$API/v1/public/resolve?url=" + buildString {
    for (byte in lookup.encodeToByteArray()) {
        val code = byte.toInt() and 0xff
        val char = code.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%').append("0123456789ABCDEF"[code shr 4]).append("0123456789ABCDEF"[code and 0x0f])
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ParameterLinksVectorTest {
    private fun option(keys: List<String>?): ParameterLinks? = keys?.let(::ParameterLinks)

    @Test
    fun everyLookupCase() = runTest {
        val vectors = ParameterLinkVectors
        assertTrue(vectors.lookups.isNotEmpty())
        var ran = 0
        for (case in vectors.lookups) {
            val name = case.name
            val lookup = case.lookup

            // resolve(url)
            val api = FakeApi()
            val sdk = sdk(api, allowedHosts = vectors.allowedHosts, reportAppOpens = false, parameterLinks = option(case.payloadKeys))
            assertEquals(lookup != null, sdk.isStrybokLink(case.url), name)
            if (lookup != null) {
                val link = sdk.resolve(case.url)
                assertEquals(case.url, link.url, name)
                val request = api.resolves.single()
                assertEquals("GET", request.method, name)
                assertEquals(resolveRequestUrl(lookup), request.url, name)
                assertEquals(mapOf("Accept" to "application/json"), request.headers, name)
                assertNull(request.body, name)
            } else {
                val error = assertFailsWith<UnsupportedLinkException>(name) { sdk.resolve(case.url) }
                assertEquals(case.url, error.url, name)
                assertTrue(api.resolves.isEmpty(), "$name: no request")
            }
            sdk.dispose()

            // The automatic flow, through handleLink like the platform glue: the URL launches the app.
            val flowApi = FakeApi()
            val errors = mutableListOf<StrybokException>()
            val flow = Strybok(
                apiBaseUrl = API,
                allowedHosts = vectors.allowedHosts,
                reportAppOpens = false,
                onError = { errors += it },
                transport = flowApi,
                coroutineContext = StandardTestDispatcher(testScheduler),
                parameterLinks = option(case.payloadKeys),
            )
            val received = collectLinks(flow)
            assertEquals(lookup != null, flow.handleLink(case.url), name)
            flow.start()
            advanceUntilIdle()
            assertEquals(listOfNotNull(lookup?.let(::resolveRequestUrl)), flowApi.resolves.map { it.url }, name)
            assertEquals(if (lookup == null) emptyList() else listOf(case.url), received.map { it.url }, name)
            assertEquals(emptyList(), errors, name)
            flow.dispose()
            ran += 1
        }
        assertEquals(vectors.lookups.size, ran, "every case ran")
    }

    /**
     * More cases next to the shared file (the iOS SDK runs the same table): strict UTF-8, UTF-16
     * lengths, dot segments and reserved paths, and deep links.
     */
    @Test
    fun moreCasesBeyondTheSharedVectors() {
        val link = "https://myapp.strybok.link"
        val emoji = "%F0%9F%98%80"
        val cases = listOf(
            "$link/p?id=%C0%80&lang=uk" to "$link/p?lang=uk", // overlong
            "$link/p?id=%ED%A0%80&lang=uk" to "$link/p?lang=uk", // a surrogate
            "$link/p?id=%F4%90%80%80&lang=uk" to "$link/p?lang=uk", // above U+10FFFF
            "$link/p?id=%f0%9f%98%80" to "$link/p?id=$emoji",
            "$link/p?id=%C2%85&lang=%C2%A0" to "$link/p?lang=%C2%A0", // U+0085 is a control character
            "$link/p?id=${emoji.repeat(128)}" to "$link/p?id=${emoji.repeat(128)}",
            "$link/p?id=${emoji.repeat(129)}" to "$link/p", // 258 UTF-16 code units
            "$link/p?id=a&id=b&lang=" to "$link/p?id=a",
            "$link/p?%69%64=x&id=y" to "$link/p?id=x",
            "$link/p?id%zz=1&id=2" to "$link/p?id=2",
            "$link/a/%2e" to null,
            "$link/a/.%2E/b" to null,
            "$link/%2e%2e%2e" to "$link/%2e%2e%2e",
            "$link/.well-knownx" to "$link/.well-knownx",
            "$link/_strybok/" to null,
            "$link/Favicon.ico" to "$link/Favicon.ico",
            "$link/?link=HTTPS%3A%2F%2FEXAMPLE.COM%2FP%3FID%3D1%26id%3D2" to "$link/?link=https%3A%2F%2Fexample.com%2FP%3Fid%3D2",
            "$link/?link=https%3A%2F%2Fexample.com%3Fid%3D1" to "$link/?link=https%3A%2F%2Fexample.com%2F%3Fid%3D1",
            "$link/?link=https%3A%2F%2Fexample.com%23x%3Fid%3D1" to "$link/?link=https%3A%2F%2Fexample.com%2F",
            "$link/?link=https%3A%2F%2Fexample.com%2Fcaf%C3%A9" to "$link/?link=https%3A%2F%2Fexample.com%2Fcaf%C3%A9",
            "$link/?link=https%3A%2F%2Fexample.com%2Fa%5Cb" to null,
            "$link/?link=https%3A%2F%2Fexample.com&apn" to "$link/?link=https%3A%2F%2Fexample.com%2F&apn=",
            "$link/?link=http%3A%2F%2Fexample.com%2Fp&utm_source=a&apn=x&ibi=y" to
                "$link/?link=http%3A%2F%2Fexample.com%2Fp&apn=x&ibi=y&utm_source=a",
            "$link/?link=https%3A%2F%2Fexample.com%2Fp%3Fid%3D%25zz" to "$link/?link=https%3A%2F%2Fexample.com%2Fp",
            "$link/?link=%zz" to null,
            "$link/?link=https%3A%2F%2Fexample.com&link=x&id=7" to "$link/?link=https%3A%2F%2Fexample.com%2F",
        )
        val option = ParameterLinks(listOf("id", "lang"))
        for ((url, expected) in cases) {
            assertEquals(expected, parseLinkUrl(url, ParameterLinkVectors.allowedHosts, option)?.lookup, url)
        }
    }

    @Test
    fun everyConfigurationCase() = runTest {
        val vectors = ParameterLinkVectors
        assertTrue(vectors.configurations.isNotEmpty())
        for (case in vectors.configurations) {
            val keys = case.payloadKeys
            if (keys == null) {
                // Not setting the option is always valid.
                assertTrue(case.valid, case.name)
                val sdk = sdk(FakeApi(), allowedHosts = vectors.allowedHosts)
                assertNull(sdk.parameterLinks, case.name)
                sdk.dispose()
            } else if (case.valid) {
                val accepted = ParameterLinks(keys)
                assertEquals(keys, accepted.payloadKeys, case.name)
                val sdk = sdk(FakeApi(), allowedHosts = vectors.allowedHosts, parameterLinks = accepted)
                assertEquals(accepted, sdk.parameterLinks, case.name)
                sdk.dispose()
            } else {
                // Refused like an invalid apiBaseUrl: IllegalArgumentException, before any SDK exists.
                val error = assertFailsWith<IllegalArgumentException>(case.name) { ParameterLinks(keys) }
                assertTrue(error.message.orEmpty().startsWith("parameterLinks: "), error.message)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ParameterLinksTest {
    private val keys = ParameterLinks(listOf("id", "lang"))

    private fun answer(
        linkId: String = "lnk_paths_app_1",
        route: String = "/catalog/item",
        payload: String = """{"id":"from-the-service"}""",
    ): HttpResponse = HttpResponse(
        200,
        """{"linkId":"$linkId","appId":"app_1","routePath":"$route","payload":$payload,"campaign":{"source":"service"}}""",
    )

    // --- the option ----------------------------------------------------------------------------

    @Test
    fun isOffByDefault() {
        val sdk = Strybok(apiBaseUrl = API, allowedHosts = setOf(HOST), transport = FakeApi())
        assertNull(sdk.parameterLinks)
        assertFalse(sdk.isStrybokLink("https://$HOST/product/42"))
        sdk.dispose()
    }

    @Test
    fun anInvalidOptionCannotBeCreatedAndSaysWhy() {
        for ((invalid, reason) in listOf(
            (0..20).map { "k$it" } to "at most 20",
            listOf("id", "id") to "\"id\" appears twice",
            listOf("") to "not 1–40",
            listOf("a b") to "not 1–40",
            listOf("utm_term") to "read by Strybok",
            listOf("Utm_X") to "read by Strybok",
            listOf("strybok_click") to "read by Strybok",
        )) {
            val error = assertFailsWith<IllegalArgumentException>("$invalid") { ParameterLinks(invalid) }
            assertTrue(reason in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun theKeysKeepTheirOrderAndAreCopied() {
        val source = mutableListOf("lang", "id", "ST")
        val option = ParameterLinks(source)
        source.clear()
        assertEquals(listOf("lang", "id", "ST"), option.payloadKeys)
        assertEquals(ParameterLinks(listOf("lang", "id", "ST")), option)
        assertEquals(ParameterLinks(emptyList()).payloadKeys, emptyList())
    }

    @Test
    fun theSwiftConstructorTakesTheOption() {
        val sdk = Strybok(API, setOf(HOST), null, false, false, false, keys, null)
        assertEquals(keys, sdk.parameterLinks)
        assertTrue(sdk.isStrybokLink("https://$HOST/product/42?id=7"))
        sdk.dispose()
    }

    // --- delivery ------------------------------------------------------------------------------

    @Test
    fun theDeliveredLinkHasTheRouteAndPayloadOfTheAnswerNotOfTheTappedUrl() = runTest {
        val api = FakeApi { answer() }
        val tapped = "https://$HOST/product/42?id=7&lang=uk&utm_source=tapped&secret=x#reviews"
        val sdk = sdk(api, source = FakeLinkSource(initialLink = tapped), parameterLinks = keys)
        val received = collectLinks(sdk)
        sdk.start()
        advanceUntilIdle()

        val link = received.single()
        assertEquals(tapped, link.url, "the URL as received")
        assertEquals("/catalog/item", link.routePath, "never the tapped path")
        assertEquals(mapOf<String, Any?>("id" to "from-the-service"), link.payload, "never read from the URL")
        assertEquals("""{"id":"from-the-service"}""", link.payloadJson)
        assertEquals("lnk_paths_app_1", link.linkId)
        assertEquals(LinkCampaign(source = "service"), link.campaign)
        assertFalse(link.isDeferred)
        assertEquals(
            resolveRequestUrl("https://$HOST/product/42?id=7&lang=uk&utm_source=tapped"),
            api.resolves.single().url,
        )

        val resolved = sdk.resolve(tapped)
        assertEquals("/catalog/item", resolved.routePath)
        assertEquals(mapOf<String, Any?>("id" to "from-the-service"), resolved.payload)
        sdk.dispose()
    }

    @Test
    fun aMissingPayloadInTheAnswerIsEmpty() = runTest {
        val link = sdk(FakeApi { answer(payload = "null") }, parameterLinks = keys).resolve("https://$HOST/product/42?id=7")
        assertTrue(link.payload.isEmpty())
    }

    @Test
    fun appOpenCarriesTheLinkIdTheServiceReturned() = runTest {
        for ((tapped, linkId) in listOf(
            "https://$HOST/product/42?id=7" to "lnk_paths_app_1",
            "https://$HOST/?link=https%3A%2F%2Fexample.com%2Fp%3Fid%3D7&apn=com.example.app" to "lnk_firebase_app_1",
        )) {
            val api = FakeApi { answer(linkId = linkId) }
            val sdk = sdk(api, source = FakeLinkSource(initialLink = tapped), parameterLinks = keys)
            collectLinks(sdk)
            sdk.start()
            advanceUntilIdle()
            val event = api.events.single()
            assertEquals(linkId, event.getValue("linkId").jsonPrimitive.content, tapped)
            assertEquals("app_open", event.getValue("type").jsonPrimitive.content)
            assertEquals("service", event.getValue("campaign").jsonObject.getValue("source").jsonPrimitive.content)
            assertEquals(setOf("eventId", "linkId", "type", "platform", "sdkVersion", "campaign"), event.keys)
            sdk.dispose()
        }
    }

    @Test
    fun aLongLinkSendsTheRebuiltDeepLinkAndIsDeliveredWithTheAnswersRoute() = runTest {
        val api = FakeApi { answer(linkId = "lnk_firebase_app_1", route = "/product/42") }
        val tapped = "https://$HOST/?link=https%3A%2F%2FShop.Example.com%2Fproduct%2F42%3Fid%3D7%26token%3Dsecret%23top" +
            "&apn=com.example.app&ibi=com.example.App&afl=https%3A%2F%2Fexample.com&st=Title&utm_medium=email"
        val link = sdk(api, parameterLinks = keys).resolve(tapped)
        assertEquals("/product/42", link.routePath)
        assertEquals(tapped, link.url)
        val sent = api.resolves.single().url
        assertEquals(
            resolveRequestUrl(
                "https://$HOST/?link=https%3A%2F%2Fshop.example.com%2Fproduct%2F42%3Fid%3D7" +
                    "&apn=com.example.app&ibi=com.example.App&utm_medium=email",
            ),
            sent,
        )
        for (leaked in listOf("token", "secret", "top", "afl", "Title")) assertFalse(leaked in sent, leaked)
    }

    @Test
    fun aPathThatIsNoLinkIsLinkNotFound() = runTest {
        val api = FakeApi { HttpResponse(404, """{"error":{"code":"NOT_FOUND"}}""") }
        val errors = mutableListOf<StrybokException>()
        val tapped = "https://$HOST/no/such/route"
        val sdk = sdk(api, source = FakeLinkSource(initialLink = tapped), errors = errors, parameterLinks = keys)
        sdk.start()
        advanceUntilIdle()
        val error = assertIs<LinkNotFoundException>(errors.single())
        assertEquals(tapped, error.url)
        assertEquals(1, api.resolves.size, "a 404 is not retried")
        sdk.dispose()
    }

    @Test
    fun withoutTheOptionPathLinksAndLongLinksAreDroppedSilently() = runTest {
        for (tapped in listOf(
            "https://$HOST/product/42?id=7",
            "https://$HOST/?link=https%3A%2F%2Fexample.com%2Fp",
            "https://$HOST/",
            "https://$HOST?id=7",
        )) {
            val api = FakeApi()
            val errors = mutableListOf<StrybokException>()
            val sdk = sdk(api, source = FakeLinkSource(initialLink = tapped), errors = errors)
            val received = collectLinks(sdk)
            assertFalse(sdk.isStrybokLink(tapped), tapped)
            sdk.start()
            advanceUntilIdle()
            assertTrue(api.resolves.isEmpty(), tapped)
            assertTrue(received.isEmpty(), tapped)
            assertEquals(emptyList(), errors, tapped)
            sdk.dispose()
        }
    }

    @Test
    fun theSameUrlTwiceIsOneTapAndDifferentStringsAreTwoLinks() = runTest {
        val api = FakeApi { answer() }
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source, parameterLinks = keys)
        val received = collectLinks(sdk)
        sdk.start()
        advanceUntilIdle()
        source.emit("https://$HOST/product/42?id=7&x=1")
        source.emit("https://$HOST/product/42?id=7&x=1")
        source.emit("https://$HOST/product/42?id=7&x=2")
        advanceUntilIdle()
        assertEquals(2, received.size)
        assertEquals(setOf(resolveRequestUrl("https://$HOST/product/42?id=7")), api.resolves.map { it.url }.toSet())
        assertEquals(2, api.resolves.size)
        sdk.dispose()
    }

    // --- deferred links keep the slug rule -----------------------------------------------------

    private suspend fun TestScope.started(sdk: Strybok): Strybok {
        sdk.start()
        advanceUntilIdle()
        return sdk
    }

    @Test
    fun anAndroidRedeemAnswerWhoseUrlIsNotASlugLinkIsRefused() = runTest {
        for (answerUrl in listOf("https://$HOST/product/42", "https://$HOST/", "https://$HOST/?link=https%3A%2F%2Fexample.com%2Fp")) {
            val api = FakeApi()
            api.onRedeem = { redeemed(url = answerUrl) }
            val platform = FakeDeferredPlatform(DeferredKind.INSTALL_REFERRER)
            val sdk = started(launchApp(api, platform, FakeSource(referrer()), parameterLinks = keys))
            val received = collectLinks(sdk)
            advanceUntilIdle()
            assertEquals(DeferredLinkStatus.FAILED, sdk.deferredLinkStatus.value, answerUrl)
            assertTrue(received.isEmpty(), answerUrl)
            assertTrue(platform.store.done, answerUrl)
            // The same URL arriving from the OS is a path link of this app.
            assertTrue(sdk.isStrybokLink(answerUrl), answerUrl)
            sdk.dispose()
        }
    }

    @Test
    fun aPastedLinkMustStillHaveASingleSlug() = runTest {
        val api = FakeApi()
        val sdk = started(launchApp(api, FakeDeferredPlatform(DeferredKind.IOS), parameterLinks = keys))
        for (text in listOf(
            "https://$HOST/product/42?strybok_click=$CLICK_ID",
            "https://$HOST/?strybok_click=$CLICK_ID",
            "https://$HOST?strybok_click=$CLICK_ID",
            "https://$HOST/?link=https%3A%2F%2Fexample.com%2Fp&strybok_click=$CLICK_ID",
        )) {
            assertEquals(PastedLinkResult.NOT_OURS, sdk.redeemPastedLink(text), text)
        }
        assertTrue(api.redeems.isEmpty(), "no request")
        assertEquals(PastedLinkResult.DELIVERED, sdk.redeemPastedLink(COPIED))
        sdk.dispose()
    }

    @Test
    fun aPastedLinkRedeemAnswerWhoseUrlIsNotASlugLinkIsRefused() = runTest {
        val api = FakeApi()
        api.onRedeem = { redeemed(url = "https://$HOST/product/42") }
        val platform = FakeDeferredPlatform(DeferredKind.IOS)
        val sdk = started(launchApp(api, platform, parameterLinks = keys))
        val received = collectLinks(sdk)
        assertEquals(PastedLinkResult.FAILED, sdk.redeemPastedLink(COPIED))
        advanceUntilIdle()
        assertEquals(DeferredLinkStatus.FAILED, sdk.deferredLinkStatus.value)
        assertTrue(received.isEmpty())
        assertTrue(platform.store.done)
        sdk.dispose()
    }

    @Test
    fun aMatchAnswerWhoseUrlIsNotASlugLinkIsRefused() = runTest {
        val api = FakeApi()
        api.onMatch = { redeemed(url = "https://$HOST/product/42", matched = true) }
        val platform = FakeDeferredPlatform(DeferredKind.IOS)
        val sdk = launchApp(api, platform, matching = true, parameterLinks = keys)
        val received = collectLinks(sdk)
        started(sdk)
        assertEquals(DeferredLinkStatus.FAILED, sdk.deferredLinkStatus.value)
        assertEquals(1, api.matches.size)
        assertTrue(received.isEmpty())
        sdk.dispose()
    }

    @Test
    fun aPathLinkIsANormalLinkThatWinsOverADeferredOne() = runTest {
        val api = FakeApi { answer() }
        val source = FakeSource(referrer())
        val sdk = launchApp(
            api,
            FakeDeferredPlatform(DeferredKind.INSTALL_REFERRER),
            source,
            linkSource = FakeLinkSource(initialLink = "https://$HOST/product/42?id=7"),
            parameterLinks = keys,
        )
        val received = collectLinks(sdk)
        started(sdk)
        assertEquals(DeferredLinkStatus.SUPERSEDED, sdk.deferredLinkStatus.value)
        assertTrue(api.redeems.isEmpty(), "no request")
        assertEquals(listOf(false), received.map { it.isDeferred })
        sdk.dispose()
    }
}
