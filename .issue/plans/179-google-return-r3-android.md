# Issue 179 Google return r3 android companion

## Purpose

The functional fix is in core, but Android owns the final deep-link handoff contract after the browser callback returns to the app.

## Plan

1. Add a Robolectric regression that proves `groovitation://oauth-callback?...` is converted into the expected `/oauth/native-authenticate?...` web route.
2. Keep the Android code change minimal and test-oriented.
3. Bump `versionCode` and `versionName` in the same first Android push, per repo policy.
