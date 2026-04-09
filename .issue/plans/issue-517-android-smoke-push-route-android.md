## Issue 517 Android companion plan

1. Reproduce the failure from `pekko #9458` artifacts and confirm whether the post-push tap path is app-side or harness-side.
2. Patch the Android notification tap path so Android 14/15 background activity launch policy does not break debug notification routing.
3. Add focused unit coverage for the notification PendingIntent options and the debug tap hook path.
4. Bump Android version, run targeted Android unit tests, then push and monitor branch CI before merging to `main`.
