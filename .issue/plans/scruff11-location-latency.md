# scruff11-location-latency

## Context

- User reports that the Android app can still feel slow to reflect a newly acquired location.
- The inconclusive but important clue is that closing and reopening the app sometimes makes the new location appear immediately.
- Prior scruff11 work already fixed the map auto-follow snapback and the rolling tracking geofence refresh regression.
- The remaining suspicion is that foreground Android GPS may already be posting a fresh location, but pages that attach later are not reusing that fresh fix and instead wait on a new GPS acquisition.

## Plan

1. Trace the Android foreground location path from `MainActivity.onResume()` through `ForegroundLocationManager`, the WebView bridge, and the page-side `requestFreshLocation()` flow.
2. Verify whether the server is already ranking recent `foreground-gps` rows correctly or whether the latency is entirely inside the Android/WebView handoff.
3. If the app is dropping a fresh foreground fix before the page starts listening, add a narrow warm-start path that replays a recent foreground fix immediately while still continuing normal GPS refinement.
4. Add focused regression coverage proving a recent foreground fix wins the first bridge event for the next page request.
5. Bump the Android app version and run the smallest local validation set that exercises the new warm-start path.
