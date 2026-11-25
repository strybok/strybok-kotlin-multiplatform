package com.strybok.kmp

import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a result code of a vendor's `InstallReferrerClient.startConnection` means for the check.
 * Google's and Samsung's libraries have the same shape and the same codes: `OK` 0,
 * `SERVICE_UNAVAILABLE` 1, `FEATURE_NOT_SUPPORTED` 2, `DEVELOPER_ERROR` 3,
 * `SERVICE_DISCONNECTED` -1 (and Google's `PERMISSION_ERROR` 4).
 *
 * [read] returns `ReferrerDetails.getInstallReferrer()`; a failure there (a remote exception)
 * is "unavailable for now".
 */
internal fun installReferrerAnswer(responseCode: Int, read: () -> String?): InstallReferrerResult =
    when (responseCode) {
        0 -> try {
            InstallReferrerResult.Referrer(read().orEmpty())
        } catch (_: Exception) {
            InstallReferrerResult.UnavailableForNow
        }
        // Not supported by this store version, wrong use, or not allowed to bind: asking again is pointless.
        2, 3, 4 -> InstallReferrerResult.Unavailable
        // 1 and -1: the store could not answer right now. An unknown code is treated the same way.
        else -> InstallReferrerResult.UnavailableForNow
    }

/** Whether every class in [names] can be found, without initialising any of them. */
private fun classesExist(vararg names: String): Boolean = try {
    val loader = InstallReferrerSource::class.java.classLoader
    for (name in names) Class.forName(name, false, loader)
    true
} catch (_: ClassNotFoundException) {
    false
} catch (_: LinkageError) {
    false
}

/**
 * Runs [block], which must clean up after itself, and turns everything it throws into an answer:
 * a source never throws (cancellation by the SDK aside).
 */
private inline fun answering(block: () -> InstallReferrerResult): InstallReferrerResult = try {
    block()
} catch (e: CancellationException) {
    throw e // The SDK gave up on this call.
} catch (_: SecurityException) {
    InstallReferrerResult.Unavailable
} catch (_: LinkageError) {
    InstallReferrerResult.Unavailable // A different, incompatible version of the library.
} catch (_: Exception) {
    InstallReferrerResult.UnavailableForNow
}

/** The SDK abandons a source after 10 seconds; give up a little earlier and close the connection. */
private val CONNECT_WAIT = 9.seconds

/**
 * [InstallReferrerSource] for **Google Play**, on top of Google's Play Install Referrer library.
 *
 * **Your app must add the library itself**; the SDK only compiles against it and does not bring
 * it along:
 *
 * ```kotlin
 * dependencies {
 *     implementation("com.android.installreferrer:installreferrer:2.2")
 * }
 * ```
 *
 * Without the library this source answers "unavailable" and never throws. Written from Google's
 * documentation; it has not been run on a device.
 *
 * Of Google's `ReferrerDetails` only the referrer string is read; the click and install
 * timestamps are not. The connection to the Play Store is always closed again.
 *
 * @param context any context; the application context is used.
 */
public class PlayInstallReferrerSource(context: Context) : InstallReferrerSource {
    private val appContext: Context = context.applicationContext ?: context

    override suspend fun installReferrer(): InstallReferrerResult {
        if (!isLibraryAvailable()) return InstallReferrerResult.Unavailable
        return answering { PlayCalls.read(appContext) }
    }

    /**
     * Everything that names a class of Google's library lives here, so those classes are only
     * loaded after [isLibraryAvailable] said they exist.
     */
    private object PlayCalls {
        suspend fun read(context: Context): InstallReferrerResult {
            val client = com.android.installreferrer.api.InstallReferrerClient.newBuilder(context).build()
            val connected = CompletableDeferred<Int>()
            try {
                client.startConnection(object : com.android.installreferrer.api.InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        connected.complete(responseCode)
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        connected.complete(-1) // SERVICE_DISCONNECTED
                    }
                })
                val code = withTimeoutOrNull(CONNECT_WAIT) { connected.await() }
                    ?: return InstallReferrerResult.UnavailableForNow
                // A binder call: not on the main thread, where the listener was called.
                return withContext(Dispatchers.IO) {
                    installReferrerAnswer(code) { client.installReferrer.installReferrer }
                }
            } finally {
                try {
                    client.endConnection()
                } catch (_: Exception) {
                    // Nothing was connected.
                }
            }
        }
    }

    public companion object {
        /**
         * Whether `com.android.installreferrer:installreferrer` is part of this app. `false`
         * means this source always answers "unavailable" and the app must add the dependency.
         */
        public fun isLibraryAvailable(): Boolean = classesExist(
            "com.android.installreferrer.api.InstallReferrerClient",
            "com.android.installreferrer.api.InstallReferrerStateListener",
            "com.android.installreferrer.api.ReferrerDetails",
        )
    }
}

/**
 * [InstallReferrerSource] for the **Samsung Galaxy Store**, on top of Samsung's Install Referrer
 * library (Galaxy Store 4.5.20.1 or later).
 *
 * **Your app must add the library itself**; the SDK only compiles against it and does not bring
 * it along:
 *
 * ```kotlin
 * dependencies {
 *     implementation("store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0")
 * }
 * ```
 *
 * Without the library this source answers "unavailable" and never throws.
 *
 * **Unverified.** The calls were written from the classes of Samsung's published library; this
 * source has never run on a device, and whether the Galaxy Store hands a referrer from an
 * `https` store link to the app at all is known from third parties only.
 *
 * Of Samsung's `ReferrerDetails` only the referrer string is read. The connection to the Galaxy
 * Store is always closed again.
 *
 * @param context any context; the application context is used.
 */
public class GalaxyStoreInstallReferrerSource(context: Context) : InstallReferrerSource {
    private val appContext: Context = context.applicationContext ?: context

    override suspend fun installReferrer(): InstallReferrerResult {
        if (!isLibraryAvailable()) return InstallReferrerResult.Unavailable
        return answering { GalaxyCalls.read(appContext) }
    }

    /**
     * Everything that names a class of Samsung's library lives here, so those classes are only
     * loaded after [isLibraryAvailable] said they exist.
     */
    private object GalaxyCalls {
        suspend fun read(context: Context): InstallReferrerResult {
            val client =
                com.samsung.android.sdk.sinstallreferrer.api.InstallReferrerClient.newBuilder(context).build()
            val connected = CompletableDeferred<Int>()
            try {
                client.startConnection(
                    object : com.samsung.android.sdk.sinstallreferrer.api.InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            connected.complete(responseCode)
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            connected.complete(-1) // SERVICE_DISCONNECTED
                        }
                    },
                )
                val code = withTimeoutOrNull(CONNECT_WAIT) { connected.await() }
                    ?: return InstallReferrerResult.UnavailableForNow
                return withContext(Dispatchers.IO) {
                    installReferrerAnswer(code) { client.installReferrer?.installReferrer }
                }
            } finally {
                try {
                    client.endConnection()
                } catch (_: Exception) {
                    // Nothing was connected.
                }
            }
        }
    }

    public companion object {
        /**
         * Whether Samsung's Install Referrer library is part of this app. `false` means this
         * source always answers "unavailable" and the app must add the dependency.
         */
        public fun isLibraryAvailable(): Boolean = classesExist(
            "com.samsung.android.sdk.sinstallreferrer.api.InstallReferrerClient",
            "com.samsung.android.sdk.sinstallreferrer.api.InstallReferrerStateListener",
            "com.samsung.android.sdk.sinstallreferrer.api.ReferrerDetails",
        )
    }
}

/**
 * [InstallReferrerSource] for **Huawei AppGallery** (10.5.0.300 or later). Needs no library:
 * AppGallery hands the referrer over through a content provider,
 * `content://com.huawei.appmarket.commondata/item/5`, queried with the app's own package name.
 * Column 5 of the first row is the referrer; no other column is read.
 *
 * **Unverified.** Written from Huawei's AppGallery Connect pages; it has never run on a device.
 * Two things are not known:
 *
 * - whether an app that targets Android 11 or later needs a `<queries>` entry to see the
 *   provider, and whether the provider asks for the permission
 *   `com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA`. The SDK adds neither
 *   to your manifest; see `docs/kotlin-multiplatform.md` for the entries to try.
 *   Without access this source answers "unavailable".
 * - whether AppGallery returns a value that contains `=` and `&` unchanged.
 *
 * Huawei states that the provider "may be called only after user consent". That consent is
 * your app's responsibility: enable deferred links (or add this source) only afterwards.
 */
public class AppGalleryInstallReferrerSource internal constructor(
    /** Runs the query; `null` when there is no such provider. */
    private val open: () -> Cursor?,
) : InstallReferrerSource {
    /** @param context any context; the application context is used. */
    public constructor(context: Context) : this(queryOf(context.applicationContext ?: context))

    override suspend fun installReferrer(): InstallReferrerResult = withContext(Dispatchers.IO) {
        answering {
            val cursor = open() ?: return@answering InstallReferrerResult.Unavailable
            try {
                if (!cursor.moveToFirst() || cursor.columnCount <= REFERRER_COLUMN) {
                    InstallReferrerResult.Unavailable
                } else {
                    InstallReferrerResult.Referrer(cursor.getString(REFERRER_COLUMN).orEmpty())
                }
            } finally {
                cursor.close()
            }
        }
    }

    private companion object {
        const val PROVIDER_URI = "content://com.huawei.appmarket.commondata/item/5"

        /** Huawei: "Referrer parameter set in the download link". */
        const val REFERRER_COLUMN = 5

        fun queryOf(context: Context): () -> Cursor? = {
            context.contentResolver.query(Uri.parse(PROVIDER_URI), null, null, arrayOf(context.packageName), null)
        }
    }
}
