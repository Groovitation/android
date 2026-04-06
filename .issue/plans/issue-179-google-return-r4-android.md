# Issue 179 Google Return R4 Android

## Problem

Even if the backend returns to Android after browser OAuth, the app should visibly land back on the intended destination tab instead of leaving the prior tab selected.

## Plan

1. Treat `https://groovitation.blaha.io/oauth/native-authenticate?...` as the primary Android browser-auth return path.
2. When the OAuth return intent includes a `redirect` query parameter, preselect the matching bottom-nav tab before routing the WebView so Events returns land on the Events tab.
3. Add a Robolectric regression for the HTTPS app-link callback path.
4. Bump the Android app version as required for companion-branch changes.
