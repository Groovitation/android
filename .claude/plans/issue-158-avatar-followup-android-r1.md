# Plan: issue-158-avatar-followup-android-r1

## Goal
Companion Android hardening for avatar upload issue #158.

## Approach
1. Add native WebView file chooser guardrails for avatar uploads (supported image MIME types + 20MB bound) with user-facing toast on rejection.
2. Add unit tests for the guard helper.
3. Apply mandatory Android version bump.
4. Run Android unit tests and push for CI.
