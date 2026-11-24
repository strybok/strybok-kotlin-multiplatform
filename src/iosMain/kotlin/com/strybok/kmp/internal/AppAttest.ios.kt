package com.strybok.kmp.internal

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.DeviceCheck.DCAppAttestService
import platform.DeviceCheck.DCErrorDomain
import platform.DeviceCheck.DCError
import platform.Foundation.NSData
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.create
import platform.posix.memcpy

/**
 * [AppAttestService] over `DCAppAttestService.shared` (iOS 14+).
 *
 * App Attest does not exist in the simulator (`isSupported` is `false` there), so the three
 * calls below are not covered by the automated tests; everything that decides what to do with
 * their results is, through a fake.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object DeviceCheckAppAttestService : AppAttestService {
    override val isSupported: Boolean get() = DCAppAttestService.sharedService.isSupported()

    override suspend fun generateKey(): String = suspendCancellableCoroutine { continuation ->
        DCAppAttestService.sharedService.generateKeyWithCompletionHandler { keyId, error ->
            continuation.finish(keyId, error)
        }
    }

    override suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray =
        suspendCancellableCoroutine { continuation ->
            DCAppAttestService.sharedService.attestKey(keyId, clientDataHash.toNSData()) { attestation, error ->
                continuation.finish(attestation?.toByteArray(), error)
            }
        }

    override suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray =
        suspendCancellableCoroutine { continuation ->
            DCAppAttestService.sharedService.generateAssertion(keyId, clientDataHash.toNSData()) { assertion, error ->
                continuation.finish(assertion?.toByteArray(), error)
            }
        }

    private fun <T : Any> CancellableContinuation<T>.finish(value: T?, error: NSError?) {
        if (!isActive) return // Given up on; Apple's handlers cannot be cancelled.
        if (value != null) resume(value) else resumeWithException(AppAttestException(failureOf(error)))
    }
}

/** Which of Apple's `DCError` codes [error] is, as far as the flow cares. */
internal fun failureOf(error: NSError?): AppAttestFailure = when {
    error == null || error.domain != DCErrorDomain -> AppAttestFailure.Other
    error.code == DCError.DCErrorInvalidKey.value -> AppAttestFailure.InvalidKey
    error.code == DCError.DCErrorServerUnavailable.value -> AppAttestFailure.ServerUnavailable
    else -> AppAttestFailure.Other
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/**
 * [KeyIdStore] in the app's own storage: one small file per value under
 * `Library/Application Support/com.strybok.sdk/attestation`, excluded from backups.
 *
 * The contract allows the Keychain or app storage for the App Attest key id. App storage is used
 * because the value is an identifier, not a secret, and because it has the life span of the key
 * itself: it is removed with the app (a reinstall invalidates the key anyway, while a Keychain
 * item would outlive it) and is not carried to another device. It also needs no entitlement and
 * no "required reason" API declaration, and it can be tested in the simulator.
 *
 * Every failure is treated as "nothing stored": the SDK then registers a new key.
 *
 * @param directory where the files go; `null` when the platform could not name a directory.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class FileKeyIdStore(private val directory: () -> String?) : KeyIdStore {
    override fun get(name: String): String? {
        val path = pathOf(name) ?: return null
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_VALUE_LENGTH }
    }

    override fun set(name: String, value: String) {
        val directory = directory() ?: return
        val path = pathOf(name) ?: return
        val files = NSFileManager.defaultManager
        if (!files.fileExistsAtPath(directory)) {
            files.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            // A key cannot leave its device, so its identifier has no place in a backup.
            NSURL.fileURLWithPath(directory, isDirectory = true)
                .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        }
        @Suppress("CAST_NEVER_SUCCEEDS")
        (value as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override fun remove(name: String) {
        val path = pathOf(name) ?: return
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** Names are made by the SDK (`key.<hex>`); anything else is refused rather than used as a path. */
    private fun pathOf(name: String): String? {
        if (name.isEmpty() || !name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' } || ".." in name) return null
        return directory()?.let { "$it/$name" }
    }

    companion object {
        private const val MAX_VALUE_LENGTH = 256

        /** The store of the SDK. Creating it does not touch the file system. */
        val shared: FileKeyIdStore by lazy {
            FileKeyIdStore {
                val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                    .firstOrNull() as? String
                base?.let { "$it/com.strybok.sdk/attestation" }
            }
        }
    }
}
