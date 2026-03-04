# issue-167-android-broken-r1

## Goal
Fix Android app launch crash reported in #167 (v1.0.22 closes immediately after open), and add a regression test so this class of startup breakage is caught pre-release.

## Plan
1. Reproduce startup crash path with an automated Android unit test.
2. Identify launch-time root cause and apply minimal safe fix.
3. Keep account access in bottom navigation while staying within platform constraints.
4. Validate with targeted unit tests and post issue update with /spend.
