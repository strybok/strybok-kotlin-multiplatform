package com.strybok.kmp

import com.strybok.kmp.internal.AppAttestException
import com.strybok.kmp.internal.AppAttestFailure
import com.strybok.kmp.internal.AppAttestService
import com.strybok.kmp.internal.KeyIdStore
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val ATTESTATION_HEADER = "X-Strybok-Attestation"

fun errorAnswer(status: Int, code: String, details: String = "{}"): HttpResponse =
    HttpResponse(status, """{"error":{"code":"$code","message":"Refused","details":$details}}""")

val HttpRequest.path: String get() = "/" + url.substringAfter("://").substringAfter('/', "")
val HttpRequest.json: JsonObject get() = Json.parseToJsonElement(body!!).jsonObject
val HttpRequest.attestation: String? get() = headers[ATTESTATION_HEADER]

fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

/** The service's attestation endpoints and `POST /v1/public/links`, as far as the SDK can tell. */
class AttestApi(private val now: () -> Instant) : HttpTransport {
    val all = mutableListOf<HttpRequest>()
    val challenges = mutableListOf<HttpRequest>()

    /** Requests that exchange a proof: `/attest/android`, `/attest/ios/register`, `/attest/ios/assert`. */
    val exchanges = mutableListOf<HttpRequest>()
    val creates = mutableListOf<HttpRequest>()

    /** Challenges are 43 base64url characters, like the real ones. */
    fun challengeNo(n: Int): String = "challenge-$n".padEnd(43, 'A')

    fun tokenNo(n: Int): String = "sat1.session-token-$n.signature"

    fun grant(n: Int, expiresAt: Instant = now() + 1.hours): HttpResponse =
        HttpResponse(200, """{"token":"${tokenNo(n)}","expiresAt":"$expiresAt"}""")

    val defaultChallenge: suspend (Int) -> HttpResponse = { n ->
        HttpResponse(200, """{"challenge":"${challengeNo(n)}","expiresAt":"2026-11-24T10:05:00.000Z"}""")
    }
    var onChallenge: suspend (Int) -> HttpResponse = defaultChallenge

    /** Called with the request and its number among the exchanges (from 1). */
    var onExchange: suspend (HttpRequest, Int) -> HttpResponse = { _, n -> grant(n) }
    var onCreate: suspend (HttpRequest) -> HttpResponse = { created() }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        all += request
        return when {
            request.path == "/v1/public/attest/challenge" -> {
                challenges += request
                onChallenge(challenges.size)
            }
            request.path.startsWith("/v1/public/attest/") -> {
                exchanges += request
                onExchange(request, exchanges.size)
            }
            request.path == "/v1/public/links" -> {
                creates += request
                onCreate(request)
            }
            request.path == "/v1/public/events" -> HttpResponse(202, "")
            else -> ok()
        }
    }
}

/** Play Integrity as the SDK sees it: counts requests and answers with a token that looks like Google's. */
class FakeIntegrity : IntegrityTokenProvider {
    val hashes = mutableListOf<String>()

    fun tokenFor(hash: String): String = "integrity.token-for.$hash".padEnd(120, 'x')

    var answer: suspend (String) -> String? = { hash -> tokenFor(hash) }

    override suspend fun requestIntegrityToken(requestHash: String): String? {
        hashes += requestHash
        return answer(requestHash)
    }
}

/** `DCAppAttestService` as the SDK sees it. Keys are numbered; proofs name their key and hash. */
internal class FakeAppAttest : AppAttestService {
    override var isSupported: Boolean = true

    /** Every call, e.g. `generateKey`, `attestKey:<keyId>`, `generateAssertion:<keyId>`. */
    val calls = mutableListOf<String>()
    val hashes = mutableListOf<ByteArray>()
    private var keys = 0

    var onGenerateKey: (suspend () -> String)? = null
    var onAttestKey: (suspend (String) -> ByteArray)? = null
    var onGenerateAssertion: (suspend (String) -> ByteArray)? = null

    /** Key ids look like the real ones: standard base64 of 32 bytes, 44 characters. */
    fun keyNo(n: Int): String = "key$n".padEnd(43, 'A') + "="

    fun attestationOf(keyId: String): ByteArray = "attestation-of-$keyId".encodeToByteArray()

    fun assertionOf(keyId: String): ByteArray = "assertion-by-$keyId".encodeToByteArray()

    override suspend fun generateKey(): String {
        calls += "generateKey"
        return onGenerateKey?.invoke() ?: keyNo(++keys)
    }

    override suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray {
        calls += "attestKey:$keyId"
        hashes += clientDataHash
        return onAttestKey?.invoke(keyId) ?: attestationOf(keyId)
    }

    override suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray {
        calls += "generateAssertion:$keyId"
        hashes += clientDataHash
        return onGenerateAssertion?.invoke(keyId) ?: assertionOf(keyId)
    }

    companion object {
        fun failing(failure: AppAttestFailure): Nothing = throw AppAttestException(failure)
    }
}

/** The stored key ids, in memory. */
internal class MemoryKeyIdStore : KeyIdStore {
    val values = mutableMapOf<String, String>()

    override fun get(name: String): String? = values[name]

    override fun set(name: String, value: String) {
        values[name] = value
    }

    override fun remove(name: String) {
        values.remove(name)
    }
}
