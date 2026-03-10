## Issue

Issue #26 affects create-event address behavior visible in the Android app, so this fresh retry needs the standard Android companion branch flow with a synchronized version bump while the core branch re-runs CI.

## Plan

1. Bump Android version metadata on a fresh branch from `origin/main`.
2. Push the companion branch and monitor branch CI.
3. Merge to `android/main` while the core branch CI is running, per the companion-flow instruction.
