package com.strybok.kmp

import com.strybok.kmp.internal.DeferredKind
import com.strybok.kmp.internal.DeferredPlatform
import com.strybok.kmp.internal.DeferredSetup
import com.strybok.kmp.internal.KeyIdStore
import com.strybok.kmp.testing.FakeLinkSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** A click id as the service makes them: `dc_` and 32 base62 characters. */
const val CLICK_ID = "dc_0123456789abcdefghijABCDEFGHIJ01"

/** The link an iOS visitor copies on the page before the App Store. */
const val COPIED = "https://$HOST/promo42?strybok_click=$CLICK_ID"

/** What the redeem endpoint answers for a redeemable id; with [matched] what the match endpoint answers. */
fun redeemed(
    url: String = "https://$HOST/promo42",
    route: String = "/promo/42",
    matched: Boolean = false,
): HttpResponse = HttpResponse(
    200,
    """{"linkId":"link_7","appId":"app_1","routePath":"$route","payload":{"code":"SPRING"},""" +
        """"campaign":{"source":"instagram"},"url":"$url","deferred":true""" +
        (if (matched) ""","matched":"probabilistic"}""" else "}"),
)

/** The two stored values, in memory. One instance outlives several "launches" of a test. */
internal class RecordingStore : KeyIdStore {
    val values = mutableMapOf<String, String>()

    /** Every write, in order, as `name=value`. */
    val writes = mutableListOf<String>()

    /** Names whose writes are lost, like on a full or broken disk. */
    val broken = mutableSetOf<String>()

    /** Runs right after a value was stored: the place where a test "kills the process". */
    var afterSet: ((name: String) -> Unit)? = null

    val attempts: Int get() = values["attempts"]?.toInt() ?: 0
    val done: Boolean get() = "done" in values

    override fun get(name: String): String? = values[name]

    override fun set(name: String, value: String) {
        if (name in broken) return
        values[name] = value
        writes += "$name=$value"
        afterSet?.invoke(name)
    }

    override fun remove(name: String) {
        values.remove(name)
    }
}

internal class FakeDeferredPlatform(
    override val kind: DeferredKind,
    override val store: RecordingStore = RecordingStore(),
) : DeferredPlatform {
    var identifier: String? = if (kind == DeferredKind.IOS) "com.example.App" else "com.example.app"
    var updated = false
    var family: String? = "iphone"
    var language: String? = "en-GB"
    var pasteboardHasUrl = false
    var pasteboardChecks = 0
    var claims = true

    override fun appIdentifier(): String? = identifier

    override fun updatedSinceInstall(): Boolean = updated

    override fun deviceFamily(): String? = family

    override fun preferredLanguage(): String? = language

    override suspend fun pasteboardMayContainUrl(): Boolean {
        pasteboardChecks += 1
        return pasteboardHasUrl
    }

    override fun claimProcess(): Boolean = claims
}

/** Answers with [answers] in turn (the last one repeats) and records how often it was asked. */
class FakeSource(private vararg val answers: InstallReferrerResult) : InstallReferrerSource {
    var calls = 0
    var onAsked: (suspend () -> Unit)? = null

    override suspend fun installReferrer(): InstallReferrerResult {
        val call = calls++
        onAsked?.invoke()
        return answers[call.coerceAtMost(answers.size - 1)]
    }
}

fun referrer(value: String = "strybok_click=$CLICK_ID"): InstallReferrerResult = InstallReferrerResult.Referrer(value)

/** One launch of an app with deferred links, on the virtual time of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.launchApp(
    api: FakeApi,
    platform: DeferredPlatform?,
    vararg sources: InstallReferrerSource,
    deferredLinks: Boolean = true,
    matching: Boolean = false,
    linkSource: LinkSource = FakeLinkSource(),
    errors: MutableList<StrybokException>? = null,
    maxRetries: Int = 2,
    sourceTimeout: Duration = 10.seconds,
    parameterLinks: ParameterLinks? = null,
): Strybok = sdk(
    api,
    source = linkSource,
    errors = errors,
    maxRetries = maxRetries,
    deferred = DeferredSetup(
        enabled = deferredLinks,
        sources = sources.toList(),
        allowProbabilisticMatching = matching,
        platform = platform,
        background = StandardTestDispatcher(testScheduler),
        sourceTimeout = sourceTimeout,
    ),
    parameterLinks = parameterLinks,
)
