# Issue 217 Android Companion (r2)

## Context
Issue #217 changes app-visible behavior by using native browser handoff for the restroom-code leaderboard launch path from map/event modals.

## Plan
1. Create companion Android branch aligned with core `r2` dispatch.
2. Apply mandatory Android version bump in `app/build.gradle.kts`.
3. Push branch and monitor CI.
4. Merge to `android/main` once companion CI is green while core CI is in-flight.
