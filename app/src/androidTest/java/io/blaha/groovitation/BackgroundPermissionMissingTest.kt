package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.blaha.groovitation.services.LocationWorker
import io.blaha.groovitation.services.LocationWorkerTestHooks
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * #798 test #3 of 4.
 *
 * Android framework contract (API 29+):
 * `ACCESS_BACKGROUND_LOCATION` is a separate runtime permission required
 * for location access while the app is in the background. Without it,
 * FusedLocationProvider either throws [SecurityException] or silently
 * returns null during background execution.
 * (https://developer.android.com/training/location/permissions#background)
 *
 * What we assert here: with FINE + COARSE granted but BACKGROUND revoked,
 * [LocationWorker.doWork] short-circuits into the new gap-check outcome
 * [LocationWorker.Outcome.SKIPPED_NO_BACKGROUND_LOCATION_PERMISSION] and
 * never reaches the HTTP POST path (MockWebServer receives zero requests).
 *
 * Motivation: paired with the onboarding re-prompt flow landed via
 * `human/scruff3-background-location-reprompt`. Previously this case
 * fell through to SKIPPED_NO_LOCATION_FIX or PERMISSION_REVOKED_AT_RUNTIME,
 * which conflated "permission missing" with "transient radio/GPS
 * failure" and made Ben's 2026-04-23 Cohen House outage harder to grep for.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundPermissionMissingTest {

    @get:Rule
    val locationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val packageName: String
        get() = context.packageName

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        // The gap-check is API 29+ gated. On older emulators the permission
        // doesn't exist as a distinct runtime permission so the behavior
        // under test is not meaningful.
        assumeTrue(
            "ACCESS_BACKGROUND_LOCATION gap-check requires API 29+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )

        LocationWorkerTestHooks.reset()
        // Revoke BACKGROUND. GrantPermissionRule above grants FINE + COARSE
        // but does NOT auto-grant BACKGROUND (it's a separate permission on
        // API 29+). Explicit revoke ensures a clean baseline regardless of
        // prior test state.
        runShell("pm revoke $packageName android.permission.ACCESS_BACKGROUND_LOCATION")

        mockServer = MockWebServer().apply { start() }
        LocationWorkerTestHooks.enabled = true
        LocationWorkerTestHooks.baseUrlOverride = mockServer.url("/").toString().trimEnd('/')
        LocationWorkerTestHooks.suppressGeofence = true

        // Seed a person_uuid so the worker's earlier gate (NO_PERSON_UUID)
        // doesn't fire first and hide the path under test.
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("person_uuid", "test-person-uuid")
            .putString("location_token", "test-location-token")
            .apply()
    }

    @After
    fun tearDown() {
        LocationWorkerTestHooks.reset()
        mockServer.shutdown()
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun workerSkipsWithBackgroundPermissionOutcomeAndDoesNotPost() {
        val result = runBlocking {
            TestListenableWorkerBuilder<LocationWorker>(context).build().doWork()
        }
        assertEquals(ListenableWorker.Result.success(), result)

        val outcomes = LocationWorkerTestHooks.capturedOutcomes.toList()
        assertTrue(
            "Expected outcome list to include SKIPPED_NO_BACKGROUND_LOCATION_PERMISSION, " +
                "got $outcomes",
            outcomes.contains(LocationWorker.Outcome.SKIPPED_NO_BACKGROUND_LOCATION_PERMISSION)
        )
        assertTrue(
            "Expected NOT POSTED when BACKGROUND permission is missing, got $outcomes",
            !outcomes.contains(LocationWorker.Outcome.POSTED)
        )

        // No HTTP request should ever reach the server in the BACKGROUND-missing
        // branch — the gap-check fires before the HTTP path.
        assertEquals(
            "MockWebServer must receive zero requests when BACKGROUND permission is missing",
            0, mockServer.requestCount
        )
        assertNull(
            "takeRequest must not return a request in the BACKGROUND-missing branch",
            mockServer.takeRequest(1, TimeUnit.SECONDS)
        )
    }

    /**
     * Runs a shell command and blocks until it finishes, returning stdout.
     * Reading the output of the ParcelFileDescriptor is what forces the
     * command to run to completion — `pfd.close()` alone returns before
     * the shell has actually executed, which could let `pm revoke` race
     * the test body's worker-driver call.
     */
    private fun runShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }
}
