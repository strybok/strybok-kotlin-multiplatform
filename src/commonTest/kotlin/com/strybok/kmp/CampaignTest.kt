package com.strybok.kmp

import com.strybok.kmp.internal.appOpenEventBody
import com.strybok.kmp.internal.parseLinkUrl
import com.strybok.kmp.internal.parseResolveResponse
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Campaign values (SDK contract, "Campaign query"; decision 0018). */
@OptIn(ExperimentalCoroutinesApi::class)
class CampaignTest {
    private val hosts = setOf(HOST)

    private fun lookup(url: String): String = assertNotNull(parseLinkUrl(url, hosts), url).lookup

    private fun resolved(campaign: String?): HttpResponse = HttpResponse(
        200,
        """{"linkId":"link_1","appId":"app_1","routePath":"/x"${campaign?.let { ""","campaign":$it""" } ?: ""}}""",
    )

    // --- lookup ----------------------------------------------------------------------------

    @Test
    fun theExampleOfTheContract() {
        val link = assertNotNull(
            parseLinkUrl(
                "https://MyApp.strybok.link/invite777?utm_source=instagram&fbclid=abc&utm_medium=two%20words&utm_source=x#top",
                setOf("myapp.strybok.link"),
            ),
        )
        assertEquals("https://myapp.strybok.link/invite777?utm_source=instagram", link.lookup)
    }

    @Test
    fun withoutCampaignParametersTheLookupIsWhatEarlierVersionsSent() {
        for (url in listOf(
            INVITE,
            "$INVITE?",
            "$INVITE#utm_source=fragment",
            "$INVITE?#utm_source=fragment",
            "$INVITE?token=secret&fbclid=abc&gclid=1&utm_id=7#f",
            "$INVITE?a=1#?utm_source=fragment",
            "$INVITE?utm_source=",
            "$INVITE?utm_source",
            "$INVITE?&&=&=x&",
        )) {
            assertEquals(INVITE, lookup(url), url)
        }
    }

    @Test
    fun keepsTheFiveKeysInTheFixedOrderAndNothingElse() {
        assertEquals(
            "$INVITE?utm_source=s&utm_medium=m&utm_campaign=c&utm_term=t&utm_content=n",
            lookup("$INVITE?utm_content=n&x=1&utm_term=t&utm_campaign=c&token=secret&utm_medium=m&utm_source=s#utm_source=f"),
        )
        assertEquals("$INVITE?utm_medium=m&utm_content=n", lookup("$INVITE?utm_content=n&&utm_medium=m&"))
        assertEquals("$INVITE?utm_source=a", lookup("$INVITE?utm_source=a#b"))
    }

    @Test
    fun keyNamesAreExactAndLowerCase() {
        for (query in listOf(
            "UTM_SOURCE=a",
            "Utm_Source=a",
            "utm_sourcex=a",
            "xutm_source=a",
            "utm_id=a",
            "utm_source%20=a",
            "+utm_source=a",
            "utm_source[]=a",
        )) {
            assertEquals(INVITE, lookup("$INVITE?$query"), query)
        }
    }

    @Test
    fun theFirstOccurrenceOfAKeyCountsEvenWhenItsValueIsDropped() {
        assertEquals("$INVITE?utm_source=first", lookup("$INVITE?utm_source=first&utm_source=second"))
        assertEquals(INVITE, lookup("$INVITE?utm_source=not%20valid&utm_source=second"))
        assertEquals(INVITE, lookup("$INVITE?utm_source=&utm_source=second"))
        assertEquals(INVITE, lookup("$INVITE?utm_source&utm_source=second"))
        assertEquals("$INVITE?utm_medium=m", lookup("$INVITE?utm_source=a%2Fb&utm_medium=m&utm_source=second"))
    }

    @Test
    fun valuesOutsideTheAllowedCharactersOrLengthAreDropped() {
        for (value in listOf(
            "two%20words",
            "two+words",
            "a@b.c",
            "a%40b.c",
            "a/b",
            "a%2Fb",
            "a=b",
            "a%26utm_medium=b",
            "a%3Db",
            "(other)",
            "a,b",
            "a:b",
            "a*b",
            "a!b",
            "a'b",
            "a\"b",
            "<script>",
            "кампанія",
            "%D0%BA%D0%B0",
            "caf%C3%A9",
            "a%00b",
            "a%0Ab",
            "a".repeat(65),
        )) {
            assertEquals(INVITE, lookup("$INVITE?utm_source=$value"), value)
        }
        val longest = "a".repeat(64)
        assertEquals("$INVITE?utm_source=$longest", lookup("$INVITE?utm_source=$longest"))
        assertEquals("$INVITE?utm_source=AZaz09._~-", lookup("$INVITE?utm_source=AZaz09._~-"))
    }

    @Test
    fun percentEncodedKeysAndValuesAreDecoded() {
        assertEquals("$INVITE?utm_source=insta-gram", lookup("$INVITE?utm_source=%69nsta%2Dgram"))
        assertEquals("$INVITE?utm_source=a._~b", lookup("$INVITE?utm_source=a%2e%5f%7Eb"))
        assertEquals("$INVITE?utm_source=x", lookup("$INVITE?utm%5Fsource=x"))
        assertEquals("$INVITE?utm_medium=x", lookup("$INVITE?%75tm_medium=x"))
        // 64 characters after decoding.
        val encoded = "%61".repeat(64)
        assertEquals("$INVITE?utm_term=${"a".repeat(64)}", lookup("$INVITE?utm_term=$encoded"))
        assertEquals(INVITE, lookup("$INVITE?utm_term=$encoded%61"))
    }

    @Test
    fun malformedEscapesAndInvalidUtf8DropTheValueAndNeverThrow() {
        for (value in listOf(
            "%",
            "a%",
            "a%2",
            "%2",
            "%zz",
            "%G0",
            "%0G",
            "a%-1",
            "%+1",
            "%%",
            "%u0041",
            "%C3%28",
            "%FF",
            "%C0%AE",
            "%E2%82",
            "%ED%A0%80",
            "\uD800",
            "a\uDC00b",
        )) {
            assertEquals(INVITE, lookup("$INVITE?utm_source=$value"), value)
            assertEquals("$INVITE?utm_medium=ok", lookup("$INVITE?utm_source=$value&utm_medium=ok"), value)
            assertEquals("$INVITE?utm_medium=ok", lookup("$INVITE?$value=x&utm_medium=ok"), value)
        }
    }

    @Test
    fun aQueryChangesNeitherWhichUrlsAreOursNorTheHostAndPathThatAreUsed() {
        val query = "?utm_source=a&utm_medium=b"
        for (url in listOf(
            "http://$HOST/invite777",
            "https://evil.example.com/invite777",
            "https://user:pw@$HOST/invite777",
            "https://user@$HOST/invite777",
            "https://$HOST:8443/invite777",
            "https://$HOST/a/b",
            "https://$HOST/invite777/",
            "https://$HOST/",
            "https://$HOST",
            "https://$HOST/" + "a".repeat(65),
        )) {
            assertNull(parseLinkUrl(url, hosts), url)
            assertNull(parseLinkUrl(url + query, hosts), url + query)
        }
        // Nothing in a query can stand in for the authority or the path.
        for (url in listOf(
            "https://evil.example.com/x?utm_source=https://$HOST/invite777",
            "https://evil.example.com?utm_source=a@$HOST/invite777",
            "https://$HOST?utm_source=/invite777",
            "https://$HOST/?utm_source=a",
            // Whitespace anywhere still makes the URL invalid, in the query too.
            "$INVITE?utm_source=two words",
            "$INVITE?utm_source=a\n",
        )) {
            assertNull(parseLinkUrl(url, hosts), url)
        }
        val link = assertNotNull(parseLinkUrl("HTTPS://My-App.STRYBOK.link:443/Invite777?utm_source=a&token=s#f", hosts))
        assertEquals(HOST, link.host)
        assertEquals("Invite777", link.slug)
        assertEquals("?utm_source=a", link.campaignQuery)
        assertEquals("https://$HOST/Invite777?utm_source=a", link.lookup)
    }

    // --- resolve ---------------------------------------------------------------------------

    @Test
    fun resolveSendsTheCampaignQueryAndNothingElseOfTheQuery() = runTest {
        val api = FakeApi()
        val url = "https://My-App.Strybok.Link/invite777?token=secret&utm_medium=social&utm_source=instagram&email=a%40b.c#private"
        val link = sdk(api).resolve(url)

        assertEquals(url, link.url)
        val request = api.resolves.single()
        assertEquals(
            "$API/v1/public/resolve?url=https%3A%2F%2F$HOST%2Finvite777%3Futm_source%3Dinstagram%26utm_medium%3Dsocial",
            request.url,
        )
        for (private in listOf("secret", "token", "email", "private", "b.c")) {
            assertFalse(private in request.url, private)
        }
    }

    @Test
    fun aUrlWithoutCampaignParametersIsRequestedExactlyAsBefore() = runTest {
        val api = FakeApi()
        val sdk = sdk(api)
        sdk.resolve(INVITE)
        sdk.resolve("$INVITE?token=secret&utm_source=two%20words#f")
        val expected = "$API/v1/public/resolve?url=https%3A%2F%2F$HOST%2Finvite777"
        assertEquals(listOf(expected, expected), api.resolves.map { it.url })
    }

    @Test
    fun campaignOfTheResolvedLinkHoldsTheFiveStringFieldsTheServerSent() = runTest {
        val api = FakeApi {
            resolved("""{"source":"instagram","medium":"social","campaign":"spring","term":"shoes","content":"banner","extra":"x"}""")
        }
        val campaign = sdk(api).resolve(INVITE).campaign
        assertEquals(LinkCampaign("instagram", "social", "spring", "shoes", "banner"), campaign)
        assertEquals("instagram", campaign.source)
        assertEquals("social", campaign.medium)
        assertEquals("spring", campaign.campaign)
        assertEquals("shoes", campaign.term)
        assertEquals("banner", campaign.content)
        assertFalse(campaign.isEmpty)
    }

    @Test
    fun campaignIsEmptyNeverNullWhenMissingOrNotAnObject() {
        for (campaign in listOf(null, "null", "[]", "[\"a\"]", "\"instagram\"", "7", "true", "{}", """{"other":"x"}""")) {
            val link = assertNotNull(parseResolveResponse(INVITE, resolved(campaign).body), campaign)
            assertTrue(link.campaign.isEmpty, campaign)
            assertSame(LinkCampaign.EMPTY, link.campaign, campaign)
            assertEquals("/x", link.routePath)
        }
    }

    @Test
    fun campaignFieldsThatAreNotStringsAreIgnoredNotErrors() {
        val body = resolved("""{"source":7,"medium":null,"campaign":{"a":1},"term":["x"],"content":"banner","utm_source":"x"}""").body
        assertEquals(LinkCampaign(content = "banner"), assertNotNull(parseResolveResponse(INVITE, body)).campaign)
        // A JSON number or boolean is not a string, whatever its text looks like.
        val unquoted = resolved("""{"source":true,"medium":1.5,"campaign":"c"}""").body
        assertEquals(LinkCampaign(campaign = "c"), assertNotNull(parseResolveResponse(INVITE, unquoted)).campaign)
    }

    @Test
    fun campaignsCompareByValue() {
        assertEquals(LinkCampaign(), LinkCampaign.EMPTY)
        assertEquals(LinkCampaign(source = "s").hashCode(), LinkCampaign(source = "s").hashCode())
        assertNotEquals(LinkCampaign(source = "s"), LinkCampaign(medium = "s"))
        assertTrue(LinkCampaign().isEmpty)
        assertEquals(
            "LinkCampaign(source: s, medium: null, campaign: null, term: null, content: null)",
            LinkCampaign(source = "s").toString(),
        )
    }

    // --- app_open --------------------------------------------------------------------------

    @Test
    fun appOpenReportsTheCampaignExactlyAsResolveReturnedIt() = runTest {
        // The server answers with other values than the URL has: the answer is what is reported.
        val api = FakeApi { resolved("""{"source":"newsletter","content":"footer","ignored":"x","term":5}""") }
        val source = FakeLinkSource()
        val sdk = sdk(api, source = source)
        val received = collectLinks(sdk)
        sdk.start()
        source.emit("$INVITE?utm_source=instagram&utm_medium=social")
        advanceUntilIdle()

        val event = api.events.single()
        assertEquals(setOf("eventId", "linkId", "type", "platform", "sdkVersion", "campaign"), event.keys)
        val campaign = event.getValue("campaign").jsonObject
        assertEquals(setOf("source", "content"), campaign.keys)
        assertEquals("newsletter", campaign.getValue("source").jsonPrimitive.content)
        assertEquals("footer", campaign.getValue("content").jsonPrimitive.content)
        assertEquals(LinkCampaign(source = "newsletter", content = "footer"), received.single().campaign)
        sdk.dispose()
    }

    @Test
    fun appOpenHasNoCampaignWhenResolveReturnedNoneWhateverTheUrlSays() = runTest {
        for (campaign in listOf(null, "{}", "null", "[]", """{"source":1}""")) {
            val api = FakeApi { resolved(campaign) }
            val source = FakeLinkSource()
            val sdk = sdk(api, source = source)
            collectLinks(sdk)
            sdk.start()
            source.emit("$INVITE?utm_source=instagram")
            advanceUntilIdle()
            assertEquals(
                setOf("eventId", "linkId", "type", "platform", "sdkVersion"),
                api.events.single().keys,
                campaign,
            )
            sdk.dispose()
        }
    }

    @Test
    fun appOpenBodyCarriesAllFiveFieldsInOrder() {
        val body = appOpenEventBody("link_1", "ios", "1.2.3", LinkCampaign("s", "m", "c", "t", "n"))
        val campaign = Json.parseToJsonElement(body).jsonObject.getValue("campaign")
        assertEquals("""{"source":"s","medium":"m","campaign":"c","term":"t","content":"n"}""", campaign.toString())
        assertFalse("campaign" in appOpenEventBody("link_1", "ios", "1.2.3"))
    }

    // --- dedupe ----------------------------------------------------------------------------

    @Test
    fun theSameSlugWithDifferentCampaignParametersIsTwoLinks() = runTest {
        val source = FakeLinkSource()
        val api = FakeApi()
        val sdk = sdk(api, source = source)
        val received = collectLinks(sdk)
        sdk.start()

        source.emit("$INVITE?utm_source=a")
        source.emit("$INVITE?utm_source=a") // The same URL string: one tap reported twice.
        source.emit("$INVITE?utm_source=b")
        source.emit(INVITE)
        // Compared as received: a parameter that is never sent still makes another URL.
        source.emit("$INVITE?fbclid=1")
        source.emit("$INVITE?fbclid=1")
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            listOf("$INVITE?utm_source=a", "$INVITE?utm_source=b", INVITE, "$INVITE?fbclid=1"),
            received.map { it.url },
        )
        val plain = "$API/v1/public/resolve?url=https%3A%2F%2F$HOST%2Finvite777"
        assertEquals(
            listOf("$plain%3Futm_source%3Da", "$plain%3Futm_source%3Db", plain, plain),
            api.resolves.map { it.url },
        )
        sdk.dispose()
    }
}
