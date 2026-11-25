package com.strybok.kmp.internal

import com.strybok.kmp.PlatformContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/** A plain JVM has no app store and no installation: the `deferredLinks` option is ignored. */
internal actual fun platformDeferred(context: PlatformContext?): DeferredPlatform? = null

internal actual val deferredNeedsContext: String? = null

internal actual fun platformBackgroundContext(): CoroutineContext = Dispatchers.IO
