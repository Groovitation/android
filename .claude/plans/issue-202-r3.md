# Issue #202 r3 - Events tab loads map instead of events on Android

## Root Cause

When navigating from Map back to Events, Hotwire's `Navigator.route()` determines
the presentation should be `POP` (because the events URL `/` matches the previous
location in the back stack). The POP reveals the stale fragment without performing
a fresh Turbo visit, so the WebView continues showing `/map` content.

Traced through decompiled Hotwire Native Android 1.2.0 `NavigatorRule.newPresentation()`:
- `locationsAreSame(newLocation, previousLocation)` returns true (events `/` was previous)
- This triggers `Presentation.POP` which pops the back stack
- The restored fragment's WebView shows stale map content

## Fix

Use `Navigator.clearAll()` for the Events tab instead of `route()`. Since Events
IS the start location (`/`), `clearAll()` resets the back stack and forces a fresh
Turbo visit to the start location.

## Test

Added `MainActivityEventsTabNavigationTest` (Robolectric) that verifies:
1. Events tab path maps to `/`
2. Events tab uses `clearAll()` not `route()`
3. Other tabs use `route()` not `clearAll()`
