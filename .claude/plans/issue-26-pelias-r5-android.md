## Issue

Issue #26 changes create-event address behavior that is visible through the Android app, so the companion Android branch flow requires a synchronized version bump on a fresh retry branch.

## Plan

1. Create a fresh companion branch from `android/main`.
2. Bump Android version metadata only.
3. Push the branch and monitor branch CI.
4. Merge to `android/main` after branch CI succeeds while core CI is still running.
