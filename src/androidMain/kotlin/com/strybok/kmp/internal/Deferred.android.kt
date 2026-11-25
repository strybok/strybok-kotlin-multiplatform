package com.strybok.kmp.internal

import android.content.Context
import com.strybok.kmp.PlatformContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

internal actual fun platformDeferred(context: PlatformContext?): DeferredPlatform? =
    context?.let(::AndroidDeferredPlatform)

internal actual val deferredNeedsContext: String? =
    "Deferred links need the application Context on Android: pass platformContext to Strybok(...)"

internal actual fun platformBackgroundContext(): CoroutineContext = Dispatchers.IO

/**
 * [KeyIdStore] as one tiny file per value in [directory]. Every failure is "nothing stored";
 * the deferred-link state reads a value back after writing it.
 */
internal class FileValueStore(private val directory: () -> File?) : KeyIdStore {
    override fun get(name: String): String? = try {
        fileOf(name)?.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()
    } catch (_: Exception) {
        null
    }

    /** Writes through a temporary file, so a crash leaves the old value or the new one. */
    override fun set(name: String, value: String) {
        try {
            val directory = directory() ?: return
            val target = fileOf(name) ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            val temporary = File(directory, "$name.tmp")
            temporary.writeText(value, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.writeText(value, Charsets.UTF_8)
                temporary.delete()
            }
        } catch (_: Exception) {
            // Nothing stored.
        }
    }

    override fun remove(name: String) {
        try {
            fileOf(name)?.delete()
        } catch (_: Exception) {
            // Nothing to remove.
        }
    }

    /** Names are made by the SDK; anything else is refused rather than used as a path. */
    private fun fileOf(name: String): File? {
        if (name.isEmpty() || !name.all { it in 'a'..'z' || it in '0'..'9' }) return null
        return directory()?.let { File(it, name) }
    }
}

/**
 * Deferred links in a real app. Nothing is touched before the check asks.
 *
 * The two values are files inside `Context.getNoBackupFilesDir()` and not `SharedPreferences`:
 * Android's automatic backup would otherwise restore `done` into a fresh installation, which is
 * exactly the launch the check exists for.
 */
internal class AndroidDeferredPlatform(context: Context) : DeferredPlatform {
    private val appContext: Context = context.applicationContext ?: context

    override val kind: DeferredKind = DeferredKind.INSTALL_REFERRER

    override val store: KeyIdStore = FileValueStore { File(appContext.noBackupFilesDir, "com.strybok.sdk/deferred") }

    override fun appIdentifier(): String? = appContext.packageName

    override fun updatedSinceInstall(): Boolean = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.firstInstallTime != info.lastUpdateTime
    } catch (_: Exception) {
        false
    }

    override fun claimProcess(): Boolean = claimed.compareAndSet(false, true)

    private companion object {
        /** One check per process, however many `Strybok` instances the app creates. */
        val claimed = AtomicBoolean(false)
    }
}
