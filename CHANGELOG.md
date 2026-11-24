## 0.3.0

- Optional attestation of `createLink` ("Extra protection"), off by default: with
  `attestation = true` the SDK proves to the service that a request comes from a genuine build
  of the app on a genuine device — Apple App Attest (`DCAppAttestService`) on iOS, the Google
  Play Integrity standard API on Android. The JVM target cannot attest. The proof is made lazily
  inside `createLink` (challenge → platform proof → session token in the `X-Strybok-Attestation`
  header), never in `start()`; the token is kept in memory until 60 seconds before it expires,
  concurrent calls share one attestation, and a refused token is replaced once.
- iOS: the identifier of the device's App Attest key is kept per API host and client key in the
  app's own storage (`Library/Application Support/com.strybok.sdk/attestation`, excluded from
  backups). A key the service or the device no longer knows is replaced once; a key Apple could
  not be asked about is tried again later. Swift gets
  `Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:onError:)`.
- Android: **no new dependency.** `IntegrityTokenProvider` is a one-method interface, passed as
  the new `integrityTokenProvider` argument; `PlayIntegrityTokenProvider` (a context and the
  Cloud project number) is the ready-made implementation and is compiled against
  `com.google.android.play:integrity` with `compileOnly`. Apps that want attestation add that
  library themselves; without it the provider reports "cannot attest". The published POMs and
  module metadata are unchanged. The Android AAR now ships a consumer R8 rule (`-dontwarn` for
  the two Google packages) so apps without the library still build.
- A device that cannot attest (simulator, no Google Play services, a sideloaded build, no
  provider) sends the request without a proof and gets the service's answer; the SDK tries again
  after 60 seconds at the earliest.
- New errors, thrown by `createLink` only: `StrybokAttestationRequiredException` (the owner only
  accepts attested apps; also reported when attestation is off) and
  `StrybokAttestationFailedException` (`reason`). They extend the sealed `StrybokException`, so
  an exhaustive `when` over it needs branches for them (or an `else`). Both were
  `StrybokUnauthorizedException` in 0.2.0.
- Not verified against Apple's or Google's real services or on a device: covered by unit tests
  with fakes on the JVM, Android host and the iOS simulator, where App Attest itself does not
  exist.

## 0.2.0

- `createLink(routePath, payload, title, description, imageUrl)` asks the service for a short
  link to a screen of the app (the "Share" button) and returns a `CreatedLink` (`url`, `slug`,
  `routePath`, `linkId`). It runs only when called, never as part of `start`, and is retried
  like `resolve` (creating is idempotent; 4xx answers, including 429, are not retried). A
  `createLink(routePath)` overload exists for Swift.
- New constructor argument `clientKey`: the application's publishable `pk_…` key from the
  dashboard. Only `createLink` needs it. Swift gets
  `Strybok(apiBaseUrl:allowedHosts:clientKey:onError:)`.
- New errors, thrown by `createLink` only: `StrybokUnauthorizedException`,
  `RouteNotAllowedException` (`routePath`), `StrybokRateLimitedException` (`retryAfterSeconds`)
  and `StrybokValidationException` (`issues`). They extend the sealed `StrybokException`, so an
  exhaustive `when` over it needs branches for them (or an `else`).
- `HttpResponse` has `headers` and `header(name)`; the two-argument constructor still exists.
  Both platform transports (`HttpURLConnection`, `NSURLSession`) fill them. A custom transport
  only has to pass headers on if the app wants `retryAfterSeconds`.

## 0.1.0

- Initial version: `Strybok` with `start`, `links` (Flow), `addListener` (callback),
  `getInitialLink`, `resolve`, `isStrybokLink`, `handleLink`, `dispose`; strict host allowlist
  and URL validation; duplicate-delivery protection; timeout and retry with backoff; sealed
  `StrybokException` hierarchy; best-effort `app_open` events.
- Platform glue: `handleIntent(Intent)` on Android, `handleUrl(NSURL)` and
  `handleUserActivity(NSUserActivity)` on iOS.
- Replaceable `LinkSource` and `HttpTransport`; `FakeLinkSource` test helper.
