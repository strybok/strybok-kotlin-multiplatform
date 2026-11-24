package com.strybok.kmp.internal

import com.strybok.kmp.HttpTransport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val platformName: String = "ios"

internal actual fun platformHttpTransport(): HttpTransport = NSUrlSessionTransport

internal actual fun platformCoroutineContext(): CoroutineContext = Dispatchers.Main
