package com.strybok.kmp.internal

import com.strybok.kmp.HttpTransport
import com.strybok.kmp.IntegrityTokenProvider
import kotlin.coroutines.CoroutineContext

/** `ios`, `android` or `unknown`; reported with `app_open` events. */
internal expect val platformName: String

/** HTTP transport used when the app does not provide one. */
internal expect fun platformHttpTransport(): HttpTransport

/** Where the SDK runs and calls back: the main thread on Android and iOS. */
internal expect fun platformCoroutineContext(): CoroutineContext

/**
 * How this platform proves that a `createLink` request comes from a genuine app: App Attest on
 * iOS, Play Integrity through [integrityTokenProvider] on Android. `null` when it cannot — the
 * JVM, or Android without a provider. Must not do any work: it is called from the constructor.
 */
internal expect fun platformAttestor(integrityTokenProvider: IntegrityTokenProvider?): Attestor?
