# The Strybok SDK needs no keep rules of its own.
#
# PlayIntegrityTokenProvider is compiled against com.google.android.play:integrity, which the SDK
# does not bring along. Apps that do not use attestation do not have that library; tell R8 that
# the missing classes are expected.
-dontwarn com.google.android.play.core.integrity.**
-dontwarn com.google.android.gms.tasks.**

# The same for the install referrer sources of deferred links: PlayInstallReferrerSource is
# compiled against com.android.installreferrer:installreferrer and GalaxyStoreInstallReferrerSource
# against Samsung's store.galaxy.samsung.installreferrer library. An app adds the library of each
# store it wants to ask; the classes of the others are missing on purpose.
-dontwarn com.android.installreferrer.api.**
-dontwarn com.samsung.android.sdk.sinstallreferrer.api.**
