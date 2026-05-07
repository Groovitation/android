package io.blaha.groovitation.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice E (#1043): unit-level coverage for the read/write boundary on the
 * activity-recognition cache. The receiver writes; LocationWorker reads
 * via [ActivityRecognitionTracker.recentActivityOrNull]. The boundary
 * behaviors that matter for the contract are: missing → null, stale → null,
 * unknown wire-name → null, fresh → the cached value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ActivityRecognitionTrackerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `recentActivityOrNull returns null when nothing has been recorded`() {
        assertNull(ActivityRecognitionTracker.recentActivityOrNull(context))
    }

    @Test
    fun `recordLatestActivity persists and is read back when fresh`() {
        ActivityRecognitionTracker.recordLatestActivity(context, "in_vehicle", timestamp = 1_000L)
        assertEquals(
            "in_vehicle",
            ActivityRecognitionTracker.recentActivityOrNull(context, nowMs = 2_000L)
        )
    }

    @Test
    fun `stale activity beyond the staleness window is suppressed`() {
        ActivityRecognitionTracker.recordLatestActivity(context, "in_vehicle", timestamp = 1_000L)
        val tooLate = 1_000L + ActivityRecognitionTracker.STALE_AFTER_MS + 1L
        assertNull(ActivityRecognitionTracker.recentActivityOrNull(context, nowMs = tooLate))
    }

    @Test
    fun `unknown wire-name is refused at write time`() {
        ActivityRecognitionTracker.recordLatestActivity(context, "tilting", timestamp = 1_000L)
        assertNull(ActivityRecognitionTracker.recentActivityOrNull(context, nowMs = 1_500L))
    }

    @Test
    fun `subsequent record overwrites previous activity`() {
        ActivityRecognitionTracker.recordLatestActivity(context, "in_vehicle", timestamp = 1_000L)
        ActivityRecognitionTracker.recordLatestActivity(context, "still", timestamp = 2_000L)
        assertEquals(
            "still",
            ActivityRecognitionTracker.recentActivityOrNull(context, nowMs = 2_500L)
        )
    }
}
