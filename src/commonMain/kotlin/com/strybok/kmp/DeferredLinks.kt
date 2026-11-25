package com.strybok.kmp

/**
 * What the platform needs to keep the two values of the deferred-link check: the application
 * `Context` on Android (this type **is** `android.content.Context` there). iOS and the JVM need
 * nothing; there the type cannot be instantiated and the `platformContext` argument of
 * [Strybok] stays `null`.
 */
public expect abstract class PlatformContext

/**
 * Where the deferred-link check of this installation stands; see [Strybok.deferredLinkStatus].
 *
 * A check that finds nothing is the normal case — most people who install an app never tapped a
 * link — so none of these values is an error and none is ever reported to `onError`. The status
 * is meant for developers and QA.
 */
public enum class DeferredLinkStatus(
    /** Whether this installation will never run the check again. */
    public val isFinal: Boolean,
) {
    /** The `deferredLinks` option is off, or this target has no deferred links (the JVM). */
    DISABLED(false),

    /** The check is running in this launch (or waits for [Strybok.start]). */
    CHECKING(false),

    /** This launch could not finish the check (store or network not reachable); a later launch tries again. */
    PENDING(false),

    /**
     * iOS only: nothing happens until the app hands over what the person pasted
     * ([Strybok.redeemPastedLink]) or says that they moved on ([Strybok.skipPastedLink]).
     */
    AWAITING_PASTE(false),

    /** The check was finished in an earlier launch, or the app was updated since it was installed. */
    NOT_FIRST_LAUNCH(true),

    /** No source is configured, or none can ever answer on this device. */
    UNAVAILABLE(true),

    /** The store returned a referrer without a click id of ours: the usual result of an ordinary install. */
    NO_REFERRER(true),

    /** The service refused: unknown, too old, already used, not this application's — or nothing matched. */
    NOT_FOUND(true),

    /** A normal link of this app arrived first, so the deferred link was dropped. */
    SUPERSEDED(true),

    /** The link was handed to the delivery pipeline. */
    DELIVERED(true),

    /** Three launches could not finish the check, or the answer of the service was unusable. */
    FAILED(true),
}

/** The answer of [Strybok.redeemPastedLink] (iOS). */
public enum class PastedLinkResult {
    /** The link behind the pasted text was delivered through [Strybok.links]. */
    DELIVERED,

    /**
     * The text is not a copied link of this app. Nothing was sent, stored or logged: the person
     * may have had anything on their clipboard.
     */
    NOT_OURS,

    /** The service refused the link: unknown, older than 24 hours, already used, or another app's. */
    NOT_FOUND,

    /** A deferred link was already handled on this installation; nothing was sent. */
    ALREADY_HANDLED,

    /** The `deferredLinks` option is off, or this is not iOS; nothing was sent. */
    DISABLED,

    /**
     * No usable answer. After a network failure the person can tap Paste again; after an
     * unusable answer of the service a later call returns [ALREADY_HANDLED].
     */
    FAILED,
}

/** The answer of an [InstallReferrerSource]: exactly one of the three cases. */
public sealed class InstallReferrerResult {
    /**
     * The raw install referrer of the store the app was installed from, exactly as the store
     * returned it (it may be empty).
     */
    public class Referrer(public val value: String) : InstallReferrerResult() {
        /** Never contains the referrer: it must not end up in a log. */
        override fun toString(): String = "InstallReferrerResult.Referrer"
    }

    /** The store could not be asked; a later launch may succeed. */
    public data object UnavailableForNow : InstallReferrerResult()

    /** This store is not on the device, is too old, or its library is not in the app. */
    public data object Unavailable : InstallReferrerResult()
}

/**
 * Asks one app store for the install referrer of this app (Android). Shipped implementations on
 * the Android target: `PlayInstallReferrerSource`, `GalaxyStoreInstallReferrerSource` and
 * `AppGalleryInstallReferrerSource`; write your own for another store or for tests.
 *
 * The SDK calls [installReferrer] away from the main thread and never while it starts. An
 * implementation
 *
 * - must not throw (the SDK treats an exception as [InstallReferrerResult.UnavailableForNow]);
 * - must answer within 10 seconds: after that the SDK cancels the call, abandons it and treats
 *   it as "unavailable for now";
 * - must release whatever connection it opened, also when it is cancelled.
 */
public fun interface InstallReferrerSource {
    public suspend fun installReferrer(): InstallReferrerResult
}
