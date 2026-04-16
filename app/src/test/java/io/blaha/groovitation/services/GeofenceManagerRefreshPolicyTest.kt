package io.blaha.groovitation.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceManagerRefreshPolicyTest {

    @Test
    fun refreshRemovalIdsPreserveTrackingGeofence() {
        val ids = setOf(
            "interest:coffee",
            GeofenceManager.TRACKING_GEOFENCE_ID,
            "interest:pizza"
        )

        val removalIds = GeofenceManager.refreshRemovalIds(ids)

        assertEquals(setOf("interest:coffee", "interest:pizza"), removalIds.toSet())
        assertFalse(removalIds.contains(GeofenceManager.TRACKING_GEOFENCE_ID))
    }

    @Test
    fun fullResetRemovalIdsIncludeTrackingGeofence() {
        val ids = setOf("interest:coffee", "interest:pizza")

        val removalIds = GeofenceManager.fullResetRemovalIds(ids)

        assertTrue(removalIds.contains(GeofenceManager.TRACKING_GEOFENCE_ID))
        assertEquals(
            setOf("interest:coffee", "interest:pizza", GeofenceManager.TRACKING_GEOFENCE_ID),
            removalIds.toSet()
        )
    }
}
