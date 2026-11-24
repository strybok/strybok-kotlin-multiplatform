package com.strybok.kmp.internal

import com.strybok.kmp.IntegrityTokenProvider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val platformName: String = "android"

internal actual fun platformCoroutineContext(): CoroutineContext = Dispatchers.Main

/** Play Integrity, when the app supplied a provider; an app without Google Play does not. */
internal actual fun platformAttestor(integrityTokenProvider: IntegrityTokenProvider?): Attestor? =
    integrityTokenProvider?.let(::IntegrityAttestor)
