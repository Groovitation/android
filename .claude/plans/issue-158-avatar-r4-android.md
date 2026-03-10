## Issue

Issue #158 affects Android app behavior, so the companion Android branch flow is required on this retry. Current `android/main` already contains the avatar chooser sniffing logic, so this branch should only carry the next required version bump unless fresh audit shows another missing delta.

## Plan

1. Verify current `android/main` already has the companion avatar guard behavior.
2. Bump Android version metadata from the current main version for this retry.
3. Run focused Android validation if the local SDK is available, otherwise document the environment blocker.
4. Push the branch, monitor branch CI, and merge to `android/main` while core CI continues.
