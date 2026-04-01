# Issue 403 Android companion plan

1. Refactor incoming push notification display/tap routing into a shared native helper so the FCM entrypoint remains the authoritative path.
2. Add a debug-only simulation receiver that can post the same notification payload deterministically from CI.
3. Add focused Robolectric coverage for notification payload parsing, pending-intent routing, and notification posting.
4. Apply the mandatory Android version bump in the first companion commit, then push and reconcile the companion branch to `main`.
