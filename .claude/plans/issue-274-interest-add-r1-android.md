## Goal

Ship the issue #274 interests-tab refresh fix through the Android companion flow so the Android app picks up the updated web behavior.

## Plan

1. Bump the Android app version on a fresh `android/main` worktree branch tied to issue #274.
2. Run available local Android validation if the machine is configured for it.
3. Push the companion branch, then merge it to `android/main` while the core branch CI is running.
