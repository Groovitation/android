## Plan

1. Audit the current Android avatar chooser/upload guard against issue #158 and current core behavior.
2. Reproduce the native-side mismatch that still allows broken avatar uploads.
3. Tighten chooser validation and add tests, with required version bump if Android behavior changes.
4. Push the companion branch, run Android CI, and merge to `android/main` while core CI is in flight if policy requires.
