## Plan

1. Audit current `pekko` and `android/main` to separate shipped shared-web modal fixes from any remaining Android-native bug.
2. Port only the verified Android `closeTopWebModalIfOpen` script change that closes the explicit top overlay/modal without synthesizing `Escape`.
3. Add or keep focused JVM coverage for the generated script and bump the Android app version for the companion push.
4. Run targeted local Gradle validation, push `claude/issue-114-android-back-button-r9`, monitor branch CI, and merge to `android/main` if green.
