# Kotlin Multiplatform integration

The `strybok-kmp` library is for Kotlin Multiplatform and Compose Multiplatform apps on Android
and iOS. Your app hands it the URL the OS opened it with; it asks the Strybok API which in-app
route the link stands for, and gives your shared code each link exactly once.

> **Status:** covered by unit tests on the JVM, Android host tests and the iOS simulator
> (Kotlin/Native), and the Swift snippets below type-check against the generated framework.
> Early release (0.4.x), published on Maven Central. **Not verified on a physical device** (see
> the [manual test plan](./manual-test-plan.md)) and not tried inside a complete Compose
> Multiplatform app.
>
> Restoring a link that was tapped before the app was installed ("deferred links") is
> **optional and off by default**: automatic on Android through the store's install referrer;
> on iOS there is **no automatic restore** — only a paste step, or a guess that Apple forbids.
> None of it has run on a device. See [Deferred links](#12-deferred-links-optional).

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
            export("com.strybok:strybok-kmp:0.4.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.4.0")
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

| Member                   | Purpose                                                                                                                                       |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `start()`                | Suspends until the launch link was handled; later links keep arriving. Idempotent.                                                            |
| `links`                  | `Flow<ResolvedDeepLink>` (`url`, `linkId`, `appId`, `routePath`, `payload`, `payloadJson`, `campaign`, `isDeferred`, `isProbabilisticMatch`). |
| `addListener { }`        | Callback version of `links`; returns a `Subscription` with `cancel()`.                                                                        |
| `getInitialLink()`       | The launch link, if not delivered through `links` / a listener yet. At most once.                                                             |
| `resolve(url)`           | Resolve a URL explicitly (QR scanner, tests). Does not emit on `links` or report an app open.                                                 |
| `isStrybokLink(url)`     | Whether a URL is one of this app's links.                                                                                                     |
| `handleLink(url)`        | Hand over a URL string yourself; `handleIntent` / `handleUrl` / `handleUserActivity` are built on it.                                         |
| `createLink(…)`          | Make a short link to a screen of the app. See [Create a link to share](#11-create-a-link-to-share).                                           |
| `enableDeferredLinks()`  | Switch deferred links on later, for example after a consent screen. See [Deferred links](#12-deferred-links-optional).                        |
| `deferredLinkStatus`     | `StateFlow<DeferredLinkStatus>`: where the deferred-link check stands. Also `addDeferredLinkStatusListener { }`.                              |
| `redeemPastedLink(text)` | iOS: hand over a string the person pasted; answers with a `PastedLinkResult`. Also `pasteboardMayContainLink()` and `skipPastedLink()`.       |
| `dispose()`              | Stop for good: nothing is delivered afterwards, collectors complete.                                                                          |

Options (Kotlin named arguments): `timeout` (5 s per attempt), `maxRetries` (2; only timeouts,
network errors and 5xx are retried), `retryDelay` (400 ms, doubles per retry), `dedupeWindow`
(2 s), `reportAppOpens` (`true`), `onError`, `clientKey` (only for `createLink`), `attestation`
and `integrityTokenProvider` (only for `createLink`, see
[Extra protection](#extra-protection)), `deferredLinks`, `installReferrerSources`,
`allowProbabilisticMatching` and `platformContext` (see
[Deferred links](#12-deferred-links-optional)), plus `linkSource`, `transport` and
`coroutineContext` for tests and custom setups. Swift sees four short constructors with all
defaults, `Strybok(apiBaseUrl:allowedHosts:onError:)`,
`Strybok(apiBaseUrl:allowedHosts:clientKey:onError:)`,
`Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:onError:)` and
`Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:deferredLinks:allowProbabilisticMatching:onError:)`;
configure anything else in Kotlin.

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

Only the link's host and slug are sent to `resolve`, plus the five `utm_*` campaign parameters of
the tapped URL when they are valid (see below); every other query parameter and the fragment
stay on the device. On iOS the SDK uses an ephemeral `NSURLSession` (no cookies or cache on disk).
`createLink` sends the client key and what you pass to it (route, payload, title, description,
image URL), and only when your app calls it. With `deferredLinks` switched on, the SDK also
sends what [Deferred links](#12-deferred-links-optional) lists: on Android the code found in
the store's install referrer, on iOS the code of a link the person pasted — and, only with
`allowProbabilisticMatching`, the device family and first language, from which the service
identifies the device together with its network address.

### Campaign values

A resolved link has `campaign` (`LinkCampaign`): the `utm_source`, `utm_medium`, `utm_campaign`,
`utm_term` and `utm_content` of this open as `source`, `medium`, `campaign`, `term` and
`content`. It is never `null`; a property without a value is `null` (`nil` in Swift), and
`campaign.isEmpty` is `true` when there is none at all.

```kotlin
strybok.links.collect { link ->
    link.campaign.source?.let { analytics.setUserProperty("link_source", it) } // a label, nothing more
    navigate(link.routePath)
}
```

- **Where the values come from.** The link's defaults — set in the dashboard (link form →
  "Campaign (optional)") or through the management API — with the `utm_*` parameters of the
  tapped URL on top, key by key. `https://my-app.strybok.link/invite777?utm_source=instagram`
  opens with source `instagram` whatever the default is; a link tapped without parameters gives
  the defaults. A URL can replace a default but cannot clear it.
- **Untrusted labels.** Whoever shares a link can write any `utm_*` value into its URL. Use the
  values as labels for your own analytics; never decide access, prices, rewards or navigation by
  them.
- **What is accepted.** Only these five lower-case names, and only values of 1–64 characters
  out of `A–Z a–z 0–9 . _ ~ -`. Anything else (`spring sale`, an e-mail address, non-Latin
  letters) is dropped silently, as if the parameter were absent — by the SDK and again by the
  server.
- **What is sent.** With the resolve request the SDK sends those five parameters when they are
  valid, and nothing else of the query and never the fragment. With the `app_open` event it
  reports the values exactly as resolve returned them. The dashboard's link analytics then
  shows a "Campaigns" breakdown by source, medium and campaign, with app opens and browser opens
  counted separately (term and content are not broken down).
- **Versions.** New in 0.4.0. Older versions send no query: their links carry no `campaign` and
  their app opens are counted under the link's defaults.

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

## 12. Deferred links (optional)

A person without your app taps a link, installs the app and opens it. "Deferred links" is
what happens next. It is **off by default** — in the dashboard and in the SDK — and it is very
different on the two platforms:

| Platform | What the SDK can do                                                                                                                                        |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android  | Automatic, through the store's install referrer: the link arrives about a second after the first start.                                                    |
| iOS      | **Nothing automatic that Apple allows.** Two opt-in flows with real costs: the person pastes the link (A), or the service guesses (B, forbidden by Apple). |
| JVM      | Nothing; the options are ignored.                                                                                                                          |

The exact behaviour is in the SDK contract:
[Android](./sdk-contract.md#deferred-links-optional-android),
[iOS](./sdk-contract.md#deferred-links-on-ios-optional).

> **Nothing here has run on a device, against a real store or with a real pasteboard.** The
> flows are common code, covered by unit tests on the JVM, Android host and the iOS simulator
> with fake sources, a fake store and a fake service. On Android the Google Play source is
> written from Google's documentation, and **the Samsung Galaxy Store and Huawei AppGallery
> sources are unverified**. The Swift snippets in this section were written from the Kotlin
> signatures and have **not** been compiled.

A deferred link is delivered through `links` like any other, with `isDeferred == true`. It is
never the launch link: `start()`, `getInitialLink()` and `createLink` never wait for it and
`getInitialLink()` never returns it. No `app_open` event is sent for it, and nothing about
deferred links is ever reported to `onError`.

### Android

What the service does for this, where the code is added to your store URLs and everything it
cannot do is in the
[Android setup guide](./android.md#links-tapped-before-the-app-is-installed-deferred-links).

1. Dashboard → your application → **When a link opens in a browser** → switch on **Restore the
   link after install (Android only)**. The Android package name must be set.
2. In the **Android app module**, add the library of each store you publish in. The SDK
   compiles against them but does not bring them along:

   | Source                             | Store                | Add to the app                                                                                                      | Standing                                                                                                                                                                                      |
   | ---------------------------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `PlayInstallReferrerSource`        | Google Play          | `implementation("com.android.installreferrer:installreferrer:2.2")`                                                 | Written from Google's documentation. Not device-tested                                                                                                                                        |
   | `GalaxyStoreInstallReferrerSource` | Samsung Galaxy Store | `implementation("store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0")` (Maven Central) | **Unverified.** Written from the classes of Samsung's library; Galaxy Store 4.5.20.1 or later. Whether the referrer of an `https` store link reaches the app is known from third parties only |
   | `AppGalleryInstallReferrerSource`  | Huawei AppGallery    | No library. Possibly the two manifest entries below — **unverified**                                                | **Unverified.** Written from Huawei's AppGallery Connect pages; AppGallery 10.5.0.300 or later. Huawei asks for the user's consent first                                                      |

   A source whose library is missing answers "unavailable" and the next one is asked.

3. Give the SDK the application `Context` and the sources. Both exist only on Android, so pass
   them into your shared code from the Android side:

```kotlin
// shared/src/commonMain/kotlin/AppLinks.kt
import com.strybok.kmp.InstallReferrerSource
import com.strybok.kmp.PlatformContext
import com.strybok.kmp.Strybok

object AppLinks {
    lateinit var strybok: Strybok
        private set

    /** Android passes its context and sources; iOS calls `create()` with the defaults. */
    fun create(platformContext: PlatformContext? = null, sources: List<InstallReferrerSource> = emptyList()) {
        strybok = Strybok(
            apiBaseUrl = "https://api.strybok.com",
            allowedHosts = setOf("my-app.strybok.link"),
            deferredLinks = true,
            installReferrerSources = sources,
            platformContext = platformContext,
        )
    }
}
```

```kotlin
// androidApp: Application.onCreate
AppLinks.create(
    platformContext = applicationContext,
    sources = listOf(
        PlayInstallReferrerSource(applicationContext),
        GalaxyStoreInstallReferrerSource(applicationContext),
        AppGalleryInstallReferrerSource(applicationContext),
    ),
)
```

`PlatformContext` **is** `android.content.Context` on Android; on iOS and the JVM it cannot be
created and stays `null`. On Android, `deferredLinks = true` without a context is refused with
an `IllegalArgumentException`.

What happens: after `start()` has done everything else, and at most once per process, the SDK
asks the sources in order. When a referrer carries a code (`strybok_click=dc_…`), the SDK sends
that code, the package name and the SDK version to `POST /v1/public/deferred-links/redeem` —
nothing about the device and nothing else from the referrer — and delivers the link it gets
back. The referrer and the code are never logged or stored.

- If a normal link of your app arrives first — the launch link, or one tapped while the check
  is running — the normal link wins and the deferred one is dropped.
- The SDK keeps two values in the app's private no-backup storage: how many launches tried,
  and whether the check is finished. A launch that cannot finish leaves the check for the next
  launch; after **three** launches it gives up. A launch that crashes during the check has used
  one of the three. A link is delivered **at most once**.
- An app that was **updated** since it was installed is never asked.
- A source may take 10 seconds; after that it is abandoned for this launch.

**Huawei AppGallery: manifest entries that may be needed.** Huawei's page does not say whether
an app that targets Android 11 or later must declare that it wants to see AppGallery's content
provider, or hold a permission to read it, and this has not been tried. The SDK adds
**nothing** to your manifest. If `AppGalleryInstallReferrerSource` answers "unavailable" on a
device that has AppGallery, try:

```xml
<manifest>
    <queries>
        <provider android:authorities="com.huawei.appmarket.commondata" />
    </queries>
    <uses-permission android:name="com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA" />
</manifest>
```

**Another store, or your own code.** `InstallReferrerSource` is a one-function interface:
`installReferrer()` is a `suspend` function called away from the main thread that returns
`InstallReferrerResult.Referrer(string)`, `InstallReferrerResult.UnavailableForNow` (try again
in a later launch) or `InstallReferrerResult.Unavailable` (asking again is pointless). It must
not throw and must close what it opened, also when it is cancelled.

**The limits on Android, plainly:**

- It works **only when the app is installed from the store page the link sent the person to**
  (the store _app_, not the store's website, not after a search, not from an APK), **within 24
  hours** of the tap, **once**.
- **Huawei AppGallery and Samsung Galaxy Store have never been verified**, not even that the
  stores pass the value on.
- **None of it has run on a device.** The calls into Google's and Samsung's libraries compile
  against them and are otherwise untested.

### iOS

The App Store passes nothing from a link to a newly installed app, so **there is no automatic
deferred link on iOS that Apple allows**. `deferredLinks = true` does nothing by itself there.
Read the [iOS guide](./ios.md#links-tapped-before-the-app-is-installed) first: it recommends
Apple's own mechanisms before either of the two flows below, and lists what each flow costs
your users and you.

**Flow A — the person pastes the link** (dashboard option **Copy and paste the link**). Before
the App Store, iOS visitors see a page with a **Copy link and get the app** button. In your
app, on the first screen or in onboarding, you show a paste button and hand what the person
pasted to the SDK:

```swift
// SwiftUI, iOS 16 or later. The system paste button is the person's consent: no alert appears.
PasteButton(payloadType: String.self) { strings in
    guard let text = strings.first else { return }
    Task {
        let result = try? await strybok.redeemPastedLink(text: text)
        if result == .notOurs { /* say that there was nothing to continue with */ }
    }
}
```

`redeemPastedLink` answers with one of:

| Result            | Meaning                                                                                                         |
| ----------------- | --------------------------------------------------------------------------------------------------------------- |
| `DELIVERED`       | The link was delivered through `links`, also when a normal link was delivered earlier in this launch.           |
| `NOT_OURS`        | The text is not a copied link of your app. No request was made; the text is dropped, not stored and not logged. |
| `NOT_FOUND`       | The service refused the link: unknown, older than 24 hours, already used, or another app's.                     |
| `ALREADY_HANDLED` | A deferred link was already handled on this installation. No request was made.                                  |
| `DISABLED`        | `deferredLinks` is off (or this is not iOS). No request was made.                                               |
| `FAILED`          | No usable answer. After a network failure the person can tap Paste again.                                       |

- **The SDK never reads the pasteboard.** It receives the string from your paste button, sends
  only the code found in it, your bundle identifier and the SDK version to
  `POST /v1/public/deferred-links/redeem`, and drops the string.
- `pasteboardMayContainLink()` tells you whether showing the button is worth it. It uses
  `UIPasteboard.detectPatterns`, which answers "this looks like a web URL" without handing over
  the content and without a banner or alert; it cannot know whether the URL is yours.
- Place the button where a sentence can say what it does, describe it in your App Review notes,
  and do not paste automatically.
- What it costs: **every** iOS visitor without the app gets one extra page and tap before the
  App Store; it works only if the person taps copy there and Paste in your app; it replaces
  what was on their clipboard; the code works once, for 24 hours.

**Flow B — probabilistic matching: forbidden by Apple, not recommended.** With
`allowProbabilisticMatching = true` **in addition to** `deferredLinks = true` (and the owner's
switch in the dashboard, and each link allowed individually), the SDK asks the service on the
first launch to **guess** which link the person tapped. It sends the bundle identifier, the
device family (`iphone`, `ipad` or `ipod`), the first preferred language and the SDK version —
no identifier, no device model or name, no screen size, no OS version, no time zone — and the
service uses the network address of the connection.

- **This is fingerprinting in Apple's terms.** The App Store Review Guidelines and the Apple
  Developer Program License Agreement forbid deriving data from a device to identify it, with
  or without the user's consent. If you switch this on, the App Review and legal risk is yours.
- **A match is a guess and can be another person's link** (shared Wi-Fi, an office, carrier
  NAT). Every link has `isProbabilisticMatch`; **check it**, and never let such a link by
  itself sign anyone in, accept an invitation, apply a personal discount or show personal
  content.
- It often cannot work at all (iCloud Private Relay, a VPN, a change of network), it makes
  Strybok process your visitors' IP addresses, and nobody knows how accurate it is. The
  [iOS guide](./ios.md#option-not-recommended-probabilistic-matching-iosdeferredmatching) has
  all of it.
- When the pasteboard may hold a link, the SDK does not guess yet: the status is
  `AWAITING_PASTE` until your app calls `redeemPastedLink` (a result of `NOT_OURS` then lets
  the matching start) or `skipPastedLink()` when the person leaves the screen with the paste
  button. A pasted link always wins over a guess.
- A normal link of your app that arrives first wins here too, three launches are the limit as
  on Android, and a match is used up by the request that gets it.

On iOS the two stored values are files in the app's own storage
(`Library/Application Support/com.strybok.sdk/deferred`, excluded from backups), like the App
Attest key identifier.

Swift callers have the short constructor
`Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:deferredLinks:allowProbabilisticMatching:onError:)`.

### Consent, and the status

Reading a store's install referrer is access to information stored on the device, and flow B
is processing of personal data. Whether your app must ask first is for you to decide. If it
does, leave `deferredLinks` off and call `strybok.enableDeferredLinks()` once the person agreed:
the automatic part then runs once. Until then the SDK reads, sends and stores nothing.

For development and QA, `strybok.deferredLinkStatus` is a `StateFlow<DeferredLinkStatus>`
(`addDeferredLinkStatusListener { }` is the callback form):

| Status             | Meaning                                                                                                                      |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| `DISABLED`         | `deferredLinks` is off, or the target has no deferred links (JVM).                                                           |
| `CHECKING`         | The automatic check is running in this launch (or waits for `start()`).                                                      |
| `PENDING`          | This launch could not finish the check; a later launch tries again.                                                          |
| `AWAITING_PASTE`   | iOS: nothing happens until the app calls `redeemPastedLink` or `skipPastedLink()`. With flow A alone this is the rest state. |
| `NOT_FIRST_LAUNCH` | A deferred link was handled in an earlier launch, or the Android app was updated since it was installed. Final.              |
| `UNAVAILABLE`      | Android: no source is configured or none can ever answer. iOS flow B: not an iPhone, iPad or iPod. Final.                    |
| `NO_REFERRER`      | Android: the store returned a referrer without a code of ours — the usual result of an ordinary install. Final.              |
| `NOT_FOUND`        | The service refused the code, or (flow B) nothing matched or more than one did. Final.                                       |
| `SUPERSEDED`       | A normal link of your app arrived first. Final.                                                                              |
| `DELIVERED`        | The link was handed to `links`. Final.                                                                                       |
| `FAILED`           | Three launches could not finish the check, or the answer of the service was unusable. Final.                                 |

To run the automatic check again while developing, clear the app's data (Android) or delete the
app (iOS).

## Troubleshooting

| Symptom                                                                       | Check                                                                                                                                                                                                                   |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Link opens the browser/store instead of the app                               | Native association is not verified: see the [Android](./android.md) and [iOS](./ios.md) guides. The SDK is not involved yet at this point.                                                                              |
| App opens but nothing happens                                                 | Is the URL forwarded (steps 4 and 5)? Does `handleIntent` / `handleUrl` return `true` — if not, the host is missing from `allowedHosts`. Was `start()` called? Is anything collecting `links`? What does `onError` say? |
| Screen opens twice                                                            | Two `Strybok` instances, or you navigate from both `getInitialLink()` and your own handling of the intent/URL.                                                                                                          |
| The old link opens again after rotating the device                            | `handleIntent(intent)` is called in `onCreate` without the `savedInstanceState == null` check.                                                                                                                          |
| A second activity appears for a link while the app is open                    | The activity is not `singleTop` (or `singleTask`), so `onNewIntent` is not used.                                                                                                                                        |
| `Strybok` is not visible in Swift                                             | The framework does not export the library: `export("com.strybok:strybok-kmp:0.4.0")` together with an `api(...)` dependency.                                                                                            |
| Gradle: "plugin ... was not found" for the module                             | Declare both plugins with versions (`apply false`) in the root build file (step 2).                                                                                                                                     |
| `IllegalStateException: Module with the Main dispatcher...` in JVM unit tests | Pass `coroutineContext` (a test dispatcher) as in step 10; Android's main thread does not exist in host tests.                                                                                                          |
| Works on tap in Notes/Messages, not from Instagram/Telegram                   | In-app browsers often do not hand links to apps. Users get the fallback page with a manual button.                                                                                                                      |
