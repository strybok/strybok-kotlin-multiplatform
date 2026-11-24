package com.strybok.kmp

/**
 * **Android only.** Produces the Google Play Integrity token that proves a [Strybok.createLink]
 * request comes from a genuine build of the app on a genuine device. Used when the `attestation`
 * option of [Strybok] is switched on; ignored on iOS (which uses App Attest and needs no
 * provider) and on the JVM (which cannot attest).
 *
 * The SDK itself does not depend on Google's libraries, so apps for markets without Google Play
 * can use it unchanged. An app that wants the extra protection adds
 * `com.google.android.play:integrity` to its Android source set and passes a
 * `PlayIntegrityTokenProvider`; an app that already talks to Play Integrity can implement this
 * interface on top of its own code.
 */
public fun interface IntegrityTokenProvider {
    /**
     * Makes a Play Integrity **standard** request bound to [requestHash] and returns the token
     * exactly as Google returned it. Called only from inside `createLink`.
     *
     * Return `null` (or throw) when this device or build cannot produce one: no Google Play
     * services, no Play Store, a network failure, Google's quota. The SDK then sends the
     * create-link request without a proof and reports the service's answer; it never fails on
     * its own because of this. Implementations must honour coroutine cancellation; the SDK
     * gives up after 30 seconds.
     *
     * @param requestHash the value for `StandardIntegrityTokenRequest.setRequestHash`: unpadded
     *   base64url of the SHA-256 of the service's one-time challenge, 43 characters.
     */
    public suspend fun requestIntegrityToken(requestHash: String): String?
}
