# issue-180-r3-android

## Objective
Complete the mandatory Android companion flow for issue #180 while core CI runs.

## Plan
1. Branch from `android/main` in a dedicated worktree.
2. Apply mandatory version bump in `app/build.gradle.kts`.
3. Push companion branch, monitor Android CI jobs to success.
4. Merge to `android/main` and confirm `android/main` pipeline success.
