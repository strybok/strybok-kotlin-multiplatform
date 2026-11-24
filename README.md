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

Full guide, native setup and troubleshooting:
[`docs/kotlin-multiplatform.md`](./docs/kotlin-multiplatform.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- Targets: Android (minSdk 21), `iosArm64`, `iosSimulatorArm64`, `iosX64`, and `jvm` (used for
  fast tests; reports the platform `unknown`).
- Dependencies: `kotlinx-coroutines-core` and `kotlinx-serialization-json` (plus
  `kotlinx-coroutines-android` on Android). HTTP uses the platform clients — `HttpURLConnection`
  and `NSURLSession` — behind the small `HttpTransport` interface: three GET/POST calls do not
  justify shipping Ktor and an engine per platform into every app.
- **Early release (0.2.x)**: covered by unit tests on the JVM, Android host tests and the iOS simulator. **Not verified on
  a physical device.**
- Deferred deep linking is not supported.

## Install

The library is on Maven Central. In your shared module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.2.0")
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
