## 0.1.0

- Initial version: `Strybok` with `start`, `links` (Flow), `addListener` (callback),
  `getInitialLink`, `resolve`, `isStrybokLink`, `handleLink`, `dispose`; strict host allowlist
  and URL validation; duplicate-delivery protection; timeout and retry with backoff; sealed
  `StrybokException` hierarchy; best-effort `app_open` events.
- Platform glue: `handleIntent(Intent)` on Android, `handleUrl(NSURL)` and
  `handleUserActivity(NSUserActivity)` on iOS.
- Replaceable `LinkSource` and `HttpTransport`; `FakeLinkSource` test helper.
