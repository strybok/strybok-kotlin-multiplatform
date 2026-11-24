package com.strybok.kmp

import com.strybok.kmp.internal.platformAttestor
import com.strybok.kmp.internal.requestHashOf
import com.strybok.kmp.internal.sha256
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class AttestationJvmTest {
    @Test
    fun sha256AgreesWithTheJdkForEveryPaddingCase() {
        for (size in 0..300) {
            val input = ByteArray(size) { (it * 31 + size).toByte() }
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(input), sha256(input), "size $size")
        }
    }

    @Test
    fun requestHashAgreesWithTheJdk() {
        val challenge = "q7Jc0bB1hYk3Zr9uVtXw2eLm5nOa8dFs4gHiKjPlQyE"
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8)))
        assertEquals(expected, requestHashOf(challenge))
    }

    /** The JVM target has neither App Attest nor Play Integrity. */
    @Test
    fun theJvmTargetCannotAttest() = runTest {
        val integrity = FakeIntegrity()
        assertNull(platformAttestor(integrity))
        assertNull(platformAttestor(null))

        val api = AttestApi { Clock.System.now() }
        val strybok = Strybok(
            apiBaseUrl = API, allowedHosts = setOf(HOST), transport = api, clientKey = CLIENT_KEY,
            coroutineContext = StandardTestDispatcher(testScheduler),
            attestation = true, integrityTokenProvider = integrity,
        )
        strybok.createLink("/product/42")
        api.onCreate = { errorAnswer(401, "ATTESTATION_REQUIRED") }
        assertFailsWith<StrybokAttestationRequiredException> { strybok.createLink("/product/42") }

        assertEquals(listOf("/v1/public/links", "/v1/public/links"), api.all.map { it.path })
        assertTrue(api.creates.none { ATTESTATION_HEADER in it.headers })
        assertTrue(integrity.hashes.isEmpty())
        strybok.dispose()
    }
}
