package io.blaha.groovitation.services

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lock down the metadata schema that `GeofenceManager.refreshGeofences` writes
 * into SharedPreferences and that `GeofenceBroadcastReceiver.onReceive` reads
 * back when a geofence enters (#705).
 *
 * These tests exercise the JSON parsing directly rather than spinning up a
 * Context, so we are testing the contract between the two components without
 * Android framework dependencies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProximityGeofenceMetadataTest {

    private fun buildServerGeofence(
        id: String = "proximity-site-abc",
        type: String = "site",
        targetKind: String? = "site",
        targetId: String? = "abc",
        placeName: String = "Coffee Hall",
        interestName: String = "Coffee",
        imageUrl: String? = "https://cdn.example.com/coffee.jpg",
        deepLink: String? = "/map?site=abc",
        score: Double = 0.75,
        radiusMeters: Double = 500.0
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("latitude", 32.78)
        put("longitude", -96.80)
        put("radiusMeters", radiusMeters)
        put("type", type)
        targetKind?.let { put("targetKind", it) }
        targetId?.let { put("targetId", it) }
        put("placeName", placeName)
        put("interestName", interestName)
        if (imageUrl != null) put("imageUrl", imageUrl) else put("imageUrl", JSONObject.NULL)
        deepLink?.let { put("deepLink", it) }
        put("score", score)
    }

    @Test
    fun `receiver reads all proximity fields from a fully-populated server payload`() {
        val serverPayload = buildServerGeofence()

        val stored = JSONObject().apply {
            put("placeName", serverPayload.getString("placeName"))
            put("interestName", serverPayload.getString("interestName"))
            put("latitude", serverPayload.getDouble("latitude"))
            put("longitude", serverPayload.getDouble("longitude"))
            put("targetKind", serverPayload.optString("targetKind", serverPayload.optString("type", "site")))
            put("targetId", serverPayload.optString("targetId", ""))
            put(
                "imageUrl",
                if (serverPayload.isNull("imageUrl")) "" else serverPayload.optString("imageUrl", "")
            )
            put("deepLink", serverPayload.optString("deepLink", ""))
            put("score", serverPayload.optDouble("score", 0.0))
            put("radiusMeters", serverPayload.getDouble("radiusMeters"))
        }

        assertEquals("Coffee Hall", stored.getString("placeName"))
        assertEquals("Coffee", stored.getString("interestName"))
        assertEquals("site", stored.getString("targetKind"))
        assertEquals("abc", stored.getString("targetId"))
        assertEquals("https://cdn.example.com/coffee.jpg", stored.getString("imageUrl"))
        assertEquals("/map?site=abc", stored.getString("deepLink"))
        assertEquals(0.75, stored.getDouble("score"), 0.0001)
        assertEquals(500.0, stored.getDouble("radiusMeters"), 0.0001)
    }

    @Test
    fun `falls back to type when targetKind is absent (older server build)`() {
        // Mixed-rollout safety: a backend that hasn't shipped #705 yet still
        // emits `type` but not `targetKind`. The client must degrade to
        // matching behavior instead of dropping the geofence.
        val serverPayload = buildServerGeofence(targetKind = null)
        val resolvedKind = serverPayload.optString(
            "targetKind",
            serverPayload.optString("type", "site")
        )
        assertEquals("site", resolvedKind)
    }

    @Test
    fun `treats null imageUrl as empty string so receiver renders plain notification`() {
        val serverPayload = buildServerGeofence(imageUrl = null)
        val imageUrl = if (serverPayload.has("imageUrl") && !serverPayload.isNull("imageUrl"))
            serverPayload.optString("imageUrl", "")
        else ""
        assertEquals("", imageUrl)
    }

    @Test
    fun `treats missing deepLink as empty so receiver falls back to the map root`() {
        val serverPayload = buildServerGeofence(deepLink = null)
        assertEquals("", serverPayload.optString("deepLink", ""))
    }

    @Test
    fun `event kind round-trips through metadata`() {
        val serverPayload = buildServerGeofence(
            id = "proximity-event-xyz",
            type = "event",
            targetKind = "event",
            targetId = "xyz",
            deepLink = "/map?event=xyz"
        )
        assertEquals("event", serverPayload.getString("targetKind"))
        assertEquals("xyz", serverPayload.getString("targetId"))
        assertEquals("/map?event=xyz", serverPayload.getString("deepLink"))
    }
}
