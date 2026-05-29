# #1506 Android WebRTC Permission Rework

## Context

The server-side Permissions-Policy fix is deployed, but Ben's Android tablet
still reports failed microphone access inside the Jitsi room.

## Plan

- Keep requesting the Android runtime camera/microphone permissions mapped from
  the WebView permission request.
- Grant the supported WebRTC audio/video subset back to WebView instead of
  denying the whole request when Jitsi includes an extra unsupported resource.
- Bump both brand APK versions because shared WebView behavior changes.
- Cover the resource-filtering behavior with focused unit tests.
