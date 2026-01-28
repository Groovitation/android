# Push Notification Plan: Background Location → New Site Discovery

## Overview

When a user's background location update causes discovery of a new site matching their interests, send a push notification to their device.

---

## Current State

### What Exists
- `place_suggestions` table tracks: `seen_at`, `dismissed_at`, `visited_at`, `score`, `reason`
- `SessionManager` knows which users have active WebSocket sessions
- `NearbyPlacesForLocationActor` already identifies NEW places (not in `place_suggestions`)
- Redis has `queueNotification()` infrastructure
- Android app has FCM token retrieval (`TokenStorage.fcmToken`)

### What's Missing
- `notification_tokens` table in PostgreSQL
- `PushService` to send FCM/APNs
- `notified_at` column in `place_suggestions`
- Logic to decide when to notify vs not

---

## When TO Send Notification

| Condition | Rationale |
|-----------|-----------|
| Background location update | User isn't looking at the app |
| No active WebSocket session | User would see it on map if session active |
| Place is NEW (just added to `place_suggestions`) | Don't re-notify about known places |
| `notified_at IS NULL` | Haven't notified about this place yet |
| `dismissed_at IS NULL` | User hasn't rejected this place |
| `score >= 0.5` (within ~1km) | Place is reasonably close |
| Location accuracy <= 150m | Confident about user's actual location |
| < 3 notifications in last hour | Rate limiting |
| User has notification preferences enabled | Respect user settings |

---

## When NOT TO Send Notification

| Condition | Rationale |
|-----------|-----------|
| **User has active session** | They'll see `ShowPlaceOnMap` in real-time on the map |
| **Place already notified** | `notified_at IS NOT NULL` - don't spam |
| **Place dismissed** | User explicitly said "not interested" |
| **Low score (< 0.2)** | Place is > 4km away, not relevant |
| **Poor accuracy (> 200m)** | Location might be wrong, could be misleading |
| **Rate limit exceeded** | > 5 notifications/day or > 3/hour |
| **Quiet hours** | 10pm - 8am local time (configurable) |
| **User just left this place** | Previous location was near this place |
| **User disabled notifications** | Per-user preference |
| **Place type mismatch** | e.g., bar notification at 9am |
| **Device token invalid/expired** | FCM returned error previously |

---

## Data Model Changes

### 1. Add `notification_tokens` Table

```sql
CREATE TABLE notification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id UUID NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    platform VARCHAR(20) NOT NULL,  -- 'android', 'ios', 'web'
    device_id VARCHAR(255),         -- Unique device identifier
    app_version VARCHAR(20),
    created_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP(6),
    failed_at TIMESTAMP(6),         -- Last FCM failure
    failure_count INT DEFAULT 0,
    CONSTRAINT unique_party_token UNIQUE (party_id, token)
);

CREATE INDEX idx_notification_tokens_party ON notification_tokens(party_id);
CREATE INDEX idx_notification_tokens_platform ON notification_tokens(platform);
```

### 2. Add `notified_at` to `place_suggestions`

```sql
ALTER TABLE place_suggestions
ADD COLUMN notified_at TIMESTAMP(6),
ADD COLUMN notification_id UUID;  -- Link to notification record

CREATE INDEX idx_place_suggestions_not_notified
ON place_suggestions(party_id)
WHERE notified_at IS NULL AND dismissed_at IS NULL;
```

### 3. Add `notification_log` Table (for rate limiting & history)

```sql
CREATE TABLE notification_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id UUID NOT NULL REFERENCES parties(id),
    notification_type VARCHAR(50) NOT NULL,  -- 'new_place', 'friend_activity', etc.
    place_suggestion_id UUID REFERENCES place_suggestions(id),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    sent_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP(6),
    opened_at TIMESTAMP(6),
    deep_link TEXT
);

CREATE INDEX idx_notification_log_party_time
ON notification_log(party_id, sent_at DESC);
```

### 4. Add `notification_preferences` Table

```sql
CREATE TABLE notification_preferences (
    party_id UUID PRIMARY KEY REFERENCES parties(id),
    new_places_enabled BOOLEAN DEFAULT TRUE,
    friend_activity_enabled BOOLEAN DEFAULT TRUE,
    quiet_hours_start TIME,          -- e.g., '22:00'
    quiet_hours_end TIME,            -- e.g., '08:00'
    max_per_day INT DEFAULT 10,
    max_per_hour INT DEFAULT 3,
    min_place_score FLOAT DEFAULT 0.3,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT NOW()
);
```

---

## Actor System Changes

### New: `PushNotificationActor`

Responsible for:
- Checking if notification should be sent (all the "when NOT to" conditions)
- Rate limiting per user
- Calling FCM/APNs
- Logging notifications
- Handling failures/retries

```
NearbyPlacesForLocationActor
    │
    │ (new place found, inserted into place_suggestions)
    │
    ▼
PushNotificationActor.MaybeNotify(
    userId: UUID,
    placeSuggestionId: UUID,
    placeId: UUID,
    placeName: String,
    reason: String,
    score: Double,
    isBackgroundUpdate: Boolean,
    locationAccuracy: Option[Double]
)
    │
    │ Checks:
    │   1. isBackgroundUpdate == true?
    │   2. SessionManager.hasActiveSession(userId) == false?
    │   3. notified_at IS NULL?
    │   4. score >= minScore?
    │   5. accuracy <= 150m?
    │   6. rate limit OK?
    │   7. not quiet hours?
    │   8. notifications enabled?
    │
    │ If all pass:
    │   - Get notification_token for user
    │   - Send FCM
    │   - UPDATE place_suggestions SET notified_at = NOW()
    │   - INSERT notification_log
    │
    ▼
FCM / APNs
```

### Modify: `NearbyPlacesForLocationActor`

After inserting new place into `place_suggestions`:

```scala
// Existing: broadcast to active sessions
sessionManager ! SessionManager.BroadcastToUser(userId, ShowPlaceOnMap(...))

// NEW: potentially send push notification
pushNotificationActor ! PushNotificationActor.MaybeNotify(
  userId = userId,
  placeSuggestionId = newSuggestionId,
  placeId = place.id,
  placeName = place.name,
  reason = s"Matches your interest in ${interest.name}",
  score = calculatedScore,
  isBackgroundUpdate = locationUpdate.updateReason == "significant_distance",
  locationAccuracy = locationUpdate.accuracy
)
```

### Modify: `LocationUpdate` Message

Add field to distinguish background vs foreground:

```scala
case class LocationUpdate(
  latitude: Double,
  longitude: Double,
  // ... existing fields ...
  updateReason: Option[String],  // "significant_distance", "foreground", etc.
  source: String = "foreground"  // NEW: "background_service", "foreground", "geofence"
)
```

---

## FCM Integration

### PushService.scala

```scala
class PushService(firebaseCredentials: String) {

  private val firebaseApp = FirebaseApp.initializeApp(
    FirebaseOptions.builder()
      .setCredentials(GoogleCredentials.fromStream(new FileInputStream(firebaseCredentials)))
      .build()
  )

  def sendToAndroid(token: String, title: String, body: String, deepLink: String): Future[SendResult] = {
    val message = Message.builder()
      .setToken(token)
      .setNotification(Notification.builder()
        .setTitle(title)
        .setBody(body)
        .build())
      .putData("url", deepLink)
      .putData("channel", "groovitation_places")
      .setAndroidConfig(AndroidConfig.builder()
        .setPriority(AndroidConfig.Priority.HIGH)
        .build())
      .build()

    Future {
      FirebaseMessaging.getInstance(firebaseApp).send(message)
    }
  }
}
```

### Notification Content

```
Title: "🍕 Pizza place nearby!"
Body: "Joe's Pizza matches your interest. 0.3km away."
Deep Link: /map?highlight={placeId}

-- or --

Title: "New place discovered"
Body: "The Record Store - Matches your interest in vinyl records"
Deep Link: /places/{placeId}
```

---

## Android Changes

### Already Done
- FCM token retrieval in `MainActivity.fetchFcmToken()`
- `TokenStorage.fcmToken` holds the token
- `GroovitationMessagingService` handles incoming notifications
- Deep link handling via `url` extra

### Needed
- Register token with server via `NotificationTokenComponent`
- Handle notification tap → navigate to `/map?highlight={placeId}`

---

## Flow Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│ ANDROID (Background)                                              │
│                                                                   │
│ LocationTrackingService                                           │
│   │ (100m significant change)                                     │
│   │                                                               │
│   ▼                                                               │
│ POST /api/location                                                │
│   { lat, lon, accuracy, updateReason: "significant_distance" }    │
└───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│ PEKKO BACKEND                                                     │
│                                                                   │
│ BackgroundLocationController                                      │
│   │                                                               │
│   ▼                                                               │
│ ActiveUserActor.LocationUpdate(source="background_service")       │
│   │                                                               │
│   ▼                                                               │
│ LocationManagerActor                                              │
│   │                                                               │
│   ▼                                                               │
│ NearbyPlacesForLocationActor                                      │
│   │                                                               │
│   ├─── Query places matching interests within 5km                 │
│   │                                                               │
│   ├─── Filter: NOT already in place_suggestions                   │
│   │                                                               │
│   ├─── INSERT new places into place_suggestions                   │
│   │                                                               │
│   ├─── SessionManager.BroadcastToUser (for active sessions)       │
│   │         │                                                     │
│   │         └─── [No active session for background update]        │
│   │                                                               │
│   └─── PushNotificationActor.MaybeNotify ◄─── NEW                 │
│             │                                                     │
│             ▼                                                     │
│         ┌─────────────────────────────────────┐                   │
│         │ DECISION LOGIC                      │                   │
│         │                                     │                   │
│         │ ✓ isBackgroundUpdate?               │                   │
│         │ ✓ No active WebSocket session?      │                   │
│         │ ✓ notified_at IS NULL?              │                   │
│         │ ✓ score >= 0.3?                     │                   │
│         │ ✓ accuracy <= 150m?                 │                   │
│         │ ✓ Rate limit OK?                    │                   │
│         │ ✓ Not quiet hours?                  │                   │
│         │ ✓ User enabled notifications?       │                   │
│         └─────────────────────────────────────┘                   │
│                       │                                           │
│                       ▼ (all checks pass)                         │
│               PushService.sendToAndroid()                         │
│                       │                                           │
│                       ├─── UPDATE place_suggestions               │
│                       │    SET notified_at = NOW()                │
│                       │                                           │
│                       └─── INSERT notification_log                │
└───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│ FCM (Google)                                                      │
│   │                                                               │
│   ▼                                                               │
│ ANDROID DEVICE                                                    │
│                                                                   │
│ GroovitationMessagingService.onMessageReceived()                  │
│   │                                                               │
│   ▼                                                               │
│ Notification: "🎸 Guitar shop nearby!"                            │
│              "Strings & Things - 0.4km away"                      │
│   │                                                               │
│   ▼ (user taps)                                                   │
│ MainActivity → /map?highlight={placeId}                           │
└───────────────────────────────────────────────────────────────────┘
```

---

## Edge Cases & Special Handling

### 1. User Opens App While Notification Pending
- Notification might arrive after user opened map
- Solution: When session registers, mark recent `place_suggestions` as `seen_at = NOW()`

### 2. Multiple Devices
- User might have Android phone + tablet
- Send to all registered tokens for user
- Track `notification_log` per-token to avoid duplicate counts

### 3. Token Rotation
- FCM tokens can change
- On app launch, always re-register token
- Delete old tokens that fail repeatedly

### 4. Batch Discoveries
- Single location update might find 5 new places
- Don't send 5 notifications
- Send 1: "5 new places nearby!" with deep link to map

### 5. "Leaving" vs "Arriving"
- If previous location was near place, user is leaving
- Only notify when arriving (wasn't near before, now is)
- Track `last_notified_location` per user

---

## Implementation Order

1. **Schema changes** - Add tables/columns (pekko-hotwire-native branch)
2. **NotificationTokenRepository** - CRUD for tokens
3. **Token registration endpoint** - `POST /api/notification-tokens`
4. **PushService** - FCM integration
5. **PushNotificationActor** - Decision logic
6. **Modify NearbyPlacesForLocationActor** - Call PushNotificationActor
7. **Android: Register token** - On app launch, send token to server
8. **Test end-to-end** - Walk around, verify notifications

---

## Testing Checklist

- [ ] Background update with no active session → notification sent
- [ ] Foreground update → no notification (user sees map)
- [ ] Active WebSocket session → no notification
- [ ] Same place twice → only 1 notification
- [ ] Dismissed place → no notification
- [ ] Rate limit → 4th notification in hour blocked
- [ ] Quiet hours → no notification at 2am
- [ ] Low accuracy (500m) → no notification
- [ ] Low score (5km away) → no notification
- [ ] Tap notification → opens map with place highlighted
- [ ] Multiple places found → single "X places nearby" notification
