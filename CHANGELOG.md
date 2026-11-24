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
