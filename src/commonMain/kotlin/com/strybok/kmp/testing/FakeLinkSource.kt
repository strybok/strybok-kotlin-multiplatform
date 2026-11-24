package com.strybok.kmp.testing

import com.strybok.kmp.LinkSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * A [LinkSource] for tests: simulates the launch URL and URLs arriving while the app runs.
 *
 * Like a real platform, it does not buffer: a URL emitted while the SDK is not listening
 * (before `start()` or after `dispose()`) is lost.
 */
public class FakeLinkSource(
    private val initialLink: String? = null,
    /** Also report [initialLink] on [links], as some platforms do. */
    private val replayInitialLinkOnStream: Boolean = false,
) : LinkSource {
    private val flow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Whether the SDK is currently listening. */
    public val hasListener: Boolean get() = flow.subscriptionCount.value > 0

    /** Simulates the OS handing [url] to the running app. */
    public fun emit(url: String) {
        flow.tryEmit(url)
    }

    override suspend fun getInitialLink(): String? = initialLink

    override val links: Flow<String> = flow.onSubscription {
        if (replayInitialLinkOnStream && initialLink != null) emit(initialLink)
    }
}
