# Android CI Google Services JSON Hardening

## Goal
Stop CI `build` failures caused by malformed `app/google-services.json` when CI variables are empty or encoded differently.

## Plan
1. Replace raw `echo "$GOOGLE_SERVICES_JSON" > app/google-services.json` with robust input handling.
2. Support both raw JSON and base64 JSON via `GOOGLE_SERVICES_JSON` and `GOOGLE_SERVICES_JSON_B64`.
3. Validate the resulting file with `jq -e .` before Gradle runs.
4. Fail fast with actionable diagnostics if input is missing or invalid.
5. Run local CI lint checks for shell syntax and JSON validation paths.
