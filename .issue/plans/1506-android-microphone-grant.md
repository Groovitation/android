# #1506 Android microphone grant rework

## Problem

After installing the Android build from the previous #1506 round, Ben's tablet can use the camera in the embedded Jitsi room, but microphone capture still fails. Reopening the room leaves only the microphone warning.

## Plan

- Keep the deployed core Permissions-Policy/Jitsi iframe fixes unchanged unless investigation proves a server regression.
- Patch the Android WebView media contract for audio-only failure:
  - declare `android.permission.MODIFY_AUDIO_SETTINGS` alongside `RECORD_AUDIO`;
  - ensure the WebView does not require a user gesture for WebRTC media playback;
  - keep the existing runtime `RECORD_AUDIO` grant bridge and partial audio/video resource grant behavior.
- Bump both Android brand versions because shared WebView behavior changes for Groovitation and Chucopedia.
- Verify with focused Android unit/source tests and branch CI, then merge the Android companion to `android/main` and hold for the main deploy + physical tablet QA.

## Coverage Map

- WebView has Android's audio routing permission needed by Chromium/WebRTC microphone capture -> Bucket 1 manifest/source contract -> `PermissionBridgeSourceTest`.
- WebView allows inline Jitsi media to play without user gesture gating -> Bucket 1 WebView settings contract -> `PermissionBridgeSourceTest`.
- Actual tablet microphone capture -> Bucket 2 physical-device behavior -> post-deploy Ben/scruff7 QA after installing the new APK.
