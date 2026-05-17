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

    // #1185: dismissal-radius resolution for the paired-geofence outer-EXIT
    // fence. Server is authoritative when it provides `dismissalRadiusMeters`;
    // older backend builds fall back to a 1.5× local multiplier so a new app
    // paired with an old backend still gets the 1.5× hysteresis margin
    // instead of regressing to the single-fence boundary-cancel behaviour.

    @Test
    fun resolveDismissalRadiusUsesServerValueWhenPresent() {
        val resolved = GeofenceManager.resolveDismissalRadius(
            innerRadius = 300f,
            serverDismissalRadius = 480.0
        )
        assertEquals(480f, resolved, 0.0001f)
    }

    @Test
    fun resolveDismissalRadiusFallsBackTo15xWhenServerOmitsField() {
        // Mixed-rollout safety: new app + old backend that doesn't yet emit
        // `dismissalRadiusMeters`. The client multiplies the inner radius
        // itself so the user still sees hysteresis on dismiss.
        val resolved = GeofenceManager.resolveDismissalRadius(
            innerRadius = 300f,
            serverDismissalRadius = 0.0
        )
        assertEquals(450f, resolved, 0.0001f)
    }

    @Test
    fun resolveDismissalRadiusFallsBackForSmallRadii() {
        // 50 m floor (`MinRadiusMeters`) → 75 m dismiss.
        val resolved = GeofenceManager.resolveDismissalRadius(
            innerRadius = 50f,
            serverDismissalRadius = 0.0
        )
        assertEquals(75f, resolved, 0.0001f)
    }

    @Test
    fun resolveDismissalRadiusIsStrictlyGreaterThanInner() {
        // No zero-margin dismiss: the outer fence must always sit past the
        // inner so a user oscillating at the inner boundary doesn't trip
        // EXIT immediately.
        for (inner in listOf(50f, 120f, 200f, 300f)) {
            val outer = GeofenceManager.resolveDismissalRadius(inner, 0.0)
            assertTrue(
                "Outer ($outer) must exceed inner ($inner)",
                outer > inner
            )
        }
    }
}
