package com.strybok.kmp.internal

import com.strybok.kmp.HttpRequest
import com.strybok.kmp.HttpTransport
import com.strybok.kmp.IntegrityTokenProvider
import com.strybok.kmp.StrybokUnauthorizedException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The optional attestation of `createLink` ("Attestation (optional)" in the SDK contract):
 *
 *   challenge -> platform proof bound to it -> session token -> createLink
 *
 * Everything here is common code, including the App Attest flow, so the whole contract is
 * tested on every target with fakes. Only the two thin wrappers around Apple's and Google's
 * APIs are platform code.
 */

internal const val KEY_REFUSED_MESSAGE =
    "The client key was refused: it is wrong or was replaced, or link creation is switched off"

/** A token is not used during the last minute before the service's `expiresAt`. */
internal val TOKEN_EXPIRY_MARGIN: Duration = 60.seconds

/** Pause after an attempt that ended with "cannot attest". */
internal val ATTESTATION_RETRY_PAUSE: Duration = 60.seconds

/** Upper bound for one call into Play Integrity or App Attest; both can talk to a server. */
internal val PROOF_TIMEOUT: Duration = 30.seconds

private const val MAX_FIELD_LENGTH = 4096

/** A session token as the service granted it. */
internal class AttestationGrant(val token: String, val expiresAt: Instant)

/** Produces a session token for one platform; `null` means "cannot attest now". */
internal fun interface Attestor {
    /** @throws StrybokUnauthorizedException when the service refuses the client key. */
    suspend fun attest(api: AttestationApi): AttestationGrant?
}

/** `clientDataHash` of App Attest: SHA-256 of the challenge's UTF-8 bytes. */
internal fun clientDataHashOf(challenge: String): ByteArray = sha256(challenge.encodeToByteArray())

/** `requestHash` of a Play Integrity standard request: unpadded base64url of [clientDataHashOf]. */
internal fun requestHashOf(challenge: String): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(clientDataHashOf(challenge))

/** The answer of an endpoint that exchanges a proof for a session token. */
internal sealed interface Exchange {
    class Granted(val grant: AttestationGrant) : Exchange

    /** Anything else. [code] and [reason] are `error.code` and `error.details.reason`, if present. */
    class Refused(val status: Int?, val code: String?, val reason: String?) : Exchange {
        fun failedWith(reason: String): Boolean =
            status == 403 && code == "ATTESTATION_FAILED" && this.reason == reason
    }
}

/**
 * The service's attestation endpoints. Requests use the same timeout as every other request
 * and are never retried: every attempt spends a challenge.
 */
internal class AttestationApi(
    private val apiBaseUrl: String,
    private val clientKey: String,
    private val timeout: Duration,
    private val transport: HttpTransport,
) {
    /**
     * Identifies the API host and client key without containing either; storage that outlives
     * the process (the App Attest key id) is scoped by it.
     */
    val scopeId: String by lazy {
        sha256("$apiBaseUrl\n$clientKey".encodeToByteArray()).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    /** A fresh single-use challenge, or `null` when the service did not give one. */
    suspend fun challenge(): String? {
        val (status, body) = post("/v1/public/attest/challenge", null) ?: return null
        if (status != 200) return null
        return parseObject(body)?.text("challenge")
    }

    suspend fun exchange(path: String, body: JsonObject): Exchange {
        val (status, answer) = post(path, body.toString()) ?: return Exchange.Refused(null, null, null)
        val json = parseObject(answer)
        if (status == 200) {
            val token = json?.text("token")
            val expiresAt = json?.text("expiresAt")?.let { Instant.parseOrNull(it) }
            // The token goes into an HTTP header; anything else than printable ASCII is not ours.
            if (token != null && expiresAt != null && token.all { it in '!'..'~' }) {
                return Exchange.Granted(AttestationGrant(token, expiresAt))
            }
            return Exchange.Refused(status, null, null)
        }
        val error = json?.get("error") as? JsonObject
        return Exchange.Refused(status, error?.text("code"), (error?.get("details") as? JsonObject)?.text("reason"))
    }

    /**
     * Status and body of the answer; `null` when there was none (timeout, transport failure).
     *
     * @throws StrybokUnauthorizedException for a 401: the client key is wrong or switched off.
     */
    private suspend fun post(path: String, body: String?): Pair<Int, String>? {
        val headers = buildMap {
            if (body != null) put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("X-Strybok-Key", clientKey)
        }
        val request = HttpRequest("POST", apiBaseUrl + path, headers, body, timeout)
        val response = try {
            withTimeoutOrNull(timeout) { transport.execute(request) }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive() // We were cancelled: propagate.
            null
        } catch (_: Exception) {
            null
        } ?: return null
        if (response.statusCode == 401) throw StrybokUnauthorizedException(KEY_REFUSED_MESSAGE)
        return response.statusCode to response.body
    }
}

private fun parseObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (_: IllegalArgumentException) { // SerializationException
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
    ?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH }

/**
 * Runs a call into a platform API that must never hurt the app: bounded by [PROOF_TIMEOUT],
 * and every failure but our own cancellation becomes [onFailure]'s value.
 */
private suspend fun <T : Any> guarded(onFailure: (Throwable?) -> T?, block: suspend () -> T?): T? = try {
    withTimeoutOrNull(PROOF_TIMEOUT) { block() } ?: onFailure(null)
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    onFailure(e)
} catch (e: Throwable) {
    // Also errors like a missing class when the app does not ship the platform library.
    onFailure(e)
}

// --- Android: Play Integrity ---------------------------------------------------------------

/** Play Integrity (standard request): one token per challenge, exchanged at `/attest/android`. */
internal class IntegrityAttestor(private val provider: IntegrityTokenProvider) : Attestor {
    override suspend fun attest(api: AttestationApi): AttestationGrant? {
        val challenge = api.challenge() ?: return null
        val integrityToken = guarded({ null }) { provider.requestIntegrityToken(requestHashOf(challenge)) }
        if (integrityToken.isNullOrEmpty()) return null
        val body = buildJsonObject {
            put("integrityToken", integrityToken)
            put("challenge", challenge)
        }
        return (api.exchange("/v1/public/attest/android", body) as? Exchange.Granted)?.grant
    }
}

// --- iOS: App Attest -----------------------------------------------------------------------

/** Why a call into App Attest failed, as far as the flow cares. */
internal enum class AppAttestFailure {
    /** `DCError.invalidKey`: the key is gone (reinstall, restore) or cannot be used for this. */
    InvalidKey,

    /** `DCError.serverUnavailable`: Apple could not be reached; the same key can be tried again. */
    ServerUnavailable,
    Other,
}

internal class AppAttestException(val failure: AppAttestFailure, message: String? = null) : Exception(message)

/** `DCAppAttestService`, or a fake of it. Failures are thrown as [AppAttestException]. */
internal interface AppAttestService {
    /** `false` in the simulator, in some extensions and on some Macs. */
    val isSupported: Boolean

    /** A new key in the Secure Enclave; returns its identifier (standard base64, 44 characters). */
    suspend fun generateKey(): String

    /** Asks Apple to attest [keyId]; returns the attestation object. Works once per key. */
    suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray

    suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray
}

/** Small persistent string storage (app storage on iOS). Implementations never throw. */
internal interface KeyIdStore {
    fun get(name: String): String?

    fun set(name: String, value: String)

    fun remove(name: String)
}

/**
 * App Attest: a device key is attested once (`/attest/ios/register`) and proves itself with an
 * assertion afterwards (`/attest/ios/assert`). The key id is kept per API host and client key.
 */
internal class AppAttestAttestor(
    private val service: AppAttestService,
    private val store: KeyIdStore,
) : Attestor {
    private sealed interface Step {
        class Done(val grant: AttestationGrant?) : Step

        /** The stored key is of no use any more: forget it and register a new one. */
        data object StartOver : Step
    }

    override suspend fun attest(api: AttestationApi): AttestationGrant? {
        if (guarded({ false }) { service.isSupported } != true) return null
        val keyName = "key.${api.scopeId}"
        val stored = store.get(keyName)
        if (stored != null) {
            when (val step = assert(api, stored)) {
                is Step.Done -> return step.grant
                Step.StartOver -> store.remove(keyName)
            }
        }
        return register(api, keyName)
    }

    private suspend fun assert(api: AttestationApi, keyId: String): Step {
        val challenge = api.challenge() ?: return Step.Done(null)
        var invalidKey = false
        val assertion = guarded({ error ->
            invalidKey = (error as? AppAttestException)?.failure == AppAttestFailure.InvalidKey
            null
        }) { service.generateAssertion(keyId, clientDataHashOf(challenge)) }
        if (assertion == null) return if (invalidKey) Step.StartOver else Step.Done(null)
        val body = buildJsonObject {
            put("keyId", keyId)
            put("assertion", Base64.Default.encode(assertion))
            put("challenge", challenge)
        }
        return when (val answer = api.exchange("/v1/public/attest/ios/assert", body)) {
            is Exchange.Granted -> Step.Done(answer.grant)
            // Also after the service cleaned up a key that was not used for a long time.
            is Exchange.Refused -> if (answer.failedWith("unknown_key")) Step.StartOver else Step.Done(null)
        }
    }

    /** The first-time path, always with a challenge of its own. */
    private suspend fun register(api: AttestationApi, keyName: String): AttestationGrant? {
        val pendingName = "pending.${api.scopeId}"
        val challenge = api.challenge() ?: return null
        // A key Apple could not be asked about last time is tried again instead of thrown away.
        val keyId = store.get(pendingName)
            ?: guarded({ null }) { service.generateKey() }?.takeIf { it.isNotEmpty() }
            ?: return null
        var keepKey = false
        val attestation = guarded({ error ->
            // No answer in time counts like "Apple is unavailable"; everything else spoils the key.
            keepKey = error == null || (error as? AppAttestException)?.failure == AppAttestFailure.ServerUnavailable
            null
        }) { service.attestKey(keyId, clientDataHashOf(challenge)) }
        if (attestation == null) {
            if (keepKey) store.set(pendingName, keyId) else store.remove(pendingName)
            return null
        }
        store.remove(pendingName) // A key is attested once.
        val body = buildJsonObject {
            put("keyId", keyId)
            put("attestation", Base64.Default.encode(attestation))
            put("challenge", challenge)
        }
        return when (val answer = api.exchange("/v1/public/attest/ios/register", body)) {
            is Exchange.Granted -> {
                store.set(keyName, keyId) // Only now: the service knows the key.
                answer.grant
            }
            is Exchange.Refused -> {
                if (!answer.failedWith("key_exists")) return null
                // The service already has this key (an earlier answer got lost): it proves
                // itself with an assertion from now on. If that fails too, start over next time.
                store.set(keyName, keyId)
                when (val step = assert(api, keyId)) {
                    is Step.Done -> step.grant
                    Step.StartOver -> null.also { store.remove(keyName) }
                }
            }
        }
    }
}

// --- token cache ---------------------------------------------------------------------------

/**
 * One session token per SDK instance, in memory only. Created by the first `createLink` that
 * needs it; nothing here runs on its own or is written to disk, to logs or into an error.
 */
internal class AttestationSession(
    private val attestor: Attestor,
    private val api: AttestationApi,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks,
    /** Wall clock; only compared with the service's `expiresAt`. */
    private val now: () -> Instant,
) {
    private class Cached(val token: String, val useUntil: ComparableTimeMark, val useUntilWall: Instant)

    private class State(
        val cached: Cached? = null,
        val inFlight: Deferred<String?>? = null,
        /** When the last attempt ended with "cannot attest". */
        val unavailableAt: ComparableTimeMark? = null,
    )

    private val state = AtomicReference(State())

    /**
     * A token for the `X-Strybok-Attestation` header, or `null` when this device cannot attest
     * right now (the request is then sent without the header).
     *
     * Concurrent callers share one attestation. After a failed attempt the next one is not
     * started for [ATTESTATION_RETRY_PAUSE], so a device that cannot attest does not pay for it
     * on every share.
     *
     * @throws StrybokUnauthorizedException when the service refuses the client key.
     */
    suspend fun token(): String? {
        while (true) {
            val current = state.load()
            val mark = timeSource.markNow()
            current.cached?.let { if (mark < it.useUntil && now() < it.useUntilWall) return it.token }
            current.unavailableAt?.let { if (mark - it < ATTESTATION_RETRY_PAUSE) return null }
            current.inFlight?.let { return await(it) }
            // In the SDK's own scope: a caller that gives up does not take the others down.
            val attempt = scope.async(start = CoroutineStart.LAZY) { attestAndRemember() }
            if (state.compareAndSet(current, State(inFlight = attempt, unavailableAt = current.unavailableAt))) {
                attempt.start() // Even when this caller is already on its way out.
                return await(attempt)
            }
            attempt.cancel() // Somebody else was faster: join theirs.
        }
    }

    private suspend fun await(attempt: Deferred<String?>): String? = try {
        attempt.await()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive() // The caller was cancelled: propagate.
        null // The SDK was disposed; the caller notices before it sends anything.
    }

    /** Forgets [token] after the service refused it; a newer token is left alone. */
    fun discard(token: String) {
        while (true) {
            val current = state.load()
            if (current.cached?.token != token) return
            if (state.compareAndSet(current, State(null, current.inFlight, current.unavailableAt))) return
        }
    }

    private suspend fun attestAndRemember(): String? {
        val grant = try {
            attestor.attest(api)
        } catch (e: Throwable) {
            finish { State() }
            throw e
        }
        if (grant == null) {
            val failedAt = timeSource.markNow()
            finish { State(unavailableAt = failedAt) }
            return null
        }
        val useUntilWall = grant.expiresAt - TOKEN_EXPIRY_MARGIN
        val lifetime = useUntilWall - now()
        // A device whose clock is far off gets a token it may use for this call only.
        val cached = if (lifetime.isPositive()) Cached(grant.token, timeSource.markNow() + lifetime, useUntilWall) else null
        finish { State(cached = cached) }
        return grant.token
    }

    /** Only the attempt in flight replaces the state, so a plain swap is enough. */
    private fun finish(next: () -> State) {
        state.store(next())
    }
}
