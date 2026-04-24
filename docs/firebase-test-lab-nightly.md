# Firebase Test Lab nightly runbook

Tracks the `firebase-test-lab-nightly` CI job (#797). Read this when the
nightly goes red.

## What the job is for

Emulators can fake Doze and app-standby buckets via ADB, but they cannot
reproduce:

- Samsung "Put unused apps to sleep" 3-day / 16-day thresholds.
- OneUI-specific throttling (the OneUI 7 location regression on S24
  family that motivated this job in the first place).
- Samsung's custom WebView Chromium patches.
- OEM-specific `FusedLocationProvider` radio behaviour
  (`PRIORITY_BALANCED` returning null on Samsung with the screen off).
- Samsung re-adding apps to the restricted bucket after a firmware
  update.

These form the dominant class of "proximity works for me, silent in
prod" bugs. The nightly catches them by running on an actual Galaxy S24
in Firebase Test Lab, on a schedule, against the latest `main` build.

## Trigger model

- **Scheduled pipeline only** — gated by
  `$CI_PIPELINE_SOURCE == "schedule"` in `.gitlab-ci.yml`. No per-PR
  fan-out, so cost stays predictable (~$1–5/day per the ticket budget).
- **Manual trigger from the pipeline UI** is also exposed via
  `when: manual`. Use it when investigating an OEM-shaped bug between
  scheduled runs.

The schedule itself is configured under
`Project → Build → Pipeline schedules` in the GitLab UI for the
`pekko-android` project. Cadence: nightly UTC.

## Required CI variables

| Variable | Required | Notes |
| --- | --- | --- |
| `FTL_SERVICE_ACCOUNT_KEY_B64` | yes | Base64-encoded JSON for a service account with `roles/firebase.testLabAdmin` and access to the Cloud Storage results bucket. Masked. |
| `FTL_GCP_PROJECT_ID` | yes | The GCP project the FTL run bills against. |
| `FTL_DEVICE_MODEL` | no | Defaults to `SM-S921U` (Galaxy S24 US carrier). Override only when chasing a specific OneUI version. |
| `FTL_DEVICE_VERSION` | no | Android API level for the device. Defaults to `34`. |

The job aborts up-front with a clear message if the first two are
missing — no half-runs that quietly succeed.

## What the job does

Two FTL submissions per run:

1. **Robo crawl** (`--type robo`, 5-minute timeout). Firebase's automated
   UI explorer. No test code — surfaces ANRs/crashes that OEM throttling
   or OneUI-specific WebView patches cause without needing custom
   assertions.
2. **Instrumentation suite** (`--type instrumentation`, 30-minute
   timeout). Same `androidTest` suite that `emulator-smoke-test` runs,
   re-targeted at the physical S24. The location / Doze / AppStandby
   tests are the ones that catch OEM regressions; the rest serve as a
   smoke layer that the build is sound on real hardware. Uses Android
   Test Orchestrator (`--use-orchestrator`) so a single test crashing
   doesn't take down the rest of the suite.

Both are submitted with `--results-dir` under `ci-nightly/<run-id>/...`
in FTL's results bucket so historical runs are retrievable.

## Reading a failure

The CI job's artefacts include `build/firebase-test-lab/robo.log` and
`build/firebase-test-lab/instrumentation.log`. Each contains:

- The FTL "console" link to the run in Firebase, with video, logcat, and
  performance traces.
- The `gcloud` exit summary line — non-zero exit is what failed the job.

Open the FTL console link first. The triage path:

1. **Did the APK install?**
   - "INSTALL_FAILED_*" → likely an OEM signature-validation or
     `versionCode` regression. Check `app/build.gradle.kts` for the
     `versionCode` bump on the failing commit. (Reminder: every push to
     this repo needs a `versionCode + 1` bump — see top-level CLAUDE.md.)
2. **Did the app launch?**
   - Crash on launch with an OEM-named class in the stack (Samsung
     `*PowerManagerService*`, `*OneUI*`) → environmental regression.
     File a ticket against application logic, not a "fix the test" PR.
   - Crash with a `groovitation.*` stack → application bug. Same fix
     path as a normal in-repo crash.
3. **Did individual tests fail?**
   - `LocationWorkerInstrumentedTest`, `DozeInstrumentedTest`,
     `AppStandbyRestrictedBucketTest`, `BackgroundPermissionMissingTest`
     are the **load-bearing** location tests. A red here is the signal
     the nightly was built to catch — **do not retry-to-green**.
     Investigate via the FTL video + logcat first; the bug is almost
     certainly real.
   - Other tests (`MainActivity*`, avatar, web styling) failing on FTL
     but passing on the emulator usually points at WebView differences
     or screen-size differences. Real but lower priority.
4. **Did Robo find an ANR?**
   - The FTL console shows ANR traces under "Issues". The trace's top
     frame names the offending API. ANRs from `LocationCallback`
     dispatch under Doze are the classic OneUI shape.

## When to retry vs investigate

| Symptom | Action |
| --- | --- |
| Generic "FTL pool empty, queued 30+ min" | Retry. Quota is shared; this is transient. |
| Network errors during APK upload | Retry. Logs show `gcloud` HTTP 5xx. |
| Test failures with no crash, all green on the next run | Investigate — flake suggests a real timing issue under OEM throttling. Don't suppress; file a follow-up. |
| Test failures with the same red across 2+ runs | Real regression. Triage via the FTL console video. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The `versionCode` regressed. Check the most recent merge to `main`. |

## Cost guardrails

- Single device per night, 5-minute Robo + ≤30-minute instrumentation
  slot ⇒ ~$1–5/day (per the ticket's accepted budget).
- The job's manual-trigger path does not impose a quota — be mindful
  when firing it repeatedly during an investigation. Use the FTL Cloud
  Console for the "Run again" path on a previously-submitted matrix
  rather than re-uploading.

## Tracked follow-ups

These are intentional scope cuts in the first landing of the nightly:

- **30-minute background-location scenario** (per #797's acceptance
  criterion #4). Needs a publicly-reachable test backend that can
  receive location POSTs and expose them for assertion. The existing
  `avatar_fixture_server.py` is loopback-only and the fixture URL the
  emulator-smoke-test uses (`http://10.0.2.2:...`) is not reachable
  from FTL cloud devices. Tracked in a follow-up.
- **Mock-location injection for geofence ENTER assertions**. Same
  blocker — needs the publicly-reachable backend before the assertion
  loop is meaningful.
- **Per-OneUI-version matrix expansion**. Today the job runs against a
  single device. Expanding to multiple OneUI versions (S24 with stock
  vs. carrier-customised firmware) is a single `gcloud --device …`
  flag away once cost permits.
