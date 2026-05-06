# Groovitation Android App

Native Android wrapper for Groovitation using Hotwire Native.

## Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Firebase Setup

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Create a new project or use existing one
3. Add Android apps with package names: `io.blaha.groovitation` and `io.blaha.groovitation.chucopedia`
4. Download `google-services.json` and place it in `app/` directory
5. Enable Cloud Messaging in Firebase Console

### Building

```bash
# Production APKs
./gradlew assembleGroovitationProdDebug assembleElPasoProdDebug

# Fixture/local APKs (default backend: http://10.0.2.2:3000)
./gradlew assembleGroovitationLocalDebug assembleElPasoLocalDebug

# Fixture/local APKs with an explicit backend override
./gradlew assembleGroovitationLocalDebug assembleElPasoLocalDebug -PgroovitationLocalBaseUrl=http://10.0.2.2:4010

# Install on connected device
./gradlew installGroovitationLocalDebug
./gradlew installElPasoLocalDebug
```

### Project Structure

```
app/
├── src/main/
│   ├── java/io/blaha/groovitation/
│   │   ├── MainActivity.kt           # Main Hotwire activity
│   │   ├── GroovitationApplication.kt # App initialization
│   │   ├── GroovitationMessagingService.kt # FCM handler
│   │   └── components/
│   │       ├── BiometricComponent.kt  # Fingerprint/face auth
│   │       ├── LocationComponent.kt   # GPS location
│   │       ├── NotificationTokenComponent.kt # FCM token
│   │       └── ShareComponent.kt      # Native share sheet
│   └── res/
│       ├── layout/                    # Activity layouts
│       ├── navigation/                # Nav graphs
│       ├── values/                    # Strings, colors, themes
│       └── xml/                       # Network security config
```

## Bridge Components

The app provides these native capabilities to the web app:

### Biometric Authentication
```javascript
// In your Stimulus controller
window.HotwireNative.postMessage('biometric', 'authenticate', {
  title: 'Confirm Identity',
  subtitle: 'Use fingerprint to continue'
})
```

### Location
```javascript
// Request current location
window.HotwireNative.postMessage('location', 'requestLocation', {})

// Start background tracking
window.HotwireNative.postMessage('location', 'startBackgroundTracking', {
  postUrl: '/api/location'
})
```

### Push Notification Token
```javascript
// Register FCM token with server
window.HotwireNative.postMessage('notification-token', 'register', {
  postUrl: '/notification_tokens'
})
```

### Share
```javascript
// Show native share sheet
window.HotwireNative.postMessage('share', 'share', {
  title: 'Check out Groovitation',
  text: 'Found this cool app!',
  url: 'https://groovitation.blaha.io'
})
```

## Configuration

### Base URL
The app ships two brand flavors and two server flavors:

```kotlin
groovitation + prod  -> https://groovitation.blaha.io
elPaso       + prod  -> https://chucopedia.blaha.io
*            + local -> http://10.0.2.2:3000
```

The local flavor can be redirected at build time without editing the repo:

```bash
./gradlew assembleGroovitationLocalDebug -PgroovitationLocalBaseUrl=http://10.0.2.2:4010
GROOVITATION_LOCAL_BASE_URL=http://10.0.2.2:4010 ./gradlew assembleElPasoLocalDebug
```

`groovitationLocalBaseUrl` takes precedence over `GROOVITATION_LOCAL_BASE_URL`. Both are normalized to avoid a trailing slash.

Production remains fixed per brand through generated `BuildConfig.BASE_URL`.

### Brand Versions
Per-brand Android versions live in `app/brand-versions.properties`. Every Android push must bump the affected brand version values; Groovitation and Chucopedia are independent installable apps.

### User Agent
The app adds a custom user agent suffix for server-side detection:
```kotlin
buildConfigField("String", "USER_AGENT_EXTENSION", "\"Groovitation Android/1.0\"")
```

## Testing

```bash
# Run unit tests
./gradlew testGroovitationProdDebugUnitTest testElPasoProdDebugUnitTest testGroovitationLocalDebugUnitTest testElPasoLocalDebugUnitTest

# Run the fixture-backed emulator lane
./gradlew connectedGroovitationLocalDebugAndroidTest -PgroovitationLocalBaseUrl=http://10.0.2.2:3000
```

CI builds both brand flavors across `prod` and `local`, runs JVM tests for all four debug variants, and boots a headless emulator for the fixture-backed Groovitation local lane plus a Chucopedia side-by-side install smoke.
Production-only smoke stays in the core repo's post-deploy `smoke-test-android` job.

## Release

1. Create signing keystore
2. Add signing config to `app/build.gradle.kts`
3. Build release APK: `./gradlew assembleRelease`
4. Upload to Play Store
