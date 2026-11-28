package com.strybok.kmp.internal

import com.strybok.kmp.DeferredLinkStatus
import com.strybok.kmp.HttpResponse
import com.strybok.kmp.InstallReferrerResult
import com.strybok.kmp.InstallReferrerSource
import com.strybok.kmp.PastedLinkResult
import com.strybok.kmp.PlatformContext
import com.strybok.kmp.ResolvedDeepLink
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Thrown when the instance is used, or was still working, after `dispose()`. */
internal class DisposedException : IllegalStateException("Strybok has been disposed")

/** Which of the two contract sections a platform implements. */
internal enum class DeferredKind {
    /** Android: the store's install referrer carries a click id. */
    INSTALL_REFERRER,

    /** iOS: a pasted link, and optionally probabilistic matching. */
    IOS,
}

/**
 * What deferred links need from the device. An interface so that every flow runs in the common
 * tests, on every target, without a device.
 */
internal interface DeferredPlatform {
    val kind: DeferredKind

    /** Where `attempts` and `done` are kept, per installation. Nothing else is ever stored. */
    val store: KeyIdStore

    /** The application ID on Android, the bundle identifier on iOS; `null` when there is none. */
    fun appIdentifier(): String?

    /** Android: `PackageInfo.firstInstallTime != lastUpdateTime`. */
    fun updatedSinceInstall(): Boolean = false

    /** iOS: `iphone`, `ipad` or `ipod`; `null` for any other kind of device. */
    fun deviceFamily(): String? = null

    /** iOS: the first preferred language, e.g. `en-GB`; `null` when there is none. */
    fun preferredLanguage(): String? = null

    /**
     * iOS: whether the pasteboard probably holds a web URL, found out **without reading it**
     * (`UIPasteboard.detectPatterns`).
     */
    suspend fun pasteboardMayContainUrl(): Boolean = false

    /** `true` for the first caller in this process, `false` for every later one. */
    fun claimProcess(): Boolean
}

/** Everything the public constructor decides about deferred links. */
internal class DeferredSetup(
    val enabled: Boolean = false,
    val sources: List<InstallReferrerSource> = emptyList(),
    val allowProbabilisticMatching: Boolean = false,
    /** `null` on a target without deferred links, and on Android without a context. */
    val platform: DeferredPlatform? = null,
    /** Where storage is read and written and sources are asked: never the main thread. */
    val background: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    /** How long one install referrer source may take before it is abandoned. */
    val sourceTimeout: Duration = 10.seconds,
)

/** The setup of the public constructor. Does no work: nothing is read before the check runs. */
internal fun deferredSetup(
    deferredLinks: Boolean,
    installReferrerSources: List<InstallReferrerSource>,
    allowProbabilisticMatching: Boolean,
    platformContext: PlatformContext?,
): DeferredSetup {
    val platform = platformDeferred(platformContext)
    require(!deferredLinks || platform != null || deferredNeedsContext == null) { deferredNeedsContext.orEmpty() }
    return DeferredSetup(
        enabled = deferredLinks,
        sources = installReferrerSources.toList(),
        allowProbabilisticMatching = allowProbabilisticMatching,
        platform = platform,
        background = platformBackgroundContext(),
    )
}

/** The two stored values, on top of the small string store the SDK already has. */
internal class DeferredState(private val store: KeyIdStore) {
    fun attempts(): Int {
        val text = read(ATTEMPTS) ?: return 0
        // Unreadable content counts as "all attempts used": never more checks than allowed.
        return text.toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
    }

    /** `false` when the value did not reach the store; the check then stops. */
    fun storeAttempts(value: Int): Boolean = write(ATTEMPTS, value.toString())

    fun isDone(): Boolean = read(DONE) != null

    fun storeDone(): Boolean = write(DONE, "1")

    private fun read(name: String): String? = try {
        store.get(name)
    } catch (_: Exception) {
        null
    }

    /** Stores do not report failures, so the value is read back. */
    private fun write(name: String, value: String): Boolean = try {
        store.set(name, value)
        store.get(name) == value
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val ATTEMPTS = "attempts"
        const val DONE = "done"
    }
}

private const val MAX_ATTEMPTS = 3
private const val REDEEM_PATH = "/v1/public/deferred-links/redeem"
private const val MATCH_PATH = "/v1/public/deferred-links/match"

/**
 * Deferred links as the SDK contract specifies them: the install referrer check on Android
 * ("Deferred links (optional, Android)") and the two flows of "Deferred links on iOS".
 *
 * At most one link per installation, and never two. Nothing here reports an error, and neither
 * the referrer, the click id nor a pasted string is logged or stored.
 */
internal class DeferredLinks(
    private val setup: DeferredSetup,
    private val scope: CoroutineScope,
    private val allowedHosts: Set<String>,
    private val sdkVersion: String,
    /** Sends a request with the usual timeout and retries; `null` when no answer below 500 came. */
    private val post: suspend (path: String, body: String) -> HttpResponse?,
    /** Whether a link of this app was accepted since the process started. */
    private val normalLinkAccepted: () -> Boolean,
    /** Hands a link to the normal pipeline; `false` once the SDK is disposed. */
    private val deliver: (ResolvedDeepLink) -> Boolean,
) {
    private val platform: DeferredPlatform? = setup.platform
    private val state: DeferredState? = platform?.let { DeferredState(it.store) }

    private val enabled = AtomicBoolean(setup.enabled && platform != null)
    private val started = AtomicBoolean(false)
    private val launched = AtomicBoolean(false)

    /** `done` was stored (or is about to be) by this process: neither flow does anything again. */
    private val handled = AtomicBoolean(false)

    /** iOS: matching waits until the app reports what the person did with the paste button. */
    private val awaitingPaste = AtomicBoolean(false)

    /** iOS: the status a pasted link ended with, when it ended the story. */
    private val pasteOutcome = AtomicReference<DeferredLinkStatus?>(null)
    private val pasteLock = Mutex()

    private val mutableStatus = MutableStateFlow(
        if (enabled.load()) DeferredLinkStatus.CHECKING else DeferredLinkStatus.DISABLED,
    )
    val status: StateFlow<DeferredLinkStatus> = mutableStatus.asStateFlow()

    /** Whether the target can do anything at all; `false` leaves every call a no-op. */
    val isSupported: Boolean get() = platform != null

    /** `start` has completed everything it does without deferred links. */
    fun onStarted() {
        started.store(true)
        launchWhenReady()
    }

    /** Switches the option on after construction; the check then runs once. */
    fun enable() {
        if (platform == null || !enabled.compareAndSet(false, true)) return
        mutableStatus.compareAndSet(DeferredLinkStatus.DISABLED, DeferredLinkStatus.CHECKING)
        launchWhenReady()
    }

    /** A coroutine of its own: `start`, the launch link and `createLink` never wait for it. */
    private fun launchWhenReady() {
        val platform = platform ?: return
        val state = state ?: return
        if (!enabled.load() || !started.load() || !launched.compareAndSet(false, true)) return
        scope.launch {
            finish {
                when (platform.kind) {
                    DeferredKind.INSTALL_REFERRER -> checkInstallReferrer(platform, state)
                    DeferredKind.IOS -> checkIos(platform, state)
                }
            }
        }
    }

    /** Runs one flow and publishes the status it ended with. Never throws, except for cancellation. */
    private suspend fun finish(flow: suspend () -> DeferredLinkStatus?) {
        val result = try {
            flow()
        } catch (e: CancellationException) {
            throw e
        } catch (_: DisposedException) {
            return // Disposed mid-flight; `done` is only stored when the answer had arrived.
        } catch (_: Exception) {
            DeferredLinkStatus.PENDING // Nothing was delivered; a later launch tries again.
        }
        if (result != null) mutableStatus.value = result
    }

    // --- Android: the install referrer ---------------------------------------------------------

    private suspend fun checkInstallReferrer(platform: DeferredPlatform, state: DeferredState): DeferredLinkStatus? {
        if (!platform.claimProcess()) {
            // Another instance in this process runs (or ran) the check; one check per process.
            return if (io { state.isDone() }) DeferredLinkStatus.NOT_FIRST_LAUNCH else DeferredLinkStatus.PENDING
        }
        if (io { state.isDone() }) return DeferredLinkStatus.NOT_FIRST_LAUNCH
        if (io { platform.updatedSinceInstall() }) {
            // An update to a version with this feature must not ask about an old referrer.
            markDone(state)
            return DeferredLinkStatus.NOT_FIRST_LAUNCH
        }
        countAttempt(state)?.let { return it }

        var clickId: String? = null
        var unavailableForNow = false
        var gotReferrer = false
        for (source in setup.sources) {
            when (val answer = ask(source)) {
                is InstallReferrerResult.Referrer -> {
                    gotReferrer = true
                    clickId = findClickId(answer.value)
                }
                InstallReferrerResult.UnavailableForNow -> unavailableForNow = true
                InstallReferrerResult.Unavailable -> Unit
            }
            if (clickId != null) break
        }
        if (clickId == null) {
            if (unavailableForNow) return DeferredLinkStatus.PENDING
            markDone(state)
            return if (gotReferrer) DeferredLinkStatus.NO_REFERRER else DeferredLinkStatus.UNAVAILABLE
        }

        if (normalLinkAccepted()) {
            markDone(state)
            return DeferredLinkStatus.SUPERSEDED // One launch, one navigation; no request.
        }
        val packageName = platform.appIdentifier()
        if (packageName == null) {
            markDone(state)
            return DeferredLinkStatus.UNAVAILABLE
        }
        val body = redeemBody("android", clickId, "packageName", packageName, sdkVersion)
        val response = post(REDEEM_PATH, body) ?: return DeferredLinkStatus.PENDING
        return conclude(state, response, probabilistic = false)
    }

    /**
     * One source, away from the main thread, for at most [DeferredSetup.sourceTimeout]. A source
     * that takes longer is cancelled and abandoned; one that throws counts as "unavailable for now".
     */
    private suspend fun ask(source: InstallReferrerSource): InstallReferrerResult {
        val call = scope.async(setup.background) { source.installReferrer() }
        return try {
            withTimeoutOrNull(setup.sourceTimeout) { call.await() } ?: InstallReferrerResult.UnavailableForNow
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive() // We were cancelled: propagate.
            InstallReferrerResult.UnavailableForNow
        } finally {
            call.cancel()
        }
    }

    // --- iOS: the pasted link (flow A) ----------------------------------------------------------

    suspend fun redeemPastedLink(text: String): PastedLinkResult {
        val platform = platform
        val state = state
        if (platform == null || state == null || platform.kind != DeferredKind.IOS || !enabled.load()) {
            return PastedLinkResult.DISABLED
        }
        // One at a time: a second tap on Paste waits for the first.
        return pasteLock.withLock {
            if (handled.load() || io { state.isDone() }) return@withLock PastedLinkResult.ALREADY_HANDLED
            val clickId = pastedClickId(text, allowedHosts)
            if (clickId == null) {
                // The string is dropped here. The person did use the paste button: stop waiting.
                startMatchingAfterPaste()
                return@withLock PastedLinkResult.NOT_OURS
            }
            val bundleId = platform.appIdentifier() ?: return@withLock PastedLinkResult.FAILED
            // Only the click id is sent, never the pasted URL.
            val response = post(REDEEM_PATH, redeemBody("ios", clickId, "bundleId", bundleId, sdkVersion))
                ?: return@withLock PastedLinkResult.FAILED
            when (val status = response.statusCode) {
                200 -> {
                    val link = parseDeferredResponse(response.body, allowedHosts, probabilistic = false)
                    when {
                        // Matching delivered a link while this request was in flight.
                        !handled.compareAndSet(false, true) -> PastedLinkResult.ALREADY_HANDLED
                        link == null -> {
                            io { state.storeDone() }
                            pasteEnded(DeferredLinkStatus.FAILED, PastedLinkResult.FAILED)
                        }
                        // Before delivery, as everywhere. Pasting was the person's own action,
                        // so a normal link earlier in this launch does not stop it.
                        !io { state.storeDone() } -> PastedLinkResult.FAILED
                        deliver(link) -> pasteEnded(DeferredLinkStatus.DELIVERED, PastedLinkResult.DELIVERED)
                        else -> PastedLinkResult.FAILED // Disposed.
                    }
                }
                429 -> PastedLinkResult.FAILED
                else -> if (status in 400..499) {
                    if (handled.compareAndSet(false, true)) {
                        io { state.storeDone() }
                        pasteEnded(DeferredLinkStatus.NOT_FOUND, PastedLinkResult.NOT_FOUND)
                    }
                    PastedLinkResult.NOT_FOUND
                } else {
                    PastedLinkResult.FAILED // Not an answer of the service; Paste can be tapped again.
                }
            }
        }
    }

    private fun pasteEnded(status: DeferredLinkStatus, result: PastedLinkResult): PastedLinkResult {
        pasteOutcome.store(status)
        mutableStatus.value = status
        return result
    }

    suspend fun pasteboardMayContainLink(): Boolean {
        val platform = platform
        val state = state
        if (platform == null || state == null || platform.kind != DeferredKind.IOS || !enabled.load()) return false
        if (handled.load() || io { state.isDone() }) return false
        return mayContainUrl(platform)
    }

    private suspend fun mayContainUrl(platform: DeferredPlatform): Boolean = try {
        platform.pasteboardMayContainUrl()
    } catch (_: Throwable) {
        currentCoroutineContext().ensureActive()
        false
    }

    /** The person moved on without pasting. */
    fun skipPastedLink() {
        if (platform?.kind == DeferredKind.IOS && enabled.load()) startMatchingAfterPaste()
    }

    // --- iOS: probabilistic matching (flow B) ---------------------------------------------------

    private suspend fun checkIos(platform: DeferredPlatform, state: DeferredState): DeferredLinkStatus? {
        // A link pasted before `start` already ended the story in this launch: its result stands.
        if (handled.load()) return pasteOutcome.load()
        if (io { state.isDone() }) {
            handled.store(true)
            return DeferredLinkStatus.NOT_FIRST_LAUNCH
        }
        // Flow A alone: there is nothing to do until the app hands over a pasted string.
        if (!setup.allowProbabilisticMatching) return DeferredLinkStatus.AWAITING_PASTE
        if (!platform.claimProcess()) return DeferredLinkStatus.PENDING // One match per process.
        // Clipboard first: an exact answer may be one tap away, so do not guess yet.
        if (mayContainUrl(platform)) {
            awaitingPaste.store(true)
            return DeferredLinkStatus.AWAITING_PASTE
        }
        return match(platform, state)
    }

    private fun startMatchingAfterPaste() {
        val platform = platform ?: return
        val state = state ?: return
        if (handled.load() || !awaitingPaste.compareAndSet(true, false)) return
        mutableStatus.value = DeferredLinkStatus.CHECKING
        scope.launch { finish { match(platform, state) } }
    }

    private suspend fun match(platform: DeferredPlatform, state: DeferredState): DeferredLinkStatus? {
        // A pasted link was handled while matching waited: its result stands.
        if (handled.load() || io { state.isDone() }) return pasteOutcome.load() ?: DeferredLinkStatus.NOT_FIRST_LAUNCH
        countAttempt(state)?.let { return it }
        if (normalLinkAccepted()) {
            markDone(state)
            return DeferredLinkStatus.SUPERSEDED
        }
        val bundleId = platform.appIdentifier()
        val deviceFamily = platform.deviceFamily()
        val locale = platform.preferredLanguage()
        if (bundleId == null || deviceFamily == null || locale == null) {
            markDone(state) // A Mac, a TV, a headset: there is nothing the service could match.
            return DeferredLinkStatus.UNAVAILABLE
        }
        val response = post(MATCH_PATH, matchBody(bundleId, deviceFamily, locale, sdkVersion))
            ?: return DeferredLinkStatus.PENDING
        return conclude(state, response, probabilistic = true)
    }

    // --- shared ----------------------------------------------------------------------------------

    /** "Three attempts, then failed" and "count this one first". `null` when the check may go on. */
    private suspend fun countAttempt(state: DeferredState): DeferredLinkStatus? {
        val attempts = io { state.attempts() }
        if (attempts >= MAX_ATTEMPTS) {
            markDone(state)
            return DeferredLinkStatus.FAILED
        }
        // Before anything else happens: a launch that dies from here on has used an attempt.
        return if (io { state.storeAttempts(attempts + 1) }) null else DeferredLinkStatus.PENDING
    }

    /** What an answer of the redeem or match endpoint means for an automatic check. */
    private suspend fun conclude(state: DeferredState, response: HttpResponse, probabilistic: Boolean): DeferredLinkStatus? =
        when (val status = response.statusCode) {
            200 -> {
                val link = parseDeferredResponse(response.body, allowedHosts, probabilistic)
                when {
                    // A pasted link was handled while this request was in flight: nothing more happens.
                    !handled.compareAndSet(false, true) -> pasteOutcome.load()
                    link == null -> {
                        io { state.storeDone() }
                        DeferredLinkStatus.FAILED
                    }
                    // Before delivery: a crash after the app received the link cannot deliver it again.
                    !io { state.storeDone() } -> DeferredLinkStatus.PENDING
                    // A normal link wins, also one that arrived while the request was in flight.
                    normalLinkAccepted() -> DeferredLinkStatus.SUPERSEDED
                    deliver(link) -> DeferredLinkStatus.DELIVERED
                    else -> null // Disposed.
                }
            }
            429 -> DeferredLinkStatus.PENDING // Not retried in this launch.
            else -> when {
                status !in 400..499 -> DeferredLinkStatus.PENDING // Not an answer of the service at all.
                !handled.compareAndSet(false, true) -> pasteOutcome.load()
                else -> {
                    io { state.storeDone() }
                    DeferredLinkStatus.NOT_FOUND
                }
            }
        }

    private suspend fun markDone(state: DeferredState) {
        handled.store(true)
        io { state.storeDone() }
    }

    /** Storage and platform facts, off the main thread; not interrupted by `dispose`. */
    private suspend fun <T> io(block: () -> T): T = withContext(NonCancellable + setup.background) { block() }
}

// ---------------------------------------------------------------------------------------------

/** The key our value has in a store's install referrer and in the query of a copied link. */
private const val CLICK_KEY = "strybok_click"
private const val CLICK_ID_PREFIX = "dc_"
private const val CLICK_ID_LENGTH = 35

/** `^dc_[0-9A-Za-z]{32}$`. */
internal fun isClickId(value: String): Boolean =
    value.length == CLICK_ID_LENGTH && value.startsWith(CLICK_ID_PREFIX) &&
        (CLICK_ID_PREFIX.length until CLICK_ID_LENGTH).all { index ->
            val char = value[index]
            char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z'
        }

/**
 * The click id inside a store's install referrer (SDK contract, "Finding the click id in a
 * referrer"), or `null`. Everything else in the string is ignored: not parsed further, not
 * stored, not sent, not logged. Never throws.
 */
internal fun findClickId(referrer: String): String? {
    val direct = clickIdPart(referrer)
    if (direct != null) return direct.takeIf(::isClickId)
    // A store may hand the value back still percent-encoded: decode the whole string, once.
    if (!referrer.contains("$CLICK_KEY%3D", ignoreCase = true)) return null
    return clickIdPart(percentDecode(referrer))?.takeIf(::isClickId)
}

/** The value of the first `strybok_click` pair, or `null` when no part has exactly that key. */
private fun clickIdPart(referrer: String): String? {
    for (part in referrer.split('&')) {
        val equals = part.indexOf('=')
        val key = if (equals < 0) part else part.substring(0, equals)
        if (key == CLICK_KEY) return if (equals < 0) "" else part.substring(equals + 1)
    }
    return null
}

/** Decodes `%XX` escapes as UTF-8. A malformed escape is kept as it is; `+` is not a space. */
private fun percentDecode(value: String): String {
    val raw = value.encodeToByteArray()
    val out = ByteArray(raw.size)
    var size = 0
    var index = 0
    while (index < raw.size) {
        val byte = raw[index]
        if (byte == '%'.code.toByte() && index + 2 < raw.size) {
            val high = hexDigit(raw[index + 1])
            val low = hexDigit(raw[index + 2])
            if (high >= 0 && low >= 0) {
                out[size++] = (high * 16 + low).toByte()
                index += 3
                continue
            }
        }
        out[size++] = byte
        index += 1
    }
    return out.decodeToString(0, size) // Bytes that are not UTF-8 become replacement characters.
}

private fun hexDigit(byte: Byte): Int = when (val char = byte.toInt().toChar()) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

/**
 * The click id of a link the person copied on the page before the App Store (SDK contract,
 * "Flow A"): [text], trimmed, must be a link of this app whose first `strybok_click` parameter
 * is a click id. `null` for anything else — the person may have had anything on their clipboard.
 * Only stored links are offered for pasting, so the slug rule applies even with `parameterLinks`.
 */
internal fun pastedClickId(text: String, allowedHosts: Set<String>): String? {
    val link = parseLinkUrl(text.trim(), allowedHosts) ?: return null
    return firstQueryValue(link.rawQuery, CLICK_KEY)?.takeIf(::isClickId)
}

/** Body of `POST /v1/public/deferred-links/redeem`. The API rejects any other field. */
internal fun redeemBody(
    platform: String,
    clickId: String,
    identifierName: String,
    identifier: String,
    sdkVersion: String,
): String = buildJsonObject {
    put("platform", platform)
    put("clickId", clickId)
    put(identifierName, identifier)
    put("sdkVersion", sdkVersion.take(32))
}.toString()

/**
 * Body of `POST /v1/public/deferred-links/match`. **Nothing else may ever be added**: no
 * identifier, no device model or name, no screen size, no OS version, no time zone.
 */
internal fun matchBody(bundleId: String, deviceFamily: String, locale: String, sdkVersion: String): String =
    buildJsonObject {
        put("platform", "ios")
        put("bundleId", bundleId)
        put("deviceFamily", deviceFamily)
        put("locale", locale)
        put("sdkVersion", sdkVersion.take(32))
    }.toString()
