## Goal

Implement a native Android avatar intake flow that keeps the modern Photos picker while restoring a first-class camera path and supporting a reusable image-submission pattern.

## Constraints

- Prefer explicit native actions over the generic file-input chooser.
- Preserve the existing WebView upload callback contract.
- Keep chooser behavior deterministic enough for instrumentation coverage.
- Fold HEIF/HEIC handling into the intake path if needed so modern phone photos are accepted.

## Plan

1. Audit the current `MainActivity.launchImageChooser` and `GroovitationWebView.onShowFileChooser` flow.
2. Replace the generic chooser launch with an explicit native intake sheet: Photos, Camera, Browse.
3. Return all three paths through the same callback pipeline so the web upload code stays stable.
4. Update Android tests to assert the new native flow and image handoff behavior.
