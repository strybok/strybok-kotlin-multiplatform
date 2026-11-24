package com.strybok.kmp

import com.strybok.kmp.internal.normalizeApiBaseUrl
import com.strybok.kmp.internal.parseLinkUrl
import com.strybok.kmp.internal.percentEncode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkUrlTest {
    private val hosts = setOf(HOST)

    @Test
    fun acceptsHttpsLinksOnAnAllowedHostWithASingleSlug() {
        for (url in listOf(
            INVITE,
            "https://MY-APP.Strybok.Link/AbC_7-x",
            "HTTPS://$HOST/abc",
            "https://$HOST/abc?utm=1#frag",
            "https://$HOST/abc#frag?x",
            "https://$HOST/abc?next=/a/b&u=user@example.com#/c",
            "https://$HOST:443/abc",
            "https://$HOST/a",
            "https://$HOST/" + "a".repeat(64),
            "https://$HOST/abc?q=café",
        )) {
            assertNotNull(parseLinkUrl(url, hosts), url)
        }
    }

    @Test
    fun rejectsEverythingElse() {
        for (url in listOf(
            "",
            "https://",
            "https:/$HOST/invite777",
            "http://$HOST/invite777",
            "myapp://$HOST/invite777",
            "//$HOST/invite777",
            "$HOST/invite777",
            " https://$HOST/invite777",
            "https://evil.example.com/invite777",
            "https://$HOST.evil.example.com/invite777",
            "https://evil-$HOST/invite777",
            "https://$HOST./invite777",
            "https://user:pw@$HOST/invite777",
            "https://user@$HOST/invite777",
            "https://$HOST@evil.example.com/invite777",
            "https://evil.example.com#@$HOST/invite777",
            "https://evil.example.com?@$HOST/invite777",
            "https://evil.example.com\\@$HOST/invite777",
            "https://$HOST\\@evil.example.com/invite777",
            "https://$HOST:8443/invite777",
            "https://$HOST:80/invite777",
            "https://$HOST:/invite777",
            "https://$HOST:0443/invite777",
            "https://$HOST:443:443/invite777",
            "https://[::1]/invite777",
            "https://my-app.strybok.lin%6b/invite777",
            "https://$HOST",
            "https://$HOST?x=1",
            "https://$HOST/",
            "https://$HOST//invite777",
            "https://$HOST/invite777/",
            "https://$HOST/a/b",
            "https://$HOST/.well-known/assetlinks.json",
            "https://$HOST/a%2Fb",
            "https://$HOST/a.b",
            "https://$HOST/a;b",
            "https://$HOST/a b",
            "https://$HOST/abc\n",
            "https://$HOST/abc\t?x=1",
            "https://$HOST/abc?x=a b",
            "https://$HOST/café",
            "https://my-app.strybok.linK/abc", // Kelvin sign lower-cases to "k" in Unicode.
            "https://$HOST/" + "a".repeat(65),
        )) {
            assertNull(parseLinkUrl(url, hosts), url)
        }
    }

    @Test
    fun lookupIsLowerCasedHostAndSlugOnly() {
        val link = assertNotNull(parseLinkUrl("https://My-App.STRYBOK.link:443/AbC?token=secret#f", hosts))
        assertEquals(HOST, link.host)
        assertEquals("AbC", link.slug)
        assertEquals("https://$HOST/AbC", link.lookup)
    }

    @Test
    fun allowedHostsOfTheSdkAreComparedLowerCase() {
        val sdk = Strybok(apiBaseUrl = API, allowedHosts = setOf("My-App.Strybok.Link"))
        try {
            assertEquals(setOf(HOST), sdk.allowedHosts)
            assertTrue(sdk.isStrybokLink(INVITE))
            assertTrue(sdk.isStrybokLink("https://MY-APP.strybok.link/AbC_7-x"))
            assertFalse(sdk.isStrybokLink("https://evil.example.com/invite777"))
        } finally {
            sdk.dispose()
        }
    }

    @Test
    fun percentEncodesEverythingButUnreservedCharacters() {
        assertEquals("https%3A%2F%2Fmy-app.strybok.link%2FAb_c-7", percentEncode("https://my-app.strybok.link/Ab_c-7"))
        assertEquals("a%20b%26c%3Dd~%C3%A9", percentEncode("a b&c=d~é"))
    }

    @Test
    fun apiBaseUrlIsValidatedAndNormalized() {
        assertEquals("https://api.strybok.com", normalizeApiBaseUrl("https://api.strybok.com/"))
        assertEquals("https://example.com/strybok", normalizeApiBaseUrl(" https://example.com/strybok// "))
        assertEquals("http://10.0.2.2:8787", normalizeApiBaseUrl("http://10.0.2.2:8787"))
        for (bad in listOf("", "api.strybok.com", "ftp://api.strybok.com", "https://", "https://a.b/?x=1", "https://a.b/#f", "https://a b")) {
            assertFailsWith<IllegalArgumentException>(bad) { normalizeApiBaseUrl(bad) }
        }
    }
}
