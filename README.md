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

Campaign values (`utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`) of the
open come with the link:

```kotlin
strybok.links.collect { link -> link.campaign.source?.let { analytics.label("source", it) } }
```

Campaign values are the link's defaults (set in the dashboard) overridden by the `utm_*`
parameters of the tapped URL. They are labels chosen by whoever shared the link: use them for
your analytics, never to decide anything. Details:
[integration guide](./docs/kotlin-multiplatform.md#campaign-values).

### Links without an API call (optional)

From 0.5.0, off by default. When the owner switches them on in the dashboard, path links
(`https://my-app.strybok.link/product/42?id=7`) and Firebase long links
(`https://my-app.strybok.link/?link=…`) open the app without a link being created first. Pass the
payload keys of your application — `parameterLinks.payloadKeys` of the integration guide
(`GET /v1/apps/:appId/integration`, shown on the dashboard's setup screen):

```kotlin
val strybok = Strybok(
    apiBaseUrl = "https://api.strybok.com",
    allowedHosts = setOf("my-app.strybok.link"),
    parameterLinks = ParameterLinks(listOf("id", "lang")),
)
```

The link arrives on `links` like any other; its `routePath` and `payload` are what the service
read, never the tapped path. Of the tapped URL only the path, the first value of each of these
keys and the `utm_*` values are sent (for a long link: the deep link's scheme, host and path with
its values of these keys, plus `apn` and `ibi`). Without the option the SDK sends exactly what
0.4.0 sent; deferred links always keep the single-slug rule. Invalid keys make `ParameterLinks`
throw `IllegalArgumentException`. **Unit-tested only, never run on a device.** Details:
[integration guide](./docs/kotlin-multiplatform.md#links-without-an-api-call).

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

### Deferred links (optional)

Restoring a link that was tapped before the app was installed. **Off by default**, and very
different per platform:

- **Android — automatic.** The store's install referrer carries a code; the SDK exchanges it
  and delivers the link about a second after the first start. Switch on **Restore the link
  after install** in the dashboard, add the store's library to the Android app and pass the
  application context and the sources:

  ```kotlin
  val strybok = Strybok(
      apiBaseUrl = "https://api.strybok.com",
      allowedHosts = setOf("my-app.strybok.link"),
      deferredLinks = true,
      // Android only, created in Android code; empty and null elsewhere:
      // listOf(PlayInstallReferrerSource(applicationContext)) and applicationContext
      installReferrerSources = installReferrerSources,
      platformContext = platformContext,
  )
  ```

  | Source                             | Store                | Add to the Android app                                                            | Standing                                     |
  | ---------------------------------- | -------------------- | --------------------------------------------------------------------------------- | -------------------------------------------- |
  | `PlayInstallReferrerSource`        | Google Play          | `com.android.installreferrer:installreferrer:2.2`                                 | From Google's documentation; not on a device |
  | `GalaxyStoreInstallReferrerSource` | Samsung Galaxy Store | `store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0` | **Unverified**                               |
  | `AppGalleryInstallReferrerSource`  | Huawei AppGallery    | No library; possibly manifest entries, see the guide                              | **Unverified**                               |

- **iOS — nothing automatic that Apple allows.** Two opt-in flows, each with a cost, described
  in [`docs/ios.md`](./docs/ios.md#links-tapped-before-the-app-is-installed):
  - **The person pastes the link.** A page before the App Store offers "Copy link and get the
    app"; your app shows a paste button and calls `strybok.redeemPastedLink(text)`. **The SDK
    never reads the pasteboard.** Cost: an extra page and tap for every iOS visitor without the
    app, and it works only for people who copy and later paste.
  - **Probabilistic matching** (`allowProbabilisticMatching = true`): the service guesses from
    the network address. **Forbidden by Apple's rules (fingerprinting), often wrong or
    impossible, and a match can be another person's link** — check
    `link.isProbabilisticMatch` before doing anything that matters. Not recommended.
- **JVM:** the options are ignored.

A deferred link arrives on `links` with `isDeferred == true`, never from `getInitialLink()`. If
your app asks for consent first, leave `deferredLinks` off and call
`strybok.enableDeferredLinks()` afterwards. `strybok.deferredLinkStatus` tells developers and QA
what happened; nothing is reported to `onError`.

The limits, plainly:

- Android: only when the app is installed **from the store page the link sent the person to**
  (not after a search, not from the store's website, not from an APK), **within 24 hours**, once.
- **Huawei AppGallery and Samsung Galaxy Store were never verified**, not even that those stores
  pass the value on.
- **None of it has run on a device, against a real store or with a real pasteboard** — only
  against fakes in unit tests.

Full guide: [`docs/kotlin-multiplatform.md`](./docs/kotlin-multiplatform.md#12-deferred-links-optional).

Full guide, native setup and troubleshooting:
[`docs/kotlin-multiplatform.md`](./docs/kotlin-multiplatform.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- Targets: Android (minSdk 21), `iosArm64`, `iosSimulatorArm64`, `iosX64`, and `jvm` (used for
  fast tests; reports the platform `unknown`).
- Dependencies: `kotlinx-coroutines-core` and `kotlinx-serialization-json` (plus
  `kotlinx-coroutines-android` on Android). HTTP uses the platform clients — `HttpURLConnection`
  and `NSURLSession` — behind the small `HttpTransport` interface: three GET/POST calls do not
  justify shipping Ktor and an engine per platform into every app. Google's Play Integrity
  library and the stores' install referrer libraries are not dependencies; only Android apps
  that switch attestation or deferred links on add them.
- **Early release (0.5.x)**: covered by unit tests on the JVM, Android host tests and the iOS simulator. **Not verified on
  a physical device.**
- Restoring a link after installation ("deferred links") is optional and off by default:
  automatic on Android, and on iOS only a paste step or a guess that Apple forbids — there is no
  automatic restore on iOS. See above.

## Install

The library is on Maven Central. In your shared module:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.5.0")
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
