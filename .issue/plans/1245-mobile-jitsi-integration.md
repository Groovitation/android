# Issue 1245 - Android Jitsi WebView Media Permissions

## Goal

Support the backend WebView fallback for inline Jitsi rooms by granting WebRTC camera and microphone resources only after Android runtime permissions are granted.

## Plan

- Bump both brand versions for the APK rule.
- Declare optional camera and microphone capabilities in the manifest.
- Map Jitsi/WebRTC `PermissionRequest` resources to Android `CAMERA` and `RECORD_AUDIO` runtime permissions.
- Request those permissions on demand from `MainActivity` and grant or deny the original WebView request based on the result.
- Add unit/source coverage for the manifest, mapping helpers, and runtime permission bridge.

## Tradeoffs

- Use the WebView fallback rather than the native Jitsi SDK to avoid adding a large native dependency and to keep the Hotwire shell path aligned with the core landing implementation.
- Do not prompt for camera or microphone during app startup; the prompt is deferred until the user actually enters a Jitsi room.
