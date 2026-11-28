## 0.5.0

- Links without an API call, optional and off by default: the new constructor argument (and
  property) `parameterLinks` (`ParameterLinks(payloadKeys)`) delivers path links
  (`https://<host>/product/42?id=7`) and Firebase long links (`https://<host>/?link=…&apn=…&ibi=…`)
  as well. Take the keys from `parameterLinks.payloadKeys` of the integration guide
  (`GET /v1/apps/:appId/integration`). Swift gets
  `Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:deferredLinks:allowProbabilisticMatching:parameterLinks:onError:)`.
- With the option a URL of an allowed host is also ours when its path is a route path (checked
  as received: never decoded or normalised; no `//`, no dot segments, nothing reserved such as
  `/.well-known/…` or `/favicon.ico`), or `/` (also an empty path) with a `link` parameter that
  is an `http(s)` deep link. Resolving then also sends the first value of each configured key
  (1–256 UTF-16 code units without control characters, re-encoded strictly), and for a long link
  the deep link rebuilt from its scheme, host and path with its own values of those keys, plus
  `apn` and `ibi`. Nothing else of the query and never the fragment. The rules are common code,
  identical on Android, iOS and the JVM, including a strict UTF-8 decoder of their own.
- The delivered link's `routePath` and `payload` are the service's answer, never the tapped URL;
  `app_open` carries the `linkId` the service returned. `isStrybokLink`, `handleLink` (and so
  `handleIntent`, `handleUrl`, `handleUserActivity`) and `resolve` follow the option.
- `ParameterLinks` throws `IllegalArgumentException`, like an invalid `apiBaseUrl`, for more than
  20 keys, a key twice, a key that is not 1–40 characters of `A–Z a–z 0–9 _ . -`, a `utm_…` key,
  or `st`, `sd`, `si`, `strybok_click`. The keys of the integration guide always pass.
- Without the option every resolve request is exactly what 0.4.0 sent. Deferred links keep the
  single-slug rule with or without it: the pasted text of `redeemPastedLink`, and the `url` of a
  redeem answer (Android referrer and iOS pasted link) or a match answer.
- The common tests run every case of the shared test vectors of the SDK contract (each lookup
  through `resolve` and through `handleLink` and `start`, each configuration through
  `ParameterLinks`) on the JVM, the Android host and the iOS simulator. The build turns the
  byte-identical copy in `src/commonTest/resources` into a Kotlin constant
  (`generateParameterLinkVectors`), because Kotlin/Native test binaries have no resources; a JVM
  test checks that the constant is the file unchanged.
- **Unit-tested only (JVM, Android host, iOS simulator), never run on a device** or against the
  production API.

## 0.4.0

- Campaign values. `ResolvedDeepLink.campaign` (`LinkCampaign`: `source`, `medium`, `campaign`,
  `term`, `content`, each `String?`; `isEmpty`; `LinkCampaign.EMPTY`) holds the link's default
  campaign values overridden by the `utm_*` parameters of the tapped URL, as the service returned
  them. It is never `null`. The values are untrusted labels chosen by whoever shared the link.
- The resolve request now carries the five parameters `utm_source`, `utm_medium`, `utm_campaign`,
  `utm_term` and `utm_content` of the tapped URL — exact lower-case names, the first occurrence
  of each, percent-decoded, and only values of 1–64 characters out of `A–Z a–z 0–9 . _ ~ -`, in
  a fixed order. Every other query parameter and the fragment are still never sent, and a URL
  without valid campaign parameters produces exactly the request of earlier versions. The filter
  is common code, identical on Android, iOS and the JVM.
- The `app_open` event reports `campaign` exactly as resolve returned it and leaves it out when
  there is none; it is never built from the URL.
- Which URLs are accepted, duplicate detection (the whole URL string as received) and everything
  else are unchanged.
- Deferred links, optional and off by default: restore a link that was tapped before the app was
  installed. New constructor arguments `deferredLinks`, `installReferrerSources`,
  `allowProbabilisticMatching` and `platformContext`; `enableDeferredLinks()` switches the
  option on later, for example after a consent screen. A deferred link is delivered through the
  normal pipeline with the new `ResolvedDeepLink.isDeferred` set, never from `getInitialLink`,
  without an `app_open` event, and nothing about it is reported to `onError`. The flows are
  common code; the JVM target ignores the options.
- Android: after the first `start()` the SDK asks the stores for their install referrer,
  exchanges the click id found in it (`POST /v1/public/deferred-links/redeem`: the id, the
  package name and the SDK version, nothing else) and delivers the link. A normal link of the
  app that arrives first — also while the request is in flight — wins. At most once per
  installation: `attempts` and `done` are kept in the app's no-backup storage, an attempt is
  counted before a source is asked, three launches are the limit, `done` is stored before
  delivery, and an app that was updated since it was installed is not asked. Needs
  `platformContext` (the new `PlatformContext` type, which is `android.content.Context` on
  Android).
- Android sources, **without a new dependency**: `InstallReferrerSource` is a one-function
  interface (`InstallReferrerResult.Referrer`, `UnavailableForNow`, `Unavailable`).
  `PlayInstallReferrerSource` and `GalaxyStoreInstallReferrerSource` are compiled against
  `com.android.installreferrer:installreferrer` and
  `store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer` with
  `compileOnly`; `AppGalleryInstallReferrerSource` queries AppGallery's content provider and
  needs no library. An app adds the library of each store it uses; a source whose library is
  missing answers "unavailable". The consumer R8 rules cover the missing classes.
- iOS, flow A: `redeemPastedLink(text)` takes a string the person pasted and answers with a
  `PastedLinkResult` (`DELIVERED`, `NOT_OURS`, `NOT_FOUND`, `ALREADY_HANDLED`, `DISABLED`,
  `FAILED`). **The SDK never reads the pasteboard**; only the click id, the bundle identifier
  and the SDK version are sent, and a string that is not a copied link of the app is dropped
  without a request. `pasteboardMayContainLink()` uses `UIPasteboard.detectPatterns`, which
  hands over no content. A test of the SDK fails when any content-reading pasteboard call
  appears in the iOS sources.
- iOS, flow B, only with `allowProbabilisticMatching` in addition to `deferredLinks`: on the
  first launch the service is asked to guess the link (`POST /v1/public/deferred-links/match`:
  bundle identifier, device family, first preferred language, SDK version, nothing else). **This
  is fingerprinting in Apple's terms and forbidden by the App Store Review Guidelines**; it is
  off by default and not recommended. Such a link has `ResolvedDeepLink.isProbabilisticMatch`
  set and can be another person's. While the pasteboard may hold a link the SDK waits
  (`AWAITING_PASTE`) for `redeemPastedLink` or the new `skipPastedLink()`.
- `deferredLinkStatus` (`StateFlow<DeferredLinkStatus>`: `DISABLED`, `CHECKING`, `PENDING`,
  `AWAITING_PASTE`, `NOT_FIRST_LAUNCH`, `UNAVAILABLE`, `NO_REFERRER`, `NOT_FOUND`, `SUPERSEDED`,
  `DELIVERED`, `FAILED`; `isFinal`) and `addDeferredLinkStatusListener` for Swift. Swift also
  gets the constructor
  `Strybok(apiBaseUrl:allowedHosts:clientKey:attestation:deferredLinks:allowProbabilisticMatching:onError:)`.
- Not verified: nothing of deferred links has run on a device, against a real store or with a
  real pasteboard. The Galaxy Store and AppGallery sources are unverified altogether; the
  manifest entries AppGallery may need are documented, not added.

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
