## Goal

Move Android emulator slot waiting out of `emulator-smoke-test` and into a dedicated CI gate so emulator queue time does not consume the smoke job wall clock and retried jobs can recover their reserved port cleanly.

## Approach

1. Add an Android emulator slot manager script that tracks slot ownership by pipeline ID, with FIFO waiting and stale-lock cleanup.
2. Add an `emulator-slot-claim` CI job that waits for a slot and writes the reserved emulator port as an artifact.
3. Rewire `emulator-smoke-test` to consume the claimed slot artifact, re-claim its preferred slot on retry if needed, and clean up the emulator process without holding the queue wait inside the test job.
4. Add a small regression test for the slot manager, then validate with shell checks, the slot-manager test script, `git diff --check`, and GitLab CI lint.
