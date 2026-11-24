package com.strybok.kmp

import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Where incoming link URLs come from. The default is [PushLinkSource], fed by the platform
 * glue (`handleIntent` on Android, `handleUrl` / `handleUserActivity` on iOS); tests and custom
 * integrations can provide their own.
 */
public interface LinkSource {
    /** The URL that launched the app (cold start), or `null`. */
    public suspend fun getInitialLink(): String?

    /**
     * URLs received while the app is running. Implementations may also replay the launch URL
     * here; the SDK de-duplicates it.
     */
    public val links: Flow<String>
}

/**
 * A [LinkSource] the app pushes URLs into.
 *
 * A URL pushed before the SDK was started is the launch URL. Everything else is delivered
 * through [links]; nothing is lost when URLs are pushed before the SDK listens.
 * [links] supports a single collector (the SDK).
 */
public class PushLinkSource : LinkSource {
    private sealed interface Launch {
        data object Waiting : Launch
        class Held(val url: String) : Launch
        data object Taken : Launch
    }

    private val launch = AtomicReference<Launch>(Launch.Waiting)
    private val channel = Channel<String>(Channel.UNLIMITED)

    /** Hands a URL to the SDK. Safe to call from any thread. */
    public fun push(url: String) {
        while (true) {
            when (launch.load()) {
                Launch.Waiting ->
                    if (launch.compareAndSet(Launch.Waiting, Launch.Held(url))) return
                else -> {
                    channel.trySend(url)
                    return
                }
            }
        }
    }

    override suspend fun getInitialLink(): String? =
        (launch.exchange(Launch.Taken) as? Launch.Held)?.url

    override val links: Flow<String> = channel.receiveAsFlow()
}
