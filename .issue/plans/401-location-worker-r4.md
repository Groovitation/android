## Issue

Add deterministic Android CI coverage for the one-shot `LocationWorker.enqueueOneShot()` path so CI can prove a background-sourced location update happens without relying on periodic scheduling or a live backend.

## Constraints

- Follow the issue body as the authoritative spec; there are no later human overrides in the ticket thread.
- Keep the scope Android-only and deterministic.
- Exercise the real WorkManager one-shot path.
- Assert a background-sourced payload and clean worker completion.

## Plan

1. Audit the prior `issue/401-location-worker-r3` Android-only implementation against current `origin/main`.
2. Carry the worker test seam, one-shot instrumentation coverage, and necessary version update onto fresh branch `issue/401-location-worker-r4`.
3. Run local Android verification (`testLocalDebugUnitTest`, androidTest compile/package, diff check).
4. Push, log `/spend` time, post the structured completion comment, and normalize the issue label to `status::ci`.
