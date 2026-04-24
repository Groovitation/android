package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.blaha.groovitation.services.BootReceiver
import io.blaha.groovitation.services.LocationWorkerTestHooks
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * #798 test #4 of 4.
 *
 * Android framework contract:
 * WorkManager's periodic work is persisted across reboots by the library,
 * but only if the process has been started at least once after boot.
 * On our app [BootReceiver] handles `ACTION_BOOT_COMPLETED` and explicitly
 * re-enqueues [io.blaha.groovitation.services.LocationWorker]'s periodic
 * and one-shot jobs. Without this, a reboot while the process is in a
 * stopped state would leave periodic work in limbo until something else
 * launches the app.
 * (https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED)
 *
 * What we assert here: after enabling location tracking and cancelling
 * all work, hand-delivering an ACTION_BOOT_COMPLETED intent to
 * [BootReceiver] re-enqueues the unique periodic work
 * `groovitation_location_periodic` into [WorkInfo.State.ENQUEUED].
 *
 * We invoke the receiver directly (rather than `am broadcast
 * android.intent.action.BOOT_COMPLETED`) because protected broadcasts can
 * only be sent by the system uid, and instrumentation doesn't have that
 * privilege. Direct invocation exercises the exact same entry point the
 * OS uses at boot, minus the protected-broadcast routing.
 */
@RunWith(AndroidJUnit4::class)
class BootReceiverReEnqueueTest {

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

        // Prerequisite: BootReceiver bails early if tracking isn't enabled,
        // so seed the flag that LocationTrackingService.isEnabled() reads.
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("tracking_enabled", true)
            .putString("person_uuid", "test-person-uuid")
            .apply()

        // Start from a clean WorkManager state so the post-boot ENQUEUED
        // assertion can't be satisfied by leftover periodic work from the
        // app's onCreate.
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        LocationWorkerTestHooks.reset()
    }

    @Test
    fun bootReceiverReEnqueuesPeriodicWorkOnBootCompleted() {
        // Sanity check: the unique work should not be scheduled before the
        // broadcast.
        val workManager = WorkManager.getInstance(context)
        val preBoot = workManager.getWorkInfosForUniqueWork("groovitation_location_periodic")
            .get(5, TimeUnit.SECONDS)
        assertTrue(
            "Expected no periodic work before BOOT_COMPLETED, got $preBoot",
            preBoot.isEmpty() || preBoot.all { it.state.isFinished }
        )

        // Hand-deliver the intent the OS would send at boot.
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // BootReceiver's enqueueUniquePeriodicWork returns immediately; the
        // resulting WorkInfo record should appear in ENQUEUED state within
        // a short budget.
        val deadline = System.currentTimeMillis() + 5_000
        var lastInfos: List<WorkInfo> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            lastInfos = workManager.getWorkInfosForUniqueWork("groovitation_location_periodic")
                .get(2, TimeUnit.SECONDS)
            if (lastInfos.any { it.state == WorkInfo.State.ENQUEUED }) break
            Thread.sleep(100)
        }

        assertNotNull("Expected periodic-work info list to be non-null", lastInfos)
        assertTrue(
            "Expected a periodic WorkInfo to exist after BOOT_COMPLETED, got $lastInfos",
            lastInfos.isNotEmpty()
        )
        assertTrue(
            "Expected periodic work to be ENQUEUED after BOOT_COMPLETED, got " +
                lastInfos.map { it.state },
            lastInfos.any { it.state == WorkInfo.State.ENQUEUED }
        )
    }
}
