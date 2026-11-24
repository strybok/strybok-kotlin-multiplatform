package com.strybok.kmp.internal

import com.strybok.kmp.IntegrityTokenProvider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val platformName: String = "unknown"

/** A plain JVM has no main thread dispatcher. */
internal actual fun platformCoroutineContext(): CoroutineContext = Dispatchers.Default

/** A plain JVM has neither App Attest nor Play Integrity: it cannot attest. */
internal actual fun platformAttestor(integrityTokenProvider: IntegrityTokenProvider?): Attestor? = null
