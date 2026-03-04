# Issue 167 Emulator Coverage Follow-up Plan

## Goal
Answer Ben with concrete Android CI emulator coverage evidence and close the coverage gap by adding an emulator smoke test path.

## Steps
1. Audit existing Android CI jobs and verify whether emulator/instrumented tests run today.
2. Add a minimal instrumentation smoke test that launches `MainActivity` and asserts bottom-nav safety.
3. Add CI emulator job to run `connectedDebugAndroidTest` on a headless emulator.
4. Keep emulator job manual/non-blocking initially for runner stability.
5. Validate local unit + instrumentation assembly paths.
6. Push branch, post findings + gap analysis + follow-up details, and log `/spend`.
