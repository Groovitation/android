# Issue 217 Android Companion (r3)

## Context
- Core ticket #217 affects modal behavior visible in native Android WebView context.
- Companion flow requires Android branch push with mandatory app version bump.

## Plan
1. Start from latest `origin/main` in a fresh companion branch.
2. Add tracking plan commit.
3. Apply mandatory Android version bump in `app/build.gradle.kts`.
4. Push branch and monitor Android pipeline to success.
5. Merge to `android/main` and monitor main Android pipeline.
6. Post companion results on issue #217.
