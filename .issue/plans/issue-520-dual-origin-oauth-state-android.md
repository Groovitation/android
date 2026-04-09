## Issue 520 Android companion plan

1. Keep the Android companion narrow: only cover the native OAuth link-flow redirect path that returns to `/users/edit`.
2. Add a focused `MainActivityOAuthCallbackTest` regression proving a custom-scheme callback still routes through `/oauth/native-authenticate` and selects the account tab for link flows.
3. Bump Android `versionCode` / `versionName`, run the targeted Robolectric test, then push and merge the companion branch to `main` while the core branch CI is still in flight.
