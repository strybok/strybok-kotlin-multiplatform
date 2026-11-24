# strybok-kmp (Kotlin Multiplatform SDK)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fcom%2Fstrybok%2Fstrybok-kmp%2Fmaven-metadata.xml&label=maven%20central)](https://central.sonatype.com/artifact/com.strybok/strybok-kmp) [![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](./LICENSE)

Receives iOS Universal Links / Android App Links in a Kotlin Multiplatform or Compose
Multiplatform app, resolves them through the Strybok API to an in-app route and payload, and
delivers each link exactly once — on cold start, from the background and in the foreground.

```kotlin
val strybok = Strybok(
    apiBaseUrl = "https://api.strybok.com",
    allowedHosts = setOf("my-app.strybok.link"),
    onError = { error -> println("Link failed: $error") },
)

scope.launch { strybok.links.collect { link -> navigate(link.routePath) } }
scope.launch { strybok.start() }

// Android: strybok.handleIntent(intent) in onCreate / onNewIntent
// iOS:     strybok.handleUrl(url: url) or strybok.handleUserActivity(userActivity: activity)
```

Create a link to share (needs the application's publishable key, `clientKey = "pk_…"`, and the
"Links from the app" feature enabled in the dashboard):

```kotlin
val link = strybok.createLink("/product/42", payload = mapOf("productId" to 42), title = "Red shoes")
share(link.url)
```

### Extra protection (optional)

The publishable key can be copied out of an app. The owner of the application can therefore ask
Strybok to accept link requests only — or preferably — from a genuine build of the app on a
genuine device, proved with Apple **App Attest** on iOS and Google **Play Integrity** on Android
(the JVM target cannot attest). It is **off by default**, and the SDK does not depend on
Google's libraries: an Android app adds `com.google.android.play:integrity` itself.

```kotlin
val strybok = Strybok(
    apiBaseUrl = "https://api.strybok.com",
    allowedHosts = setOf("my-app.strybok.link"),
    clientKey = "pk_…",
    attestation = true, // all that iOS needs
    // Android only, created in Android code:
    // PlayIntegrityTokenProvider(applicationContext, cloudProjectNumber = 123456789012L)
    integrityTokenProvider = integrityTokenProvider,
)
```

Before that: add the **App Attest** capability to the iOS app target in Xcode; for Android, link
a Google Cloud project with the Play Integrity API to the app in the Play Console and upload a
service-account key in the Strybok dashboard; then choose the mode in the dashboard
(**Preferred** first). The steps are in the
[integration guide](./docs/kotlin-multiplatform.md#extra-protection) and in
[Keeping link creation from your app safe](./docs/link-creation-security.md).

`createLink` is called as before; the proof is made lazily inside it, about once an hour, never
at start-up. Two more errors can come out: `StrybokAttestationRequiredException` (the owner
requires a proof and this device or build could not give one) and
`StrybokAttestationFailedException` (a fresh proof was refused twice). A device that cannot
attest still sends the request, and the service decides.

Limits you should know:

- iOS: App Attest needs iOS 14 or later and a **real device**; it does not work in the
  simulator.
- Android: it needs Google Play services, the Play Store and a build **installed from Google
  Play**. It does not work on devices without Google services (most devices in mainland China,
  Huawei without GMS) or for sideloaded builds. For a flavour without Google libraries, leave
  the dependency out and pass no provider.
- Google allows 10,000 integrity requests a day per app by default.
- **It has never run against Apple's or Google's real services or on a physical device** — only
  against fakes in unit tests. Treat the **Required** mode as experimental.

Full guide, native setup and troubleshooting:
[`docs/kotlin-multiplatform.md`](./docs/kotlin-multiplatform.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- Targets: Android (minSdk 21), `iosArm64`, `iosSimulatorArm64`, `iosX64`, and `jvm` (used for
  fast tests; reports the platform `unknown`).
- Dependencies: `kotlinx-coroutines-core` and `kotlinx-serialization-json` (plus
  `kotlinx-coroutines-android` on Android). HTTP uses the platform clients — `HttpURLConnection`
  and `NSURLSession` — behind the small `HttpTransport` interface: three GET/POST calls do not
  justify shipping Ktor and an engine per platform into every app. Google's Play Integrity
  library is not a dependency; only Android apps that switch attestation on add it.
- **Early release (0.3.x)**: covered by unit tests on the JVM, Android host tests and the iOS simulator. **Not verified on
  a physical device.**
- Deferred deep linking is not supported.

## Install

The library is on Maven Central. In your shared module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.3.0")
        }
    }
}
```

To let Swift see the `Strybok` class, also `export` the same coordinates from your iOS framework;
the guide has the details.

## Build and test

Needs JDK 17+, the Android SDK (`ANDROID_HOME` or a `local.properties` with `sdk.dir=...`) and,
for the iOS targets, a Mac with Xcode. Built with Gradle 9.1.0, Kotlin 2.3.20 and AGP 9.0.1.

```sh
./gradlew jvmTest                 # contract tests + HttpURLConnection transport, fastest
./gradlew testAndroidHostTest     # the same contract tests on the Android target
./gradlew iosSimulatorArm64Test   # the same contract tests on Kotlin/Native
./gradlew assemble                # all targets, including iosArm64 and iosX64
```

## Licence

Apache 2.0, see [LICENSE](./LICENSE).
