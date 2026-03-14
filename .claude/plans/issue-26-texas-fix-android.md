## Issue #26 Android companion plan

- Ticket: `root/groovitation#26`
- Core branch: `claude/issue-26-texas-fix`
- Android branch: `claude/issue-26-texas-fix-android`

### Goal

Ship the companion Android version bump required for the create-event address validation fix so app users receive the rollout prompt tied to the core deployment.

### Steps

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run Android unit tests.
3. Commit and push the companion branch.
4. Merge to `main` once Android CI is green while core CI/deploy is being reconciled.
