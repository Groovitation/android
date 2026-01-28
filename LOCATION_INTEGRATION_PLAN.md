# Background Location Integration Plan

## Goal
Get significant-change (~100m) background location updates from the Android app into the Pekko actor system.

## Current State

### Android App (Ready)
- `LocationComponent.kt` has `startBackgroundTracking()` method
- Uses `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`
- Currently posts to a URL provided by the web page via bridge message
- Interval-based (60s), not distance-based

### Pekko Backend (Existing)
- **Endpoint**: `POST /{brand}/{version}/{locale}/people/{personUuid}/location`
- **Flow**: HTTP → `ActiveUserRegistry` → `ActiveUserActor` → `LocationManagerActor` → `LocationUpdatedActor`
- **Storage**: `parties.lonlatheight` (PostGIS) + `location_history` table
- **Model**: `LocationUpdate(lat, lon, altitude, accuracy, speed, heading, isMoving, timeSinceLastUpdate, updateReason, priority)`

---

## Implementation Plan

### Phase 1: Android - Significant Change Updates

**File**: `app/src/main/java/io/blaha/groovitation/components/LocationComponent.kt`

**Changes**:
1. Replace interval-based tracking with distance-based:
```kotlin
// Current: interval-based
val locationRequest = LocationRequest.Builder(
    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
    60000L  // 60 seconds
).build()

// New: distance-based (100m minimum displacement)
val locationRequest = LocationRequest.Builder(
    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
    300000L  // 5 minutes max interval
)
    .setMinUpdateDistanceMeters(100f)  // Only update if moved 100m
    .setMinUpdateIntervalMillis(60000L) // But no faster than 1 min
    .build()
```

2. Add `updateReason` to location posts:
```kotlin
val json = JSONObject().apply {
    put("latitude", latitude)
    put("longitude", longitude)
    put("accuracy", accuracy)
    put("updateReason", "significant_distance")  // NEW
    put("priority", "background")                 // NEW
}
```

3. Store auth token for background requests (currently uses session cookies which won't work in background):
```kotlin
// Need to store auth credentials or token for background HTTP calls
object LocationStorage {
    var authToken: String? = null
    var personUuid: String? = null
    var baseUrl: String? = null
}
```

### Phase 2: Android - Background Service (Optional, for true background)

For updates when app is completely closed, need a `ForegroundService`:

**New File**: `app/src/main/java/io/blaha/groovitation/services/LocationTrackingService.kt`

```kotlin
class LocationTrackingService : Service() {
    // ForegroundService with persistent notification
    // "Groovitation is tracking your location"
    // Uses significant change API
    // Posts to server even when app is killed
}
```

**Manifest additions**:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

<service
    android:name=".services.LocationTrackingService"
    android:foregroundServiceType="location"
    android:exported="false" />
```

### Phase 3: Pekko Backend - Background Location Endpoint

**New File**: `controllers/BackgroundLocationController.scala`

A lightweight endpoint specifically for background location updates:

```scala
class BackgroundLocationController(
  activeUserRegistry: ActorRef[ActiveUserRegistry.Command],
  personRepository: PersonRepository
)(implicit system: ActorSystem[_]) {

  def routes: Route = pathPrefix("api" / "location") {
    post {
      // Token-based auth (not session cookies)
      headerValueByName("X-Location-Token") { token =>
        entity(as[BackgroundLocationUpdate]) { update =>
          // Validate token → get personId
          // Route to actor system
          // Return minimal response (save bandwidth)
          complete(StatusCodes.NoContent)
        }
      }
    }
  }
}

case class BackgroundLocationUpdate(
  latitude: Double,
  longitude: Double,
  accuracy: Option[Double],
  timestamp: Long,  // Device timestamp
  updateReason: String  // "significant_distance", "geofence_exit", etc.
)
```

**Why a separate endpoint?**
- No session cookie dependency (background HTTP)
- Minimal response (204 No Content saves battery)
- Token-based auth for background requests
- Can rate-limit differently than foreground

### Phase 4: Pekko Backend - Location Token Management

**New Table**: `location_tokens`
```sql
CREATE TABLE location_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  party_id UUID NOT NULL REFERENCES parties(id),
  token VARCHAR(64) NOT NULL UNIQUE,
  device_id VARCHAR(255),  -- Android device identifier
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  last_used_at TIMESTAMP,
  expires_at TIMESTAMP,
  revoked_at TIMESTAMP
);

CREATE INDEX idx_location_tokens_token ON location_tokens(token) WHERE revoked_at IS NULL;
```

**Token Generation** (in existing `NotificationController` or new endpoint):
```scala
// When user enables background location tracking via web UI
POST /api/location-tokens
  → Generate secure random token
  → Store in location_tokens table
  → Return token to web page
  → Web page passes token to Android via bridge component
```

### Phase 5: Actor System Integration

**No changes needed to actor hierarchy!**

The existing flow handles this perfectly:
1. `BackgroundLocationController` validates token, gets `personId`
2. Routes `ActiveUserRegistry.RouteToUser(personId, LocationUpdate(...))`
3. If user has no `ActiveUserActor` (app closed), one is spawned
4. `LocationManagerActor` → `LocationUpdatedActor` processes as usual
5. `location_history` records the update
6. Nearby places are found (if user has interests)

**Optional Enhancement**: Add `BackgroundLocationUpdate` message type to differentiate:
```scala
// In ActiveUserActor
case class BackgroundLocationUpdate(
  lat: Double,
  lon: Double,
  accuracy: Option[Double],
  deviceTimestamp: Long
) extends Command

// Handle differently if needed (e.g., skip WebSocket broadcast if no active session)
```

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  ANDROID APP (Background)                                           │
│                                                                     │
│  FusedLocationProviderClient                                        │
│         │                                                           │
│         │ (100m displacement trigger)                               │
│         ▼                                                           │
│  LocationComponent / LocationTrackingService                        │
│         │                                                           │
│         │ HTTP POST with X-Location-Token header                    │
└─────────┼───────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PEKKO BACKEND                                                      │
│                                                                     │
│  POST /api/location                                                 │
│         │                                                           │
│         ▼                                                           │
│  BackgroundLocationController                                       │
│         │ (validate token → personId)                               │
│         │                                                           │
│         ▼                                                           │
│  ActiveUserRegistry.RouteToUser(personId, LocationUpdate)           │
│         │                                                           │
│         ▼                                                           │
│  ActiveUserActor (spawned if not exists)                            │
│         │                                                           │
│         ▼                                                           │
│  LocationManagerActor                                               │
│         │                                                           │
│         ▼                                                           │
│  LocationUpdatedActor                                               │
│         │                                                           │
│         ├──► location_history (INSERT)                              │
│         ├──► parties.lonlatheight (UPDATE)                          │
│         │                                                           │
│         ▼                                                           │
│  NearbyPlacesForLocationActor                                       │
│         │                                                           │
│         ├──► place_suggestions (query/generate)                     │
│         ├──► Nebula graph queries                                   │
│         │                                                           │
│         ▼                                                           │
│  SessionManager.Broadcast (if user has active WebSocket)            │
│         │                                                           │
│         ▼                                                           │
│  [Push notification if interesting place found?]                    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Files to Create/Modify

### Android (can do now)
| File | Action | Description |
|------|--------|-------------|
| `LocationComponent.kt` | Modify | Add distance-based updates, auth token support |
| `LocationStorage.kt` | Create | Store auth token, personUuid, baseUrl |
| `LocationTrackingService.kt` | Create | ForegroundService for true background |
| `AndroidManifest.xml` | Modify | Add foreground service permissions |

### Pekko Backend (pekko-hotwire-native branch)
| File | Action | Description |
|------|--------|-------------|
| `BackgroundLocationController.scala` | Create | Lightweight location endpoint |
| `LocationTokenRepository.scala` | Create | Token CRUD operations |
| `Routes.scala` | Modify | Add `/api/location` route |
| `schema.sql` | Modify | Add `location_tokens` table |
| `Models.scala` | Modify | Add `BackgroundLocationUpdate` case class |

### Web (Stimulus controllers)
| File | Action | Description |
|------|--------|-------------|
| `location_controller.js` | Create | Bridge controller to start/stop tracking |
| Settings page | Modify | UI to enable background location + show token status |

---

## Security Considerations

1. **Token Security**: Location tokens should be:
   - Random 256-bit values (32 bytes, hex encoded = 64 chars)
   - Stored hashed in DB (like passwords)
   - Revocable per-device
   - Expiring (30 days? user configurable?)

2. **Rate Limiting**: Background endpoint should:
   - Max 1 update per 30 seconds per token
   - Max 1000 updates per day per user
   - Return 429 if exceeded

3. **Privacy**:
   - User must explicitly enable background tracking
   - Clear UI showing tracking is active
   - Easy disable from both app and web

---

## Testing Plan

1. **Unit Tests**: Token validation, location processing
2. **Integration Tests**: Full flow from HTTP to actor to DB
3. **Manual Tests**:
   - Walk 100m, verify update received
   - Kill app, walk 100m, verify update still received
   - Verify battery impact acceptable
   - Verify location accuracy reasonable

---

## Implementation Order

1. ✅ Android LocationComponent exists (interval-based)
2. **Next**: Modify Android for distance-based + token auth
3. **Then**: Create Pekko BackgroundLocationController
4. **Then**: Add location_tokens table and repository
5. **Then**: Create web UI for enabling/managing tracking
6. **Finally**: Add push notifications for interesting places

---

## Questions to Resolve

1. Should background updates trigger the full NearbyPlacesForLocationActor pipeline, or a lighter version?
2. What's the minimum accuracy threshold? (Ignore updates with accuracy > 100m?)
3. Should we batch background updates or process immediately?
4. Push notification when interesting place found in background?
