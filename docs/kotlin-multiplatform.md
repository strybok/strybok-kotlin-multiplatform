# Kotlin Multiplatform integration

The `strybok-kmp` library is for Kotlin Multiplatform and Compose Multiplatform apps on Android
and iOS. Your app hands it the URL the OS opened it with; it asks the Strybok API which in-app
route the link stands for, and gives your shared code each link exactly once.

> **Status:** covered by unit tests on the JVM, Android host tests and the iOS simulator
> (Kotlin/Native), and the Swift snippets below type-check against the generated framework.
> Early release (0.1.x), published on Maven Central. **Not verified on a physical device** (see
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
            export("com.strybok:strybok-kmp:0.1.0")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("com.strybok:strybok-kmp:0.1.0")
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
| `dispose()`          | Stop for good: nothing is delivered afterwards, collectors complete.                                  |

Options (Kotlin named arguments): `timeout` (5 s per attempt), `maxRetries` (2; only timeouts,
network errors and 5xx are retried), `retryDelay` (400 ms, doubles per retry), `dedupeWindow`
(2 s), `reportAppOpens` (`true`), `onError`, plus `linkSource`, `transport` and
`coroutineContext` for tests and custom setups. Swift sees a short constructor
`Strybok(apiBaseUrl:allowedHosts:onError:)` with all defaults; configure anything else in Kotlin.

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
the `url` being handled):

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

## Troubleshooting

| Symptom                                                                       | Check                                                                                                                                                                                                                   |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Link opens the browser/store instead of the app                               | Native association is not verified: see the [Android](./android.md) and [iOS](./ios.md) guides. The SDK is not involved yet at this point.                                                                              |
| App opens but nothing happens                                                 | Is the URL forwarded (steps 4 and 5)? Does `handleIntent` / `handleUrl` return `true` — if not, the host is missing from `allowedHosts`. Was `start()` called? Is anything collecting `links`? What does `onError` say? |
| Screen opens twice                                                            | Two `Strybok` instances, or you navigate from both `getInitialLink()` and your own handling of the intent/URL.                                                                                                          |
| The old link opens again after rotating the device                            | `handleIntent(intent)` is called in `onCreate` without the `savedInstanceState == null` check.                                                                                                                          |
| A second activity appears for a link while the app is open                    | The activity is not `singleTop` (or `singleTask`), so `onNewIntent` is not used.                                                                                                                                        |
| `Strybok` is not visible in Swift                                             | The framework does not export the library: `export("com.strybok:strybok-kmp:0.1.0")` together with an `api(...)` dependency.                                                                                            |
| Gradle: "plugin ... was not found" for the module                             | Declare both plugins with versions (`apply false`) in the root build file (step 2).                                                                                                                                     |
| `IllegalStateException: Module with the Main dispatcher...` in JVM unit tests | Pass `coroutineContext` (a test dispatcher) as in step 10; Android's main thread does not exist in host tests.                                                                                                          |
| Works on tap in Notes/Messages, not from Instagram/Telegram                   | In-app browsers often do not hand links to apps. Users get the fallback page with a manual button.                                                                                                                      |
