## Issue 496 Android companion plan

Hypothesis:
- `#481` preserved fallback session cookies, but native foreground/geofence/background posts can still miss authentication on cold app resumes because the WebView has not yet established or refreshed `_user_interface_session`.
- The native bridge currently lacks `setSessionCookie()` and `getDeviceId()`, so the existing core JS cannot proactively hand native the authenticated cookie or stable Android device id.

Plan:
1. Add failing regression coverage for the missing bridge methods, cookie storage path, and foreground retry behavior.
2. Implement `GroovitationNative.setSessionCookie(cookie)` and `GroovitationNative.getDeviceId()`.
3. Add `LocationTrackingService.storeSessionCookie()` so JS-pushed cookies persist directly to SharedPreferences.
4. Retry the first foreground location POST once after 5 seconds when no session cookie is available yet.
5. Bump Android `versionCode` / `versionName`, run focused Gradle tests, then push the companion branch and merge it to `android/main` while core CI runs.
