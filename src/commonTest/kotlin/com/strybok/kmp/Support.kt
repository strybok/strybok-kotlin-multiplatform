package com.strybok.kmp

import com.strybok.kmp.testing.FakeLinkSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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

/** Records requests and answers resolve calls with [onResolve]. */
class FakeApi(var onResolve: (suspend (HttpRequest) -> HttpResponse)? = null) : HttpTransport {
    val resolves = mutableListOf<HttpRequest>()
    val eventRequests = mutableListOf<HttpRequest>()
    val events = mutableListOf<JsonObject>()
    var onEvent: (suspend () -> HttpResponse)? = null

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (request.url.endsWith("/v1/public/events")) {
            eventRequests += request
            events += Json.parseToJsonElement(request.body!!).jsonObject
            return onEvent?.invoke() ?: HttpResponse(202, "")
        }
        resolves += request
        return onResolve?.invoke(request) ?: ok()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
/** An SDK instance running entirely on the virtual time of this test. */
fun TestScope.sdk(
    api: FakeApi,
    source: LinkSource = FakeLinkSource(),
    errors: MutableList<StrybokException>? = null,
    maxRetries: Int = 2,
    timeout: Duration = 60.milliseconds,
    retryDelay: Duration = 1.milliseconds,
    reportAppOpens: Boolean = true,
    apiBaseUrl: String = API,
    allowedHosts: Set<String> = setOf(HOST),
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
)

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
