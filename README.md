# strybok-kmp (Kotlin Multiplatform SDK)

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

Full guide, native setup and troubleshooting:
[`docs/kotlin-multiplatform.md`](./docs/kotlin-multiplatform.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- Targets: Android (minSdk 21), `iosArm64`, `iosSimulatorArm64`, `iosX64`, and `jvm` (used for
  fast tests; reports the platform `unknown`).
- Dependencies: `kotlinx-coroutines-core` and `kotlinx-serialization-json` (plus
  `kotlinx-coroutines-android` on Android). HTTP uses the platform clients — `HttpURLConnection`
  and `NSURLSession` — behind the small `HttpTransport` interface: two GET/POST calls do not
  justify shipping Ktor and an engine per platform into every app.
- **Early release (0.1.x)**: covered by unit tests on the JVM, Android host tests and the iOS simulator. **Not verified on
  a physical device.**
- Deferred deep linking is not supported.

## Install

The library is on Maven Central. In your shared module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.1.0")
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
