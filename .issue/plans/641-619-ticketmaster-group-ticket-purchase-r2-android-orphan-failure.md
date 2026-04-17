## Goal

Repair branch `issue/619-ticketmaster-group-ticket-purchase-r2-android` after orphan CI issue `#641`, where `emulator-smoke-test` failed in `MainActivityAvatarUploadInstrumentedTest` on head `36ab457`.

## Current evidence

- Branch pipeline `#10200` failed only in `emulator-smoke-test #71708`.
- The failing assertion shows `selectedAvatarName` stayed blank after the native avatar chooser flow.
- The dirty legacy companion worktree contains an uncommitted `MainActivity.kt` avatar-chooser change, but it is being treated as suspect until the failure is re-proven on committed code.

## Plan

1. Reproduce the failing avatar-upload instrumentation test on clean committed code.
2. Confirm whether the image-intake sheet cancel path clears `pendingFileChooserCallback` after a user taps one of the chooser actions.
3. Implement the minimal fix and rerun focused Android proof.
4. Push the repair to the original companion branch, watch branch CI, merge to `main` if green, then report on-ticket.
