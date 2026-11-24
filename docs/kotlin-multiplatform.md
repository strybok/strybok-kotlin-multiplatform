# Kotlin Multiplatform integration

The `strybok-kmp` library is for Kotlin Multiplatform and Compose Multiplatform apps on Android
and iOS. Your app hands it the URL the OS opened it with; it asks the Strybok API which in-app
route the link stands for, and gives your shared code each link exactly once.

> **Status:** covered by unit tests on the JVM, Android host tests and the iOS simulator
> (Kotlin/Native), and the Swift snippets below type-check against the generated framework.
> Early release (0.2.x), published on Maven Central. **Not verified on a physical device** (see
> the [manual test plan](./manual-test-plan.md)) and not tried inside a complete Compose
> Multiplatform app.
>
> Deferred deep linking is **not supported**: a link tapped before the app is installed is not
> restored after installation.

It follows the [SDK contract](./sdk-contract.md) shared by all Strybok SDKs.

## 1. Prerequisites

1. Create an application in Strybok with your iOS and Android identifiers.
2. Read its setup data: `GET /v1/apps/:appId/integration` returns the managed hostname
   (for example `my-app.strybok.link`) and ready-to-copy snippets.
3. Complete the native setup: [Android](./android.md) (intent filter with
   `android:autoVerify="true"`) and [iOS](./ios.md) (Associated Domains). Without it the OS
   never opens the app for a link, and the SDK has nothing to receive. The Flutter-only steps
   in those guides do not apply.

## 2. Add the library

The library is on Maven Central. Depend on it from your shared module, and export it to the iOS
framework so Swift sees the `Strybok` class:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            export("com.strybok:strybok-kmp:0.3.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.3.0")
        }
    }
}
```

`mavenCentral()` must be among your repositories.

Requirements and what was tried:

- Built and tested with Kotlin 2.3.20, AGP 9.0.1 and Gradle 9.1.0. The library uses the
  `com.android.kotlin.multiplatform.library` plugin (the Android-KMP plugin that AGP 9 expects)
  and common atomics from the Kotlin standard library, so it needs Kotlin 2.1.20 or newer;
  older combinations were not tried.
- Android `minSdk` 21. iOS targets: `iosArm64`, `iosSimulatorArm64`, `iosX64`.
- To build from source instead, clone
  [github.com/strybok/strybok-kotlin-multiplatform](https://github.com/strybok/strybok-kotlin-multiplatform)
  and add `includeBuild("../strybok-kotlin-multiplatform")` to `settings.gradle.kts`; Gradle then
  substitutes the same coordinates with the clone. Both builds must use the same Kotlin and
  Android Gradle plugin versions.

The library adds the `android.permission.INTERNET` permission to your manifest.

## 3. Create the SDK in shared code

Keep one instance for the whole process:

```kotlin
// shared/src/commonMain/kotlin/AppLinks.kt
import com.strybok.kmp.Strybok

object AppLinks {
    val strybok = Strybok(
        apiBaseUrl = "https://api.strybok.com",
        allowedHosts = setOf("my-app.strybok.link"),
        onError = { error -> println("Link failed: $error") },
    )
}
```

Do not create it per screen or per Activity, and do not `dispose()` it when an Activity is
destroyed: a disposed instance cannot be reused.

## 4. Forward links on Android

`MainActivity` in the Android app (declare it with `android:launchMode="singleTop"` so a link
tapped while the app is open arrives in `onNewIntent` instead of creating a second activity):

```kotlin
import com.strybok.kmp.handleIntent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not after a rotation or process restore: that would replay the old link.
        if (savedInstanceState == null) AppLinks.strybok.handleIntent(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLinks.strybok.handleIntent(intent)
    }
}
```

`handleIntent` only looks at `ACTION_VIEW` intents with a URL, ignores intents replayed from the
recents screen, and returns `true` when the URL is a link of this app. Other URLs are left to
you.

## 5. Forward links on iOS

Kotlin extension functions appear as methods in Swift: `strybok.handleUrl(url:)` and
`strybok.handleUserActivity(userActivity:)`.

SwiftUI app lifecycle (`iOSApp.swift`):

```swift
import Shared
import SwiftUI

@main
struct iOSApp: App {
    private let strybok = AppLinks.shared.strybok

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    _ = strybok.handleUrl(url: url)
                }
        }
    }
}
```

UIKit scene lifecycle (`SceneDelegate.swift`):

```swift
import Shared
import UIKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    private let strybok = AppLinks.shared.strybok

    // Cold start: the link that launched the app.
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        for activity in connectionOptions.userActivities {
            _ = strybok.handleUserActivity(userActivity: activity)
        }
    }

    // App already running.
    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        _ = strybok.handleUserActivity(userActivity: userActivity)
    }
}
```

Without scenes, forward `application(_:continue:restorationHandler:)` the same way. If a link
reaches the SDK through two of these hooks, the second copy is dropped (`dedupeWindow`).

iOS only allows HTTPS requests by default (App Transport Security), so `apiBaseUrl` must be
`https://`.

## 6. Navigate (Compose Multiplatform)

Collect `links` where your navigation lives. This example uses Navigation Compose with
type-safe routes; it shows the intended wiring and was not compiled as part of the SDK's build.

```kotlin
@Serializable data object Home
@Serializable data class Invite(val code: String)

private val inviteRoute = Regex("^/invite/([A-Za-z0-9_-]{1,64})$")

@Composable
fun App(strybok: Strybok = AppLinks.strybok) {
    val navController = rememberNavController()

    LaunchedEffect(strybok) {
        launch { strybok.start() }
        strybok.links.collect { link ->
            // routePath and payload are untrusted: only open routes the app knows.
            val invite = inviteRoute.matchEntire(link.routePath)
            if (invite != null) navController.navigate(Invite(invite.groupValues[1]))
        }
    }

    NavHost(navController, startDestination = Home) {
        composable<Home> { HomeScreen() }
        composable<Invite> { entry -> InviteScreen(entry.toRoute<Invite>().code) }
    }
}
```

One collector covers every case:

| Situation                                 | What happens                                                                      |
| ----------------------------------------- | --------------------------------------------------------------------------------- |
| **Cold start** (app launched by the link) | The link is resolved, kept until something collects `links`, then delivered once. |
| **Background** (app resumed by the link)  | Delivered on `links`.                                                             |
| **Foreground**                            | Delivered on `links`.                                                             |

A link that arrives while nothing collects (for example while the collecting composable is not
in the composition) is queued and goes to the next collector. `LaunchedEffect` restarts its
collector after an Android configuration change; queued links are not lost and delivered links
are not repeated.

If the same tap is reported twice, the duplicate is dropped (`dedupeWindow`, 2 s by default).
The same link tapped again later is a new delivery.

Prefer callbacks, or navigating from Swift? `addListener` does the same as `links`, and calls
you on the main thread:

```swift
let subscription = strybok.addListener { link in
    print(link.routePath, link.payload["invitationId"] as? String ?? "")
}
Task { try? await strybok.start() }
// later: subscription.cancel()
```

### API

| Member               | Purpose                                                                                               |
| -------------------- | ----------------------------------------------------------------------------------------------------- |
| `start()`            | Suspends until the launch link was handled; later links keep arriving. Idempotent.                    |
| `links`              | `Flow<ResolvedDeepLink>` (`url`, `linkId`, `appId`, `routePath`, `payload`, `payloadJson`).           |
| `addListener { }`    | Callback version of `links`; returns a `Subscription` with `cancel()`.                                |
| `getInitialLink()`   | The launch link, if not delivered through `links` / a listener yet. At most once.                     |
| `resolve(url)`       | Resolve a URL explicitly (QR scanner, tests). Does not emit on `links` or report an app open.         |
| `isStrybokLink(url)` | Whether a URL is one of this app's links.                                                             |
| `handleLink(url)`    | Hand over a URL string yourself; `handleIntent` / `handleUrl` / `handleUserActivity` are built on it. |
| `createLink(…)`      | Make a short link to a screen of the app. See [Create a link to share](#11-create-a-link-to-share).   |
| `dispose()`          | Stop for good: nothing is delivered afterwards, collectors complete.                                  |

Options (Kotlin named arguments): `timeout` (5 s per attempt), `maxRetries` (2; only timeouts,
network errors and 5xx are retried), `retryDelay` (400 ms, doubles per retry), `dedupeWindow`
(2 s), `reportAppOpens` (`true`), `onError`, `clientKey` (only for `createLink`), `attestation`
and `integrityTokenProvider` (only for `createLink`, see
[Extra protection](#extra-protection)), plus `linkSource`, `transport` and `coroutineContext` for
tests and custom setups. Swift sees three short constructors with all defaults,
`Strybok(apiBaseUrl:allowedHosts:onError:)`, `Strybok(apiBaseUrl:allowedHosts:clientKey:onError:)`
and `Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:onError:)`; configure anything else in
Kotlin.

Things to know:

- **Launch link.** A URL handed to the SDK _before_ `start()` runs is the launch link, which is
  what `getInitialLink()` returns. If your shared code starts the SDK before the platform
  forwards the URL (possible on iOS, where `onOpenURL` fires after the UI is up), the link is
  simply delivered through `links` and `getInitialLink()` returns `null`. Collecting `links` is
  correct in both cases; use `getInitialLink()` only if you control the order.
- **Threads.** The SDK works on the main thread on Android and iOS (network I/O does not block
  it), and `onError` and listeners are called there. All methods may be called from any thread.
- **`payload`** is decoded to plain values (`String`, `Boolean`, `Long`, `Double`, `List`,
  `Map`, `null`); `payloadJson` is the same object as JSON text.
- **Swift `async`.** `start()`, `resolve(url:)` and `getInitialLink()` are `suspend` functions
  and are available as `async throws` in Swift.

### Errors

`resolve` throws, and `onError` receives, a `StrybokException` (a sealed class; every error has
the `url` being handled). The same class holds the errors that only `createLink` throws (see
[Create a link to share](#11-create-a-link-to-share)); they never reach `onError`.

| Type                                                  | Meaning                                                                                                                                                                     |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `UnsupportedLinkException`                            | Not `https`, host not in `allowedHosts`, credentials or a port, or not a single-slug path. Thrown before any network call. Ignored silently for links arriving from the OS. |
| `LinkNotFoundException`                               | Unknown, disabled or expired link.                                                                                                                                          |
| `StrybokTimeoutException` / `StrybokNetworkException` | No answer after all retries (e.g. offline). The app keeps working; the link is simply not opened.                                                                           |
| `StrybokServerException`                              | Unexpected status or malformed response; has `statusCode`.                                                                                                                  |

```kotlin
onError = { error ->
    when (error) {
        is LinkNotFoundException -> showMessage("This link is no longer available")
        is StrybokTimeoutException, is StrybokNetworkException -> showMessage("You seem to be offline")
        is StrybokServerException, is UnsupportedLinkException -> log(error)
        else -> log(error) // createLink only
    }
}
```

In Swift a thrown error arrives as `NSError`; the Kotlin exception is
`(error as NSError).kotlinException as? StrybokException`. Using the SDK after `dispose()`
throws `IllegalStateException`.

## 7. Treat routes as untrusted input

`routePath` and `payload` are data from the network, chosen by whoever created the link. Match
the route against routes your app knows before navigating (as in the example above), and never
execute payload contents.

## 8. Links that arrive before sign-in

If a link needs an authenticated user, remember the route and open it after login instead of
dropping it: keep it in your session state while signed out and navigate once the user is in.

## 9. Analytics and privacy

After a link is delivered (or queued) the SDK sends one best-effort `app_open` event
(`POST /v1/public/events`) containing a random event id, the link id, the platform
(`ios`/`android`) and the SDK version — no device identifiers. It never delays navigation and
all failures are ignored. Disable it with `reportAppOpens = false`.

Only the link's host and slug are sent to `resolve`; query parameters and fragments stay on the
device. On iOS the SDK uses an ephemeral `NSURLSession` (no cookies or cache on disk).
`createLink` sends the client key and what you pass to it (route, payload, title, description,
image URL), and only when your app calls it.

## 10. Testing your integration

`FakeLinkSource` simulates links, and `HttpTransport` is a single-function interface, so no
device or network is needed. With `kotlinx-coroutines-test`:

```kotlin
import com.strybok.kmp.HttpResponse
import com.strybok.kmp.Strybok
import com.strybok.kmp.testing.FakeLinkSource

@Test
fun opensTheInvite() = runTest {
    val source = FakeLinkSource(initialLink = "https://my-app.strybok.link/invite777")
    val strybok = Strybok(
        apiBaseUrl = "https://api.strybok.com",
        allowedHosts = setOf("my-app.strybok.link"),
        linkSource = source,
        transport = { request ->
            if (request.method == "GET") {
                HttpResponse(200, """{"linkId":"l1","appId":"a1","routePath":"/invite/abc123"}""")
            } else {
                HttpResponse(202, "")
            }
        },
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    assertEquals("/invite/abc123", strybok.getInitialLink()?.routePath)

    source.emit("https://my-app.strybok.link/other") // a link while the app runs
    assertEquals("/invite/abc123", strybok.links.first().routePath)
    strybok.dispose()
}
```

The SDK's own tests (`src/commonTest` in the SDK directory) show more patterns (timeouts and
retries with virtual time, duplicates, disposal).

On a device, simulate a tap with
`adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://my-app.strybok.link/<slug>"`
or `xcrun simctl openurl booted "https://my-app.strybok.link/<slug>"` (the simulator needs the
Associated Domains entitlement to route the URL to your app).

## 11. Create a link to share

`createLink` asks Strybok for a short link to a screen of your app — what a "Share" button
needs. It is optional and independent of receiving links.

1. In the dashboard, open the application and enable **Links from the app**.
2. List the **route patterns** the app may create links for, for example `/product/*`. A pattern
   is made of `/`-separated segments: a literal, `*` for exactly one segment, or `**` at the end
   for one or more. A route outside the list is refused.
3. Copy the **publishable key** (`pk_…`) and pass it as `clientKey`.

The publishable key is not a secret: it is safe to ship inside the app, because it can only
create links for the listed routes of this one application. **Never put a secret API key
(`lb_…`) into an app**; `createLink` does not accept one.

```kotlin
val strybok = Strybok(
    apiBaseUrl = "https://api.strybok.com",
    allowedHosts = setOf("my-app.strybok.link"),
    clientKey = "pk_…",
)

// In a coroutine, for example from the click handler of the Share button:
try {
    val link = strybok.createLink(
        routePath = "/product/42",
        payload = mapOf("productId" to 42), // optional
        title = "Red shoes", // optional, as are description and imageUrl
    )
    share(link.url) // your platform share sheet
} catch (error: StrybokException) {
    when (error) {
        is StrybokRateLimitedException, is StrybokNetworkException,
        is StrybokTimeoutException, is StrybokServerException,
        -> showMessage("Could not create the link, try again later")
        else -> println("Link creation is misconfigured: $error")
    }
}
```

The result is a `CreatedLink` with `url` (what the user shares), `slug`, `routePath` and
`linkId`. `payload` is a `Map<String, Any?>` of plain JSON values (`String`, `Boolean`, `Int`,
`Long`, `Double`, `null`, `List`, `Map` with `String` keys); a kotlinx.serialization `JsonObject`
works as well.

| Error                                                                            | Meaning                                                                                                                         | Retrying helps |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| `StrybokUnauthorizedException`                                                   | No `clientKey` configured, or the key is wrong or was replaced, or the feature is switched off. The service does not say which. | no             |
| `RouteNotAllowedException`                                                       | `routePath` is not covered by the route patterns in the dashboard.                                                              | no             |
| `StrybokValidationException`                                                     | The route, payload, title, description or image URL was rejected; see `message` and `issues`.                                   | no             |
| `StrybokRateLimitedException`                                                    | Too many requests, or the app's daily limit of new links is reached; `retryAfterSeconds` when the service says how long.        | later          |
| `StrybokNetworkException` / `StrybokTimeoutException` / `StrybokServerException` | No usable answer after all retries.                                                                                             | later          |

Things to know:

- Creating the same content again returns the same link, so repeated taps on "Share" do not pile
  up links, and the SDK can safely retry timeouts, network errors and 5xx answers (`maxRetries`,
  `retryDelay`, `timeout`). 4xx answers, including 429, are never retried.
- It runs only when you call it: `start()`, incoming links and `resolve` never wait for it. It
  works before `start()` and does not use `allowedHosts`. After `dispose()` it throws
  `IllegalStateException`.
- Errors are thrown to the caller, never given to `onError`, and never contain the key or the
  payload.
- Do not put personal data into the route or the payload: whoever receives the link can read
  them.
- From Swift, create the SDK with `Strybok(apiBaseUrl:allowedHosts:clientKey:onError:)` and call
  `try await strybok.createLink(routePath: "/product/42")`, or the full
  `createLink(routePath:payload:title:description:imageUrl:)` with `nil` for what you do not
  need.
- In tests, answer the `POST …/v1/public/links` request in your `transport` with
  `HttpResponse(201, """{"linkId":"l1","url":"https://my-app.strybok.link/abc","slug":"abc","routePath":"/product/42"}""")`.

### Extra protection

The publishable key can be copied out of your app, so anyone can create links for the routes you
allowed. If that matters to you, the owner of the application can ask Strybok to accept link
requests only — or preferably — from a **genuine build of your app on a genuine device**. The
proof is Apple **App Attest** on iOS and Google **Play Integrity** on Android; the JVM target
cannot attest. What this does and does not protect, and the dashboard side, are described in
[Keeping link creation from your app safe](./link-creation-security.md); read
[its limits](./link-creation-security.md#the-limits) before you rely on it.

It is off by default. The SDK does not depend on Google's libraries: an Android app for a market
without Google Play uses the same artifact and passes no token provider.

**Dashboard.** Open your application → **Setup** → **Links from the app** → **Extra protection
(optional)** and choose the mode: **Preferred** first; **Required** only after you have seen
your released apps pass. You can set up one platform only — with **Required**, apps on the other
platform then cannot create links at all.

**iOS (App Attest)**

1. In the dashboard, the application needs your **Apple Team ID** and **bundle ID** (the ones
   used for Universal Links).
2. In Xcode, select the iOS app target → **Signing & Capabilities** → **+ Capability** → **App
   Attest**. This adds the `com.apple.developer.devicecheck.appattest-environment` entitlement
   (`development` for builds run from Xcode, always `production` for TestFlight and the App
   Store).
3. While developing, tick **Accept development builds (iOS)** in the dashboard. Untick it before
   you rely on the protection.
4. Test on a **real device**: App Attest does not exist in the simulator.

Nothing else is needed in the app: no dependency, no key to configure. The SDK keeps the
identifier of the device's App Attest key (not a secret) in the app's own storage, under
`Library/Application Support/com.strybok.sdk/attestation`, excluded from backups.

**Android (Play Integrity)**

1. Dashboard: fill in the **package name** and the **SHA-256 fingerprints of your signing
   certificates** (with Play App Signing, the _app signing key_ certificate from Play Console →
   **Test and release** → **App integrity**, not your upload key).
2. [Google Cloud console](https://console.cloud.google.com/): choose or create a project and
   enable the **Google Play Integrity API**. Note the **project number** (a number, not the
   project id).
3. [Play Console](https://play.google.com/console/): your app → **Test and release** → **App
   integrity** → **Play Integrity API** → **link that Cloud project**.
4. Cloud console → **IAM & Admin** → **Service Accounts**: create a service account only for
   this purpose, with only the roles Google's Play Integrity documentation asks for, and create
   a **JSON key** for it.
5. Dashboard → **Android — Play Integrity** → **Upload Google key**, then delete the downloaded
   file.
6. Add Google's library to the Android source set yourself; the SDK does not pull it in:

```kotlin
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation("com.google.android.play:integrity:1.4.0") // or newer
        }
    }
}
```

7. Test with a build **installed from Google Play** (an internal testing track is enough). A
   build installed from Android Studio is not "recognised by Play" and is refused.

**Switch it on.** `attestation = true` is all iOS needs. Android additionally needs a token
provider, which is created in Android code because it needs a `Context`:

```kotlin
// commonMain
fun createStrybok(integrityTokenProvider: IntegrityTokenProvider? = null) = Strybok(
    apiBaseUrl = "https://api.strybok.com",
    allowedHosts = setOf("my-app.strybok.link"),
    clientKey = "pk_…",
    attestation = true,
    integrityTokenProvider = integrityTokenProvider, // Android only; ignored elsewhere
)

// androidMain, e.g. in Application.onCreate
val strybok = createStrybok(
    PlayIntegrityTokenProvider(
        context = applicationContext,
        cloudProjectNumber = 123456789012L, // the Cloud project number from step 2
    ),
)

// iosMain (or Swift: Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:onError:))
val strybok = createStrybok()
```

`createLink` is called exactly as before. The first call — and about one call an hour after
that — first asks the service for a one-time challenge, asks the platform for a proof bound to
it and exchanges that for a session token, which is kept in memory only. Nothing of this happens
in `start()` or when a link arrives, and never ahead of time: an app that never calls
`createLink` makes no attestation request and creates no App Attest key.

Two more errors can come out of `createLink`:

| Error                                 | Meaning                                                                                                                                                                                                                                                     | Retrying helps |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| `StrybokAttestationRequiredException` | The mode is **Required** and this request carried no proof: the device or build cannot attest (see the limits), or `attestation` is off. Expected on such devices — tell the user that sharing is not available here rather than show an error.             | no             |
| `StrybokAttestationFailedException`   | The service refused the session token, the SDK attested again, and the fresh token was refused too. `reason` is `invalid`, `expired` or `exhausted` when the service gave one. Should not happen in a correctly set-up release; check the dashboard set-up. | no             |

**When a device cannot attest** — a simulator, no Google Play services, no token provider,
Apple or Google not reachable, an incomplete dashboard set-up — the SDK does not fail on its
own. It sends the request without a proof and reports what the service answers: the link (mode
**Off**, or **Preferred** within the small shared allowance), `StrybokRateLimitedException`
(that allowance is used up) or `StrybokAttestationRequiredException` (mode **Required**). It
then waits 60 seconds before it tries to attest again. Attestation never affects receiving links
and never throws anything but the `StrybokException`s of `createLink`.

**Android without Google Play.** `IntegrityTokenProvider` is a small interface (given the
request hash, return a Play Integrity token or `null`). `PlayIntegrityTokenProvider` is the
ready-made implementation; it only works when the app itself adds
`com.google.android.play:integrity`, and reports "cannot attest" (with one warning in logcat)
when the library is missing. For a flavour that must not contain Google libraries, leave the
dependency out and pass no provider: nothing of Google is referenced or downloaded. An app that
already uses Play Integrity can implement the interface on top of its own code instead; the
request must be a **standard** request with the given `requestHash`.

**The limits, plainly:**

- **iOS: App Attest needs iOS 14 or later and a real device.** It does not work in the
  simulator and can be unavailable in app extensions and on some Macs running iOS apps.
- **Android: it needs Google Play services, the Play Store and a build installed from Google
  Play.** It does not work on devices without Google services — most devices sold in mainland
  China, Huawei devices without Google Mobile Services — or for sideloaded builds and builds
  from other stores.
- With mode **Required**, every user on such a device or build cannot create links at all.
- **Google limits how often you can ask**: 10,000 integrity requests a day per app by default; a
  higher tier has to be requested from Google. The SDK asks about once an hour per running app
  (more often when a token is used up after 30 new links).
- On Android, Strybok needs a **Google service-account key** of yours to read the verdicts.
- It raises the cost of abuse; it does not stop a real user of the real app, and a proof can be
  taken from a device an attacker controls.
- **This code has never run against Apple's or Google's real services or on a physical
  device.** The flow is covered by unit tests on the JVM, Android host and the iOS simulator
  with a fake App Attest service, a fake token provider and a fake Strybok service. The thin
  wrappers around `DCAppAttestService` and Google's library compile, and are otherwise
  unverified (the simulator only confirms that App Attest reports "not supported" there). Treat
  **Required** as experimental until you have seen your own released builds pass.

## Troubleshooting

| Symptom                                                                       | Check                                                                                                                                                                                                                   |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Link opens the browser/store instead of the app                               | Native association is not verified: see the [Android](./android.md) and [iOS](./ios.md) guides. The SDK is not involved yet at this point.                                                                              |
| App opens but nothing happens                                                 | Is the URL forwarded (steps 4 and 5)? Does `handleIntent` / `handleUrl` return `true` — if not, the host is missing from `allowedHosts`. Was `start()` called? Is anything collecting `links`? What does `onError` say? |
| Screen opens twice                                                            | Two `Strybok` instances, or you navigate from both `getInitialLink()` and your own handling of the intent/URL.                                                                                                          |
| The old link opens again after rotating the device                            | `handleIntent(intent)` is called in `onCreate` without the `savedInstanceState == null` check.                                                                                                                          |
| A second activity appears for a link while the app is open                    | The activity is not `singleTop` (or `singleTask`), so `onNewIntent` is not used.                                                                                                                                        |
| `Strybok` is not visible in Swift                                             | The framework does not export the library: `export("com.strybok:strybok-kmp:0.3.0")` together with an `api(...)` dependency.                                                                                            |
| Gradle: "plugin ... was not found" for the module                             | Declare both plugins with versions (`apply false`) in the root build file (step 2).                                                                                                                                     |
| `IllegalStateException: Module with the Main dispatcher...` in JVM unit tests | Pass `coroutineContext` (a test dispatcher) as in step 10; Android's main thread does not exist in host tests.                                                                                                          |
| Works on tap in Notes/Messages, not from Instagram/Telegram                   | In-app browsers often do not hand links to apps. Users get the fallback page with a manual button.                                                                                                                      |
