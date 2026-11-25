package com.strybok.kmp.internal

import com.strybok.kmp.PlatformContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.preferredLanguages
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardDetectionPatternProbableWebURL
import platform.UIKit.UIUserInterfaceIdiom
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone

internal actual fun platformDeferred(context: PlatformContext?): DeferredPlatform? = IosDeferredPlatform

/** iOS needs no context. */
internal actual val deferredNeedsContext: String? = null

internal actual fun platformBackgroundContext(): CoroutineContext = Dispatchers.Default

/**
 * `iphone`, `ipad` or `ipod` — all the service is told about the device — or `null` for a Mac, a
 * TV, a headset or anything else, where matching is not attempted. An iPod touch reports the
 * phone idiom and is told apart by its model name.
 */
internal fun deviceFamilyOf(idiom: UIUserInterfaceIdiom, model: String): String? = when (idiom) {
    UIUserInterfaceIdiomPhone -> if (model.startsWith("iPod")) "ipod" else "iphone"
    UIUserInterfaceIdiomPad -> "ipad"
    else -> null
}

/**
 * Deferred links on a real device.
 *
 * `attempts` and `done` live in the same kind of store as the App Attest key id: small files in
 * the app's own storage (`Library/Application Support/com.strybok.sdk/deferred`), removed with
 * the app and excluded from backups, so a restored device starts as a new installation.
 *
 * **The pasteboard is never read.** The single call made on it asks the system whether its
 * content looks like a web URL, which Apple documents as not notifying the user; no content
 * leaves the pasteboard. `PasteboardSourceCheckTest` fails the build of this SDK when any
 * content-reading call appears in the iOS sources.
 */
internal object IosDeferredPlatform : DeferredPlatform {
    private val claimed = AtomicBoolean(false)

    override val kind: DeferredKind = DeferredKind.IOS

    override val store: KeyIdStore by lazy {
        FileKeyIdStore {
            val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String
            base?.let { "$it/com.strybok.sdk/deferred" }
        }
    }

    override fun appIdentifier(): String? = NSBundle.mainBundle.bundleIdentifier

    override fun deviceFamily(): String? =
        deviceFamilyOf(UIDevice.currentDevice.userInterfaceIdiom, UIDevice.currentDevice.model)

    override fun preferredLanguage(): String? =
        (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf { it.isNotEmpty() }

    override suspend fun pasteboardMayContainUrl(): Boolean = suspendCancellableCoroutine { continuation ->
        val wanted = setOf(UIPasteboardDetectionPatternProbableWebURL)
        UIPasteboard.generalPasteboard.detectPatternsForPatterns(wanted) { found, _ ->
            if (continuation.isActive) {
                continuation.resume(found?.contains(UIPasteboardDetectionPatternProbableWebURL) == true)
            }
        }
    }

    override fun claimProcess(): Boolean = claimed.compareAndSet(false, true)
}
