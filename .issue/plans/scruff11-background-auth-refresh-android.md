## Goal

Switch native Android location posting away from fragile session-cookie auth so foreground GPS, periodic background updates, geofence posts, and geofence refresh fetches can survive normal app idleness.

## Evidence

- Native senders (`ForegroundLocationManager`, `LocationWorker`, `GeofenceBroadcastReceiver`, `GeofenceManager`) all still call `LocationTrackingService.resolveSessionCookie(...)`.
- Prod shows recent Android location traffic only as `map-watch`; there are no modern `foreground-gps`, `background`, or `geofence` rows.
- The Android integration plan already documents that session cookies are the wrong long-term auth mechanism for background HTTP.

## Plan

1. Add storage and bridge plumbing for a dedicated native location token alongside the existing person UUID and session-cookie fallback.
2. Update native location/geofence senders to prefer the native token and only fall back to cookies where needed during transition.
3. Push the token from the WebView/native bridge on authenticated page load so the app refreshes it whenever the user actively uses the app.
4. Add focused Android tests for token storage/selection and touched sender behavior.
5. Bump Android `versionName` / `versionCode`, then run targeted Gradle verification before push.
