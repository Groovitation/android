# Maestro Tests for Groovitation Android

End-to-end tests for the Groovitation Android app using [Maestro](https://maestro.mobile.dev/).

## Prerequisites

1. **Install Maestro:**
   ```bash
   curl -Ls "https://get.maestro.mobile.dev" | bash
   ```

2. **Start Android Emulator:**
   ```bash
   # List available emulators
   emulator -list-avds

   # Start an emulator
   emulator -avd Pixel_7_API_34
   ```

3. **Install the app:**
   ```bash
   cd /home/ben/src/groovitation/android
   ./gradlew installDebug
   ```

## Running Tests

### Run all tests:
```bash
cd /home/ben/src/groovitation/android
maestro test .maestro/
```

### Run a single test:
```bash
maestro test .maestro/01-app-launches.yaml
```

### Run with Maestro Studio (visual debugging):
```bash
maestro studio
```

### Run in CI mode (no interactive prompts):
```bash
maestro test --format junit .maestro/
```

## Test Files

| File | Description |
|------|-------------|
| `01-app-launches.yaml` | Verifies app launches and loads content through basic auth |
| `02-login-flow.yaml` | Tests the login form flow |
| `03-navigation.yaml` | Tests Turbo navigation between pages |
| `04-push-notification-token.yaml` | Verifies push notification permission and token registration |
| `05-location-permission.yaml` | Tests location permission flow on map page |

## Configuration

The app connects to `https://groovitation.blaha.io` which is behind basic auth.
The `GroovitationWebViewClient` automatically handles the basic auth credentials.

## Screenshots

Test screenshots are saved to `.maestro/screenshots/` for debugging.

## Environment Variables

For login tests, set these environment variables:
```bash
export TEST_EMAIL=your-test-email@example.com
export TEST_PASSWORD=your-test-password
maestro test .maestro/02-login-flow.yaml
```

## Troubleshooting

### White screen on app launch
- Check if the emulator has internet access
- Verify basic auth credentials in `GroovitationWebViewClient.kt`
- Check logcat: `adb logcat | grep -E "(Groovitation|WebView|Error)"`

### Tests timing out
- Increase timeout values in test files
- Check if the server is responding: `curl -u groovitation:aldoofra https://groovitation.blaha.io`

### Permission dialogs not appearing
- Clear app state: `adb shell pm clear io.blaha.groovitation`
- Or use `clearState: true` in launchApp
