# Issue #195 — Android menu bar dark theme

## Problem
Bottom navigation bar is purple-on-white, doesn't match the dark theme of the web content.
No `values-night` color overrides exist, so system bars and bottom nav use hardcoded light colors.

## Fix
- Create `res/values-night/colors.xml` overriding `status_bar_color`, `navigation_bar_color`,
  `background_light`, and `text_secondary` for dark mode
- The theme already uses `DayNight` parent and references these colors by name, so night
  variants will auto-resolve
- Bump version per mandatory Android repo policy
