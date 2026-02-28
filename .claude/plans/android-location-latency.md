# android-location-latency

## Goal
Reduce perceived Android map location lag while moving by improving native location request cadence and WebView bridge delivery.

## Investigation focus
1. Native location request frequency and duration.
2. Accuracy/priority settings for foreground fixes.
3. Background location worker cadence and limitations.
4. WebView geolocation bridge behavior and event dispatch.

## Implementation plan
1. Ensure app resume triggers a native fresh location request into the WebView map path.
2. Update native JS bridge location stream to dispatch significant movement updates, not just the first fix.
3. Keep high-accuracy request settings but avoid stale/no-update behavior by tracking distance + time thresholds.
4. Compile Android app to verify build correctness.
