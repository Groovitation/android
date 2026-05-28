# #1506 Android Jitsi deep-link guard

## Context

Ben's 2026-05-28 QA rejection says the live server-side Jitsi config still disconnects in the Android app. The durable ConfigMap rollout is already live, so this pass implements the fallback named on #1506: prevent Android WebView navigation from handing the Jitsi room to the native Jitsi app where the JWT is lost.

## Plan

- Add a delegating `WebViewClient` wrapper in `GroovitationWebView`.
- Consume native Jitsi handoff URLs (`intent:`, `org.jitsi.meet:`, `jitsi-meet:`, `market:` / Play Store links for `org.jitsi.meet`) so they cannot leave the Hotwire WebView.
- Leave normal HTTPS Jitsi room/config/API loads untouched so JWT-bearing `video.blaha.io` URLs continue in-browser.
- Add unit coverage for the URL classification and a source-shape check for the WebViewClient wrapper.
- Bump the affected Android brand version before pushing the companion branch.
