# Issue #155 Android Companion r2 Plan

## Goal
Ship Android companion updates for native share reliability and mandatory app version bump.

## Steps
1. Add Android-side compatibility shim for legacy `window.NativeApp.postMessage(...)` payloads so share requests route to the Hotwire bridge with required message id.
2. Add/adjust tests for the shim helper behavior.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. Run Android tests (or document local env limits) and push branch.
5. Monitor branch CI to success, merge to `main`, and monitor `main` pipeline.
