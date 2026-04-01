package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.blaha.groovitation.services.LocationWorker
import io.blaha.groovitation.services.LocationWorkerTestHooks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [LocationWorker.enqueueOneShot] through the real WorkManager
 * pipeline with deterministic test hooks so CI can verify background-sourced
 * location uploads without Play Services or a live backend.
 */
@RunWith(AndroidJUnit4::class)
class LocationWorkerInstrumentedTest {

    @get:Rule
    val locationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocationWorkerTestHooks.reset()

        // Seed prerequisites the worker reads from SharedPreferences
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("person_uuid", "test-person-uuid")
            .putString("session_cookie", "test=session")
            .apply()

        // Inject deterministic location and suppress real I/O
        LocationWorkerTestHooks.enabled = true
        LocationWorkerTestHooks.overrideLocation = Location("test-hook").apply {
            latitude = 31.762
            longitude = -106.485
            accuracy = 12.0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        LocationWorkerTestHooks.suppressGeofence = true
    }

    @After
    fun tearDown() {
        LocationWorkerTestHooks.reset()
        // Clean up seeded prefs
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun oneShotWorkerEmitsBackgroundSourcedUpload() {
        LocationWorker.enqueueOneShot(context)

        // Poll until the unique one-shot work reaches a terminal state
        val deadline = System.currentTimeMillis() + 30_000
        var finalState: WorkInfo.State? = null
        while (System.currentTimeMillis() < deadline) {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(LocationWorker.WORK_NAME_ONESHOT)
                .get()
            if (infos.isNotEmpty()) {
                val state = infos.last().state
                if (state.isFinished) {
                    finalState = state
                    break
                }
            }
            Thread.sleep(250)
        }

        // Worker must complete successfully, not hang
        assertEquals("Worker should succeed", WorkInfo.State.SUCCEEDED, finalState)

        // Exactly one background upload must have been captured
        assertEquals("Expected one captured upload", 1, LocationWorkerTestHooks.capturedUploads.size)

        val upload = LocationWorkerTestHooks.capturedUploads[0]
        assertEquals("test-person-uuid", upload.personUuid)

        val payload = upload.payload
        assertEquals("background", payload.getString("source"))
        assertEquals("android", payload.getString("deviceType"))
        assertEquals(31.762, payload.getDouble("latitude"), 0.001)
        assertEquals(-106.485, payload.getDouble("longitude"), 0.001)
        assertEquals(12.0, payload.getDouble("accuracy"), 0.1)
        assertTrue("payload must include timestamp", payload.has("timestamp"))
        assertTrue("payload must include deviceId", payload.has("deviceId"))
    }
}
