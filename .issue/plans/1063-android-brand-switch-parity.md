# 1063 Android Brand-Switch Modal Parity

## Goal

Let the installed Android app route a same-product cross-brand HTTPS URL when one reaches `MainActivity`, so Hotwire can load the page and the server-rendered brand-switch modal can handle the confirmation.

## Plan

1. Treat both production brand hosts as in-product URLs for `ACTION_VIEW` handling while keeping the manifest scoped to the flavor's verified app-link host.
2. Preserve existing OAuth callback handling and bottom-nav path selection.
3. Add focused Robolectric coverage proving a Chucopedia/Groovitation flavor accepts the opposite brand host URL.
4. Run focused unit tests for the touched activity intent path before pushing.

## Verification

- Focused Android unit tests for `MainActivity` intent routing.
- Android branch CI after push.
- Core ticket branch CI for the issue audit branch.
