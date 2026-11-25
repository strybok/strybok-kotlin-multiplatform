package com.strybok.kmp

import com.strybok.kmp.internal.Attestor
import com.strybok.kmp.internal.DeferredSetup
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

const val HOST = "my-app.strybok.link"
const val API = "https://api.strybok.com"
const val INVITE = "https://$HOST/invite777"

fun ok(route: String = "/invite/abc123", linkId: String = "link_1"): HttpResponse = HttpResponse(
    200,
    """{"linkId":"$linkId","appId":"app_1","routePath":"$route","payload":{"invitationId":"abc123"}}""",
)

const val CLIENT_KEY = "pk_0123456789abcdefghijABCDEFGHIJ01"

fun created(status: Int = 201, slug: String = "Ab3dE9x"): HttpResponse = HttpResponse(
    status,
    """{"linkId":"link_9","url":"https://$HOST/$slug","slug":"$slug","routePath":"/product/42"}""",
)

/** Records requests and answers resolve calls with [onResolve] and create-link calls with [onCreate]. */
class FakeApi(var onResolve: (suspend (HttpRequest) -> HttpResponse)? = null) : HttpTransport {
    val resolves = mutableListOf<HttpRequest>()
    val eventRequests = mutableListOf<HttpRequest>()
    val events = mutableListOf<JsonObject>()
    var onEvent: (suspend () -> HttpResponse)? = null
    val creates = mutableListOf<HttpRequest>()
    var onCreate: (suspend (HttpRequest) -> HttpResponse)? = null
    val redeems = mutableListOf<HttpRequest>()
    var onRedeem: (suspend (HttpRequest) -> HttpResponse)? = null
    val matches = mutableListOf<HttpRequest>()
    var onMatch: (suspend (HttpRequest) -> HttpResponse)? = null

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (request.url.endsWith("/v1/public/deferred-links/redeem")) {
            redeems += request
            return onRedeem?.invoke(request) ?: redeemed()
        }
        if (request.url.endsWith("/v1/public/deferred-links/match")) {
            matches += request
            return onMatch?.invoke(request) ?: redeemed(matched = true)
        }
        if (request.url.endsWith("/v1/public/events")) {
            eventRequests += request
            events += Json.parseToJsonElement(request.body!!).jsonObject
            return onEvent?.invoke() ?: HttpResponse(202, "")
        }
        if (request.url.endsWith("/v1/public/links")) {
            creates += request
            return onCreate?.invoke(request) ?: created()
        }
        resolves += request
        return onResolve?.invoke(request) ?: ok()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
/** An SDK instance running entirely on the virtual time of this test. */
internal fun TestScope.sdk(
    api: HttpTransport,
    source: LinkSource = FakeLinkSource(),
    errors: MutableList<StrybokException>? = null,
    maxRetries: Int = 2,
    timeout: Duration = 60.milliseconds,
    retryDelay: Duration = 1.milliseconds,
    reportAppOpens: Boolean = true,
    apiBaseUrl: String = API,
    allowedHosts: Set<String> = setOf(HOST),
    clientKey: String? = CLIENT_KEY,
    attestor: Attestor? = null,
    wallClock: () -> Instant = { wallClockOf(this) },
    deferred: DeferredSetup = DeferredSetup(),
): Strybok = Strybok(
    apiBaseUrl = apiBaseUrl,
    allowedHosts = allowedHosts,
    timeout = timeout,
    maxRetries = maxRetries,
    retryDelay = retryDelay,
    dedupeWindow = 2.seconds,
    reportAppOpens = reportAppOpens,
    onError = errors?.let { list -> { error: StrybokException -> list += error } },
    linkSource = source,
    transport = api,
    coroutineContext = StandardTestDispatcher(testScheduler),
    timeSource = testScheduler.timeSource,
    clientKey = clientKey,
    attestor = attestor,
    wallClock = wallClock,
    deferred = deferred,
)

/** 2026-11-24T10:00:00Z, the wall-clock time at which every test starts. */
val T0: Instant = Instant.parse("2026-11-24T10:00:00Z")

@OptIn(ExperimentalCoroutinesApi::class)
/** A wall clock that moves with the virtual time of the test. */
fun wallClockOf(scope: TestScope): Instant = T0 + scope.testScheduler.currentTime.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
/** Collects [Strybok.links] into a list; the collector is attached when this returns. */
fun TestScope.collectLinks(sdk: Strybok, onDone: () -> Unit = {}): List<ResolvedDeepLink> {
    val received = mutableListOf<ResolvedDeepLink>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        sdk.links.collect { received += it }
        onDone()
    }
    return received
}
