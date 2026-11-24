package com.strybok.kmp.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val platformName: String = "unknown"

/** A plain JVM has no main thread dispatcher. */
internal actual fun platformCoroutineContext(): CoroutineContext = Dispatchers.Default
