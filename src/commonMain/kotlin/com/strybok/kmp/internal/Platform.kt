package com.strybok.kmp.internal

import com.strybok.kmp.HttpTransport
import kotlin.coroutines.CoroutineContext

/** `ios`, `android` or `unknown`; reported with `app_open` events. */
internal expect val platformName: String

/** HTTP transport used when the app does not provide one. */
internal expect fun platformHttpTransport(): HttpTransport

/** Where the SDK runs and calls back: the main thread on Android and iOS. */
internal expect fun platformCoroutineContext(): CoroutineContext
