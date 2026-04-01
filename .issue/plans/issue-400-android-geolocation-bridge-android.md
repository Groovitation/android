# Issue #400 Plan: Android geolocation CI coverage in pekko-android

## Context

- Project `1` issue `#400` is the active ticket; its comments currently contain only the dispatch ack.
- Latest user instruction says the implementation belongs in `pekko-android` (project `4`), not core.
- The ticket needs deterministic native CI coverage for:
  - location permission state bridging into the web layer,
  - WebView geolocation auto-grant once Android permission exists,
  - one foreground/native location payload visible to CI.

## Plan

1. Add a deterministic debug/test hook for native foreground location and geolocation-prompt observation so emulator smoke does not depend on real GPS timing.
2. Add one native instrumented test that launches `MainActivity`, verifies the permission bridge event, triggers a WebView geolocation request, and proves the WebView path auto-grants rather than surfacing a second prompt.
3. In the same test, trigger the native foreground location request path and assert the emitted payload matches the injected test location.
4. Add focused unit/source coverage for any new hook surface that is not naturally protected by the instrumented test.
5. Bump the Android app version, run local Gradle validation, push the branch, and report back on issue `#400` with `/spend`, validation, and CI status before moving the label to `status::ci`.
