package com.strybok.kmp.internal

import com.strybok.kmp.HttpTransport
import com.strybok.kmp.IntegrityTokenProvider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual val platformName: String = "ios"

internal actual fun platformHttpTransport(): HttpTransport = NSUrlSessionTransport

internal actual fun platformCoroutineContext(): CoroutineContext = Dispatchers.Main

/** App Attest. Nothing is touched here: the service and the stored key id are only used by `createLink`. */
internal actual fun platformAttestor(integrityTokenProvider: IntegrityTokenProvider?): Attestor? =
    AppAttestAttestor(DeviceCheckAppAttestService, FileKeyIdStore.shared)
