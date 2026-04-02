## Goal

Investigate the failing `root/pekko-android` `main` pipelines and land the smallest Android-repo-only fix that clears `emulator-smoke-test` without regressing the core Groovitation repo's Android CI infrastructure.

## Current failure shape

- `build`, `test`, and `lint` are green on recent `main` pipelines.
- `emulator-smoke-test` is red on every recent `main` pipeline.
- The repeated failing tests are:
  - `MainActivityAvatarUploadInstrumentedTest`
  - `MainActivityPermissionBridgeInstrumentedTest.notificationPermissionBridgeSyncsIntoWebViewAcrossChangeAndRelaunch`
  - `MainActivityPermissionBridgeInstrumentedTest.locationPermissionBridgeSyncsIntoWebViewAcrossChangeAndRelaunch`

## Constraints

- Do not change the core repo's Android CI pipeline or emulator/resource isolation behavior.
- Keep fixes scoped to the Android repo unless local proof shows a real cross-repo contract issue.
- Run focused local proof before pushing.

## Plan

1. Compare current `main` against the newer `issue/manager-pekko-android` branch to isolate the minimal missing fix set.
2. Apply the repo-local Android/test changes in this worktree.
3. Run focused local proof for the failing instrumentation paths plus relevant compile/unit checks.
4. Summarize the residual risk to the core repo's pipelines before any push.
