# Issue 155 Android Companion r3 Plan

1. Bump Android app version metadata in `app/build.gradle.kts` (`versionCode` and `versionName`) so users receive the updated app package.
2. Push `claude/issue-155-native-share-actions-android-r3` and monitor branch pipeline to terminal success.
3. Merge companion branch to `android/main` and verify `android/main` pipeline success while core issue #155 CI/merge flow completes.
