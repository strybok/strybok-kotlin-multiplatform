package com.strybok.kmp

import com.strybok.kmp.internal.appOpenEventBody
import com.strybok.kmp.internal.AttestationApi
import com.strybok.kmp.internal.AttestationSession
import com.strybok.kmp.internal.Attestor
import com.strybok.kmp.internal.KEY_REFUSED_MESSAGE
import com.strybok.kmp.internal.NotJsonException
import com.strybok.kmp.internal.asciiLowercase
import com.strybok.kmp.internal.createLinkBody
import com.strybok.kmp.internal.normalizeApiBaseUrl
import com.strybok.kmp.internal.parseCreateLinkResponse
import com.strybok.kmp.internal.parseAttestationError
import com.strybok.kmp.internal.parseLinkUrl
import com.strybok.kmp.internal.parseResolveResponse
import com.strybok.kmp.internal.parseRetryAfterSeconds
import com.strybok.kmp.internal.parseValidationError
import com.strybok.kmp.internal.percentEncode
import com.strybok.kmp.internal.platformAttestor
import com.strybok.kmp.internal.platformCoroutineContext
import com.strybok.kmp.internal.platformHttpTransport
import com.strybok.kmp.internal.platformName
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Version reported with analytics events. */
public const val STRYBOK_SDK_VERSION: String = "0.3.0"

/** Returned by [Strybok.addListener]; call [cancel] to stop receiving links. */
public fun interface Subscription {
    public fun cancel()
}

/**
 * Entry point of the SDK.
 *
 * ```kotlin
 * val strybok = Strybok(
 *     apiBaseUrl = "https://api.strybok.com",
 *     allowedHosts = setOf("my-app.strybok.link"),
 * )
 * scope.launch { strybok.links.collect { link -> navigate(link.routePath) } }
 * scope.launch { strybok.start() }
 * ```
 *
 * Every resolved link is delivered exactly once. [createLink] makes a link to share; it needs
 * a `clientKey` and is independent of incoming links. The class is thread-safe.
 */
public class Strybok internal constructor(
    apiBaseUrl: String,
    allowedHosts: Set<String>,
    /** Time allowed for one attempt of a resolve or create-link request. */
    public val timeout: Duration,
    /** Extra attempts after a timeout, network error or 5xx. 4xx answers are never retried. */
    public val maxRetries: Int,
    /** Pause before the first retry; doubles for each further retry. */
    public val retryDelay: Duration,
    /** The identical URL received again within this window is dropped as a duplicate. */
    public val dedupeWindow: Duration,
    /** Whether a best-effort `app_open` event is sent after a link was resolved. */
    public val reportAppOpens: Boolean,
    private val onError: ((StrybokException) -> Unit)?,
    private val linkSource: LinkSource,
    private val transport: HttpTransport,
    coroutineContext: CoroutineContext,
    private val timeSource: TimeSource.WithComparableMarks,
    clientKey: String?,
    /** How `createLink` proves it comes from a genuine app; `null` when it does not (the default). */
    private val attestor: Attestor? = null,
    /** Wall clock; only compared with the expiry of an attestation token. */
    private val wallClock: () -> Instant = { Clock.System.now() },
) {
    /**
     * @param apiBaseUrl base URL of the API, e.g. `https://api.strybok.com`; a path prefix is kept.
     * @param allowedHosts link hostnames of this app; links on any other host are ignored.
     * @param onError receives errors for links of this app that could not be resolved. Links
     *   that are simply not ours are ignored without an error.
     * @param linkSource where URLs come from; the default is fed by [handleLink] and the
     *   platform glue built on it.
     * @param transport performs the HTTP calls; replace it in tests.
     * @param coroutineContext where the SDK works and where [onError] and listeners are called;
     *   the main thread on Android and iOS by default.
     * @param clientKey publishable key of the application (`pk_…`), shown in the dashboard under
     *   "Links from the app". Only needed for [createLink]. It is safe to ship inside the app:
     *   it can only create links for the route patterns the owner listed. Never put a secret
     *   API key (`lb_…`) here.
     * @param attestation extra protection for [createLink], off by default: prove to the service
     *   that the request comes from a genuine build of the app on a genuine device — Apple App
     *   Attest on iOS, Google Play Integrity on Android (which also needs
     *   [integrityTokenProvider]). The JVM target cannot attest. Needs [clientKey]. The proof is
     *   made lazily inside `createLink`, never when the SDK starts or a link arrives. See
     *   `docs/link-creation-security.md` for what it does and does not protect.
     * @param integrityTokenProvider Android only: where the Play Integrity token comes from when
     *   [attestation] is on, usually a `PlayIntegrityTokenProvider`. `null` — for example in a
     *   build for a market without Google Play — means the Android app cannot attest: links are
     *   requested without a proof and the service decides. Ignored on iOS and the JVM.
     */
    public constructor(
        apiBaseUrl: String,
        allowedHosts: Set<String>,
        timeout: Duration = 5.seconds,
        maxRetries: Int = 2,
        retryDelay: Duration = 400.milliseconds,
        dedupeWindow: Duration = 2.seconds,
        reportAppOpens: Boolean = true,
        onError: ((StrybokException) -> Unit)? = null,
        linkSource: LinkSource = PushLinkSource(),
        transport: HttpTransport = platformHttpTransport(),
        coroutineContext: CoroutineContext = platformCoroutineContext(),
        clientKey: String? = null,
        attestation: Boolean = false,
        integrityTokenProvider: IntegrityTokenProvider? = null,
    ) : this(
        apiBaseUrl, allowedHosts, timeout, maxRetries, retryDelay, dedupeWindow, reportAppOpens,
        onError, linkSource, transport, coroutineContext, TimeSource.Monotonic, clientKey,
        if (attestation) platformAttestor(integrityTokenProvider) else null,
    )

    /** All defaults; convenient from Swift, where Kotlin default arguments are not available. */
    public constructor(
        apiBaseUrl: String,
        allowedHosts: Set<String>,
        onError: ((StrybokException) -> Unit)?,
    ) : this(apiBaseUrl, allowedHosts, timeout = 5.seconds, onError = onError)

    /** All defaults plus the [clientKey] for [createLink]; convenient from Swift. */
    public constructor(
        apiBaseUrl: String,
        allowedHosts: Set<String>,
        clientKey: String?,
        onError: ((StrybokException) -> Unit)?,
    ) : this(apiBaseUrl, allowedHosts, timeout = 5.seconds, onError = onError, clientKey = clientKey)

    /**
     * All defaults plus the [clientKey] and the [attestation] switch for [createLink];
     * convenient from Swift, where App Attest needs nothing else.
     */
    public constructor(
        apiBaseUrl: String,
        allowedHosts: Set<String>,
        clientKey: String?,
        attestation: Boolean,
        onError: ((StrybokException) -> Unit)?,
    ) : this(
        apiBaseUrl, allowedHosts, timeout = 5.seconds, onError = onError, clientKey = clientKey,
        attestation = attestation,
    )

    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(!retryDelay.isNegative()) { "retryDelay must not be negative" }
    }

    /** Base URL of the Strybok API, without a trailing slash. */
    public val apiBaseUrl: String = normalizeApiBaseUrl(apiBaseUrl)

    /** Link hostnames of this app, lower-case. */
    public val allowedHosts: Set<String> = allowedHosts.mapTo(mutableSetOf()) { it.asciiLowercase() }

    /** The publishable key without surrounding whitespace; `null` when none was given. */
    private val clientKey: String? = clientKey?.trim()?.takeIf { it.isNotEmpty() }

    private class State(
        val subscribers: List<Channel<ResolvedDeepLink>> = emptyList(),
        /** Links resolved while nobody was listening (typically the cold-start link). */
        val pending: List<ResolvedDeepLink> = emptyList(),
        /** The launch link while it is still in [pending]. */
        val initial: ResolvedDeepLink? = null,
        val disposed: Boolean = false,
    )

    private class Seen(val url: String, val at: ComparableTimeMark)

    private class DisposedException : IllegalStateException("Strybok has been disposed")

    private val scope = CoroutineScope(SupervisorJob() + coroutineContext)
    private val state = AtomicReference(State())
    private val lastSeen = AtomicReference<Seen?>(null)

    /** Created by the first [createLink] that needs it; never by [start] or an incoming link. */
    private val attestation = AtomicReference<AttestationSession?>(null)

    private val startJob: Deferred<Unit> = scope.async(start = CoroutineStart.LAZY) {
        // Listen first so a link arriving during start-up is not missed. Undispatched: the
        // subscription exists before the launch URL is read.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            linkSource.links
                .catch { /* A broken source ends the stream; links already received still work. */ }
                .collect { url -> if (accept(url)) scope.launch { process(url, isInitial = false) } }
        }
        val initial = try {
            linkSource.getInitialLink()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // The platform could not tell us; treat as "no launch link".
        }
        if (initial != null && accept(initial)) process(initial, isInitial = true)
    }

    /**
     * Resolved links, each delivered exactly once.
     *
     * Links that arrive while nobody collects (for example the one that cold-started the app)
     * are kept and delivered as soon as a collector attaches. The flow completes on [dispose].
     */
    public val links: Flow<ResolvedDeepLink> = flow {
        val channel = subscribe() ?: return@flow
        try {
            for (link in channel) emit(link)
        } finally {
            unsubscribe(channel)
        }
    }

    /**
     * Callback alternative to [links] (handy from Swift). [listener] is called on the SDK's
     * coroutine context: the main thread on Android and iOS. Queued links are delivered to
     * the first listener, like with [links].
     */
    public fun addListener(listener: (ResolvedDeepLink) -> Unit): Subscription {
        val channel = subscribe() ?: return Subscription { }
        val job = scope.launch { for (link in channel) listener(link) }
        // Not try/finally: a job cancelled before it ran never enters its body.
        job.invokeOnCompletion { unsubscribe(channel) }
        return Subscription { job.cancel() }
    }

    /**
     * Starts listening for links and handles the one that launched the app.
     *
     * Returns once the launch link, if any, has been resolved (or has failed), so it is safe
     * to wait for it before building the navigation graph. Calling it again is harmless.
     *
     * @throws IllegalStateException after [dispose].
     */
    @Throws(IllegalStateException::class, CancellationException::class)
    public suspend fun start() {
        checkNotDisposed()
        try {
            startJob.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // The caller was cancelled: propagate.
            // Otherwise the SDK was disposed while starting: nothing left to wait for.
        }
    }

    /**
     * The link that launched the app, if it has not been delivered through [links] or a
     * listener yet. Returns it at most once and removes it from the queue, so using both APIs
     * never navigates twice. Starts the SDK if needed.
     *
     * @throws IllegalStateException after [dispose].
     */
    @Throws(IllegalStateException::class, CancellationException::class)
    public suspend fun getInitialLink(): ResolvedDeepLink? {
        start()
        while (true) {
            val current = state.load()
            val initial = current.initial ?: return null
            val next = State(
                subscribers = current.subscribers,
                pending = current.pending.filter { it !== initial },
                initial = null,
                disposed = current.disposed,
            )
            if (state.compareAndSet(current, next)) {
                return initial.takeIf { next.pending.size < current.pending.size }
            }
        }
    }

    /**
     * Whether [url] is a Strybok link of this app: `https`, a host from [allowedHosts], no
     * credentials, default port and a single slug path.
     */
    public fun isStrybokLink(url: String): Boolean = parseLinkUrl(url, allowedHosts) != null

    /**
     * Hands a URL received from the OS to the SDK. The platform glue (`handleIntent` on
     * Android, `handleUrl` / `handleUserActivity` on iOS) calls this for you. A URL handed
     * over before [start] is the launch link.
     *
     * @return `true` when the URL is a link of this app and was accepted; `false` when it is
     *   not ours (handle it yourself) or the SDK was disposed.
     * @throws IllegalStateException when a custom [LinkSource] was configured.
     */
    public fun handleLink(url: String): Boolean {
        val source = linkSource as? PushLinkSource
            ?: throw IllegalStateException("handleLink needs the default PushLinkSource")
        if (state.load().disposed || !isStrybokLink(url)) return false
        source.push(url)
        return true
    }

    /**
     * Resolves [url] through the API without touching [links] and without reporting an app
     * open (QR scanners, tests).
     *
     * @throws StrybokException [UnsupportedLinkException] before any network call when the URL
     *   is not ours, [LinkNotFoundException] for unknown, disabled or expired links, and
     *   network / timeout / server errors otherwise.
     * @throws IllegalStateException after [dispose].
     */
    @Throws(StrybokException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun resolve(url: String): ResolvedDeepLink {
        checkNotDisposed()
        val link = parseLinkUrl(url, allowedHosts)
            ?: throw UnsupportedLinkException("Not a link for this app", url)
        val request = HttpRequest(
            method = "GET",
            // Query and fragment are not needed for the lookup and may hold private data.
            url = "$apiBaseUrl/v1/public/resolve?url=${percentEncode(link.lookup)}",
            headers = mapOf("Accept" to "application/json"),
            body = null,
            timeout = timeout,
        )
        return send(request, url, "The resolve request timed out") { response ->
            when (val status = response.statusCode) {
                200 -> parseResolveResponse(url, response.body)
                    ?: throw StrybokServerException("Malformed resolve response", url, 200)
                404 -> throw LinkNotFoundException(url)
                // Client errors will not improve.
                else -> throw StrybokServerException("Unexpected status $status", url, status)
            }
        }
    }

    /**
     * Asks the service for a short link to a screen of this app — the "Share" button.
     *
     * Needs the `clientKey` constructor argument and the "Links from the app" feature enabled
     * for the application in the dashboard, with a route pattern that covers [routePath]. It
     * runs only when you call it, is independent of [start] and of incoming links, and does not
     * use [allowedHosts].
     *
     * Creating is idempotent: the same content always returns the same active link, so
     * timeouts, network errors and 5xx answers are retried like for [resolve], and repeated
     * taps on "Share" do not pile up links. Errors are thrown to the caller and never reach
     * `onError`.
     *
     * Do not put personal data into [routePath] or [payload]: whoever receives the link can
     * read them.
     *
     * With the `attestation` option switched on, the first call (and about one call an hour
     * after that) first proves to the service that it comes from a genuine build of the app. A
     * device that cannot produce the proof still sends the request; the service then decides,
     * and answers with [StrybokAttestationRequiredException] when the owner only accepts
     * attested apps.
     *
     * @param routePath in-app route the link opens; starts with a single `/`, at most 512 characters.
     * @param payload optional JSON object attached to the link. Values may be `String`,
     *   `Boolean`, `Int`, `Long`, finite `Double`s, `null`, `List`s and `Map`s with `String`
     *   keys (a kotlinx.serialization `JsonObject` works too).
     * @param title optional title of the link preview.
     * @param description optional description of the link preview.
     * @param imageUrl optional public HTTPS URL of the link preview image.
     * @throws StrybokException [StrybokUnauthorizedException] when no client key is configured
     *   (before any network call) or the service refuses it, [StrybokValidationException] when
     *   the request is rejected by the SDK or the service, [RouteNotAllowedException],
     *   [StrybokRateLimitedException], [StrybokAttestationRequiredException] /
     *   [StrybokAttestationFailedException] when the owner asks for attestation, and
     *   [StrybokNetworkException] / [StrybokTimeoutException] / [StrybokServerException] after
     *   all retries.
     * @throws IllegalStateException after [dispose].
     */
    @Throws(StrybokException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun createLink(
        routePath: String,
        payload: Map<String, Any?>? = null,
        title: String? = null,
        description: String? = null,
        imageUrl: String? = null,
    ): CreatedLink {
        checkNotDisposed()
        val key = clientKey ?: throw StrybokUnauthorizedException("No clientKey is configured")
        // A real key is plain ASCII. Anything else can only be refused, and must not reach an
        // HTTP header (or an error message of the HTTP stack complaining about it).
        if (!key.all { it in '!'..'~' }) {
            throw StrybokUnauthorizedException("The configured clientKey is not a valid key")
        }
        if (!routePath.startsWith("/") || routePath.startsWith("//") ||
            routePath.length > MAX_ROUTE_PATH_LENGTH
        ) {
            throw preflightFailure(
                "routePath",
                "routePath must start with a single '/' and be at most $MAX_ROUTE_PATH_LENGTH characters long",
            )
        }
        // Built once: every retry sends exactly the same body.
        val body = try {
            createLinkBody(routePath, payload, title, description, imageUrl)
        } catch (e: NotJsonException) {
            throw preflightFailure("payload", e.message.orEmpty())
        }
        val session = attestor?.let { attestationSession(it, key) }
        // Lazily, here and nowhere else: the first call attests, later ones reuse the token.
        var token = session?.token()
        var response = sendCreateLink(key, token, body)
        if (session != null && token != null && response.attestationError()?.first == ATTESTATION_INVALID) {
            // The token expired, is used up or was refused: attest again and repeat the same
            // request, once. This does not count against maxRetries.
            val reason = response.attestationError()?.second
            session.discard(token)
            token = session.token() ?: throw StrybokAttestationFailedException(reason)
            response = sendCreateLink(key, token, body)
            // Refused as well: the next call starts with a new attestation.
            if (response.attestationError()?.first == ATTESTATION_INVALID) session.discard(token)
        }
        return when (val status = response.statusCode) {
            200, 201 -> parseCreateLinkResponse(response.body)
                ?: throw StrybokServerException("Malformed create-link response", null, status)
            401 -> {
                val (code, reason) = response.attestationError() ?: (null to null)
                throw when (code) {
                    ATTESTATION_REQUIRED -> StrybokAttestationRequiredException()
                    ATTESTATION_INVALID -> StrybokAttestationFailedException(reason)
                    else -> StrybokUnauthorizedException(KEY_REFUSED_MESSAGE)
                }
            }
            403 -> throw RouteNotAllowedException(routePath)
            429 -> throw StrybokRateLimitedException(parseRetryAfterSeconds(response.header("Retry-After")))
            400, 413, 422 -> {
                val (message, issues) = parseValidationError(response.body)
                throw StrybokValidationException(
                    message ?: "The service rejected the request (HTTP $status)", issues,
                )
            }
            else -> throw StrybokServerException("Unexpected status $status", null, status)
        }
    }

    /** One create-link request with the usual retries; returns the first answer below 500. */
    private suspend fun sendCreateLink(key: String, token: String?, body: String): HttpResponse {
        val request = HttpRequest(
            method = "POST",
            url = "$apiBaseUrl/v1/public/links",
            headers = buildMap {
                put("Content-Type", "application/json")
                put("Accept", "application/json")
                put("X-Strybok-Key", key)
                if (token != null) put("X-Strybok-Attestation", token)
            },
            body = body,
            timeout = timeout,
        )
        return send(request, null, "The create-link request timed out") { it }
    }

    private fun attestationSession(attestor: Attestor, key: String): AttestationSession {
        attestation.load()?.let { return it }
        val created = AttestationSession(
            attestor, AttestationApi(apiBaseUrl, key, timeout, transport), scope, timeSource, wallClock,
        )
        return if (attestation.compareAndSet(null, created)) created else attestation.load() ?: created
    }

    /** `error.code` and `error.details.reason` of a 401 that is about attestation; `null` otherwise. */
    private fun HttpResponse.attestationError(): Pair<String, String?>? {
        if (statusCode != 401) return null
        val (code, reason) = parseAttestationError(body)
        return if (code == ATTESTATION_INVALID || code == ATTESTATION_REQUIRED) code to reason else null
    }

    /** [createLink] with only a route; convenient from Swift, where default arguments are not available. */
    @Throws(StrybokException::class, IllegalStateException::class, CancellationException::class)
    public suspend fun createLink(routePath: String): CreatedLink =
        createLink(routePath, null, null, null, null)

    /**
     * Stops listening and releases resources: nothing is delivered afterwards, including a
     * link that was still resolving. The instance cannot be reused. Calling it again is harmless.
     */
    public fun dispose() {
        val previous = state.exchange(State(disposed = true))
        if (previous.disposed) return
        previous.subscribers.forEach { it.close() }
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Sends [request], retrying after a timeout, a transport failure or a 5xx answer: up to
     * [maxRetries] extra attempts, pausing [retryDelay] before the first retry and twice as
     * long before each further one. Every other answer goes to [onAnswer], whose result or
     * exception is final.
     */
    private suspend fun <T> send(
        request: HttpRequest,
        url: String?,
        timeoutMessage: String,
        onAnswer: (HttpResponse) -> T,
    ): T {
        lateinit var lastError: StrybokException
        for (attempt in 0..maxRetries) {
            if (attempt > 0) delay(retryDelay * (1 shl (attempt - 1).coerceAtMost(16)))
            checkNotDisposed()
            val response = try {
                withTimeoutOrNull(timeout) { transport.execute(request) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // We were cancelled: propagate.
                lastError = StrybokNetworkException("The request was cancelled", url, e)
                continue
            } catch (_: HttpTimeoutException) {
                lastError = StrybokTimeoutException(timeoutMessage, url)
                continue
            } catch (e: Exception) {
                lastError = StrybokNetworkException("Network error", url, e)
                continue
            }
            when {
                response == null -> lastError = StrybokTimeoutException(timeoutMessage, url)
                response.statusCode < 500 -> return onAnswer(response)
                else -> lastError = StrybokServerException(
                    "Unexpected status ${response.statusCode}", url, response.statusCode,
                )
            }
        }
        throw lastError
    }

    private fun preflightFailure(field: String, message: String): StrybokValidationException =
        StrybokValidationException(message, listOf(StrybokValidationIssue(field, message)))

    /** Cheap, synchronous checks done in arrival order: ours, and not a duplicate delivery. */
    private fun accept(url: String): Boolean =
        !state.load().disposed && isStrybokLink(url) && !isDuplicate(url)

    /**
     * The OS (or the app) can hand over the same tap twice, e.g. as the launch link and again
     * on the stream.
     */
    private fun isDuplicate(url: String): Boolean {
        val now = timeSource.markNow()
        val previous = lastSeen.exchange(Seen(url, now))
        return previous != null && previous.url == url && now - previous.at < dedupeWindow
    }

    private suspend fun process(url: String, isInitial: Boolean) {
        try {
            val link = resolve(url)
            if (deliver(link, isInitial) && reportAppOpens) scope.launch { reportAppOpen(link) }
        } catch (e: StrybokException) {
            if (!state.load().disposed) onError?.invoke(e)
        } catch (_: DisposedException) {
            // Disposed mid-flight; nothing to deliver.
        }
    }

    /** Hands [link] to the listeners, or queues it when there are none. `false` once disposed. */
    private fun deliver(link: ResolvedDeepLink, isInitial: Boolean): Boolean {
        while (true) {
            val current = state.load()
            if (current.disposed) return false
            if (current.subscribers.isEmpty()) {
                val next = State(
                    pending = current.pending + link,
                    initial = if (isInitial) link else current.initial,
                )
                if (state.compareAndSet(current, next)) return true
            } else if (current.subscribers.count { it.trySend(link).isSuccess } > 0) {
                return true
            }
            // Lost a race with a listener attaching or detaching: look again.
        }
    }

    /** Registers a listener; it starts with everything queued so far. `null` once disposed. */
    private fun subscribe(): Channel<ResolvedDeepLink>? {
        while (true) {
            val current = state.load()
            if (current.disposed) return null
            val channel = Channel<ResolvedDeepLink>(Channel.UNLIMITED)
            current.pending.forEach { channel.trySend(it) }
            if (state.compareAndSet(current, State(subscribers = current.subscribers + channel))) {
                return channel
            }
        }
    }

    private fun unsubscribe(channel: Channel<ResolvedDeepLink>) {
        var wasLast: Boolean
        while (true) {
            val current = state.load()
            if (current.disposed || channel !in current.subscribers) return
            val rest = current.subscribers.filter { it !== channel }
            wasLast = rest.isEmpty()
            if (state.compareAndSet(current, State(rest, current.pending, current.initial))) break
        }
        channel.close()
        if (!wasLast) return // Other listeners received the same links.
        // Links the last listener did not get to (it was cancelled first) stay deliverable.
        val unconsumed = generateSequence { channel.tryReceive().getOrNull() }.toList()
        if (unconsumed.isEmpty()) return
        while (true) {
            val current = state.load()
            if (current.disposed) return
            if (current.subscribers.isEmpty()) {
                val next = State(pending = unconsumed + current.pending, initial = current.initial)
                if (state.compareAndSet(current, next)) return
            } else {
                current.subscribers.forEach { subscriber -> unconsumed.forEach { subscriber.trySend(it) } }
                return
            }
        }
    }

    /**
     * Best effort and never awaited by navigation. This is a self-reported metric (an app can
     * be modified), separate from server-side fallback visits.
     */
    private suspend fun reportAppOpen(link: ResolvedDeepLink) {
        try {
            val request = HttpRequest(
                method = "POST",
                url = "$apiBaseUrl/v1/public/events",
                headers = mapOf("Content-Type" to "application/json"),
                body = appOpenEventBody(link.linkId, platformName, STRYBOK_SDK_VERSION),
                timeout = timeout,
            )
            withTimeoutOrNull(timeout) { transport.execute(request) }
        } catch (_: Exception) {
            // Analytics must never affect the app.
        }
    }

    private fun checkNotDisposed() {
        if (state.load().disposed) throw DisposedException()
    }

    private companion object {
        const val MAX_ROUTE_PATH_LENGTH = 512
        const val ATTESTATION_INVALID = "ATTESTATION_INVALID"
        const val ATTESTATION_REQUIRED = "ATTESTATION_REQUIRED"
    }
}
