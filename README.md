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
3. Add an Android app with package name: `io.blaha.groovitation`
4. Download `google-services.json` and place it in `app/` directory
5. Enable Cloud Messaging in Firebase Console

### Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
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
Edit `app/build.gradle.kts` to change the base URL:
```kotlin
buildConfigField("String", "BASE_URL", "\"https://groovitation.blaha.io\"")
```

### User Agent
The app adds a custom user agent suffix for server-side detection:
```kotlin
buildConfigField("String", "USER_AGENT_EXTENSION", "\"Groovitation Android/1.0\"")
```

## Testing

```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Release

1. Create signing keystore
2. Add signing config to `app/build.gradle.kts`
3. Build release APK: `./gradlew assembleRelease`
4. Upload to Play Store
