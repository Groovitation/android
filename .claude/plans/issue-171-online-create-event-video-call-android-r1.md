# Issue #171 Android Companion (r1)

## Context
- Core branch `claude/issue-171-online-create-event-video-call-r2` is in CI for create-event online video-call behavior.
- Companion Android flow requested for app-impact tickets.

## Plan
1. Bump Android app version in `app/build.gradle.kts` (mandatory policy).
2. Push companion branch and run Android branch CI.
3. Merge to `android/main` immediately after companion CI success while core CI is still in flight.
4. Verify `android/main` pipeline reaches terminal success and report on #171.
