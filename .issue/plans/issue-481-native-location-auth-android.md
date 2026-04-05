## Goal

Restore authenticated native Android location POSTs for foreground GPS, geofence exits, and periodic background uploads.

## Constraints

- Ticket scope is Android repo only.
- Every Android push must include a version bump.
- Fix must preserve background behavior when the WebView is not active.

## Plan

1. Reproduce the cookie-selection failure in tests by isolating native session-cookie resolution.
2. Replace the duplicated cookie lookup in native location senders with shared logic that:
   - prefers a valid session cookie
   - logs when only stale or missing cookies are available
   - refreshes/persists the last known good session cookie for background workers
3. Add targeted tests for the shared resolver and any touched location sender seams.
4. Run the relevant Gradle unit-test/build lane locally, then push and monitor Android CI.
