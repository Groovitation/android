# #1680 Android Open in Native Jitsi

## Plan

- Add a dedicated Hotwire bridge component for explicit Jitsi handoff instead of allowing Jitsi's generic deep-link promo URLs.
- Build package-targeted `org.jitsi.meet` launch intents from JWT-bearing HTTPS room URLs; fall back to the Play Store when Jitsi is not installed.
- Reply to the web layer with success/fallback so the WebView can close only after a real native handoff.
- Keep the existing WebView guard that blocks unsolicited Jitsi native URLs from embedded content.
- Bump both brand versions for the Android companion branch.
- Cover URL/intent construction and component registration with Android JVM tests.
