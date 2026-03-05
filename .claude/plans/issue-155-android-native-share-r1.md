# Plan: issue-155-android-native-share-r1

## Goal
Complete issue #155 Android lane by shipping a validated native-share update on a dedicated branch with required version bump.

## Steps
1. Verify current Android native share bridge wiring and identify any remaining robustness gaps.
2. Implement focused Android-side share improvement (if needed) and add regression coverage.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. Run Android local tests (`./gradlew test`).
5. Push branch and monitor GitLab pipeline to terminal state.
6. Post issue #155 completion status with branch, CI evidence, and `/spend`.
