# issue-26-pelias-address-autocomplete-android-r1

## Goal
Complete the Android companion lane for issue #26 while core CI runs by publishing a required app version bump so Android delivery state stays aligned with the ticket workflow.

## Steps
1. Create Android companion branch from `origin/main` in a dedicated worktree.
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Run Android unit tests (`./gradlew test --no-daemon`).
4. Push branch, monitor CI to green, merge to `android/main`, and monitor main pipeline.
