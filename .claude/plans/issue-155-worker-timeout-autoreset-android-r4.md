# issue-155-worker-timeout-autoreset-android-r4

## Goal
Run the required Android companion flow for issue #155 by shipping a mandatory version bump tied to the native-share hardening deployment window.

## Plan
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Push companion branch and verify Android branch pipeline status via GitLab API.
3. Merge companion branch to `main` after branch CI is green.
4. Verify `main` pipeline reaches terminal success.
