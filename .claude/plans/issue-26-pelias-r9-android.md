# Issue 26 Pelias r9 Android Plan

- Started: 2026-03-10T22:42:24-06:00
- Branch: `claude/issue-26-pelias-r9-android`
- Base: `origin/main` at `fb593ec`

## Context

Issue #26 affects create-event address validation behavior used inside the Android app's WebView, so the companion Android flow is required by project policy even though the code delta here is only the version bump.

## Plan

1. Bump Android `versionCode`/`versionName` on a fresh branch from current `origin/main`.
2. Push the companion branch and rely on branch CI for validation because local Android SDK configuration is unavailable on this machine.
3. Merge the companion branch to `android/main` once the branch pipeline is green, then monitor the resulting `android/main` pipeline while core CI continues.
