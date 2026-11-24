package com.strybok.kmp.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val platformName: String = "android"

internal actual fun platformCoroutineContext(): CoroutineContext = Dispatchers.Main
