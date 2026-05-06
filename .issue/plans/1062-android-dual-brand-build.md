# Android Dual-Brand Build Plan

Ticket: #1062
Branch: `issue/1062-android-dual-brand-build`

## Scope

- Add an Android brand product-flavor dimension with `groovitation` and internal `elPaso` flavors.
- Preserve the current Groovitation package ID so existing installs, Firebase config, and core Android acceptance harnesses keep working; add Chucopedia as a separate package via suffix.
- Give each brand its own app label, launcher foreground glyph, production start URL, app-link host, and version metadata.
- Build/test/upload both brand APKs in Android CI while keeping legacy Groovitation Gradle task/output compatibility for core CI.

## Verification

- Local Gradle task graph/build checks for both brand/server combinations.
- Focused unit tests covering brand BuildConfig values, app-link/OAuth routing, update URL, and installable package separation.
- Android branch CI, MR to `main`, Android main pipeline after merge.
