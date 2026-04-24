package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import io.blaha.groovitation.services.LocationWorker
import io.blaha.groovitation.services.LocationWorkerTestHooks
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * #770: exercises [LocationWorker] through the real HTTP path by redirecting
 * the POST at an in-process [MockWebServer]. Previously this test enabled a
 * test hook that captured the payload and skipped the POST entirely — that
 * shape could not catch Ben's 2026-04-23 outage, where
 * [io.blaha.groovitation.services.LocationTrackingService.resolveLocationAuth]
 * returned null and the worker returned `Result.success` without ever hitting
 * the wire. The new shape proves the POST actually happens and that the
 * silent-skip branch is observable via [LocationWorker.Outcome].
 *
 * Uses [TestListenableWorkerBuilder] to drive the worker in-process rather
 * than round-tripping through [androidx.work.WorkManager]'s scheduler.
 * Reasons (both surfaced by CI pipeline #10836):
 *  - The app's `Application.onCreate` enqueues the periodic LocationWorker,
 *    which would otherwise race our test's one-shot against the MockWebServer
 *    and inflate the request count (the exact failure the first version hit).
 *  - In-process execution is deterministic: no polling for terminal state,
 *    no retry semantics, no slot contention with Play Services.
 * The worker's `doWork` body is the same code path production runs, so the
 * HTTP-path coverage the ticket asks for is fully exercised.
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

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        LocationWorkerTestHooks.reset()

        // #770 / CI pipeline #10836 + #10840: the app's GroovitationApplication.onCreate
        // enqueues the periodic LocationWorker at process start (with a 0 initial
        // delay, per WorkManager defaults). Even with TestListenableWorkerBuilder
        // running our test's doWork in-process, that periodic fires on WorkManager's
        // own thread and, once baseUrlOverride is set, also posts to our
        // MockWebServer — which is what pushed the positive test's requestCount to
        // 2. Cancel all LocationWorker work and wait for every WorkInfo to be
        // finished BEFORE enabling hooks. After this point the only code that can
        // drive the worker is the explicit TestListenableWorkerBuilder call.
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        waitForAllLocationWorkerWorkToBeTerminal()

        mockServer = MockWebServer().apply { start() }

        // Seed prerequisites the worker reads from SharedPreferences. Positive
        // tests also seed KEY_LOCATION_TOKEN so resolveLocationAuth succeeds;
        // the negative test clears it explicitly.
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("person_uuid", "test-person-uuid")
            .putString("session_cookie", "_user_interface_session=test-session-cookie")
            .putString("location_token", "test-location-token")
            .apply()

        // Inject deterministic location and point the HTTP path at the mock
        // server. Suppressing geofence side effects keeps the test independent
        // of GeofenceManager internals.
        LocationWorkerTestHooks.enabled = true
        LocationWorkerTestHooks.overrideLocation = Location("test-hook").apply {
            latitude = 31.762
            longitude = -106.485
            accuracy = 12.0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        LocationWorkerTestHooks.suppressGeofence = true
        LocationWorkerTestHooks.baseUrlOverride = mockServer.url("/").toString().trimEnd('/')
    }

    /**
     * Polls `WorkManager.getWorkInfosByTag`-equivalent state until every
     * LocationWorker instance has reached a terminal state. `cancelAllWork`
     * returns an Operation that completes once the cancellation is *scheduled*,
     * but a work item that's already RUNNING is allowed to finish — we wait
     * here until it has. 5-second budget is generous; a stable tree cancels
     * within tens of milliseconds.
     */
    private fun waitForAllLocationWorkerWorkToBeTerminal() {
        val workManager = WorkManager.getInstance(context)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val periodic = workManager
                .getWorkInfosForUniqueWork("groovitation_location_periodic")
                .get(2, TimeUnit.SECONDS)
            val oneshot = workManager
                .getWorkInfosForUniqueWork(LocationWorker.WORK_NAME_ONESHOT)
                .get(2, TimeUnit.SECONDS)
            val allTerminal = (periodic + oneshot).all { it.state.isFinished }
            if (allTerminal) return
            Thread.sleep(100)
        }
    }

    @After
    fun tearDown() {
        LocationWorkerTestHooks.reset()
        mockServer.shutdown()
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun oneShotWorkerPostsBackgroundSourcedLocationToServer() {
        mockServer.enqueue(MockResponse().setResponseCode(204))

        val result = runBlocking {
            TestListenableWorkerBuilder<LocationWorker>(context).build().doWork()
        }
        assertEquals(ListenableWorker.Result.success(), result)

        // Exactly one POST must reach the server. Short timeout so a
        // regression surfaces as a null recorded request instead of a hang.
        val recorded: RecordedRequest? = mockServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("Expected LocationWorker to POST to the server", recorded)
        assertEquals(1, mockServer.requestCount)

        assertEquals("POST", recorded!!.method)
        assertEquals("/people/test-person-uuid/location", recorded.path)
        assertEquals("test-location-token", recorded.getHeader("X-Groovitation-Location-Token"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("background-gps", body.getString("source"))
        assertEquals("android", body.getString("deviceType"))
        assertEquals(31.762, body.getDouble("latitude"), 0.001)
        assertEquals(-106.485, body.getDouble("longitude"), 0.001)
        assertEquals(12.0, body.getDouble("accuracy"), 0.1)
        assertTrue("payload must include timestamp", body.has("timestamp"))
        assertTrue("payload must include deviceId", body.has("deviceId"))

        // The outcome enum is the observable signal prod greps on — assert
        // it lands on POSTED, not on one of the silent-skip paths.
        val outcomes = LocationWorkerTestHooks.capturedOutcomes.toList()
        assertTrue(
            "Expected outcomes to include POSTED, got $outcomes",
            outcomes.contains(LocationWorker.Outcome.POSTED)
        )
        assertTrue(
            "Outcomes must not include any silent-skip path, got $outcomes",
            outcomes.none { it == LocationWorker.Outcome.SKIPPED_NO_AUTH }
        )
    }

    @Test
    fun oneShotWorkerDoesNotPostAndMarksSkippedWhenNoAuthAvailable() {
        // Clear every auth source resolveLocationAuth inspects: stored
        // location token, stored session cookie, and (by not driving a
        // WebView in the instrumented context) live cookies. This is the
        // exact shape of the 2026-04-23 outage — worker runs, returns
        // Result.success, nothing hits the wire.
        context.getSharedPreferences("location_tracking_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("session_cookie")
            .remove("location_token")
            .apply()

        val result = runBlocking {
            TestListenableWorkerBuilder<LocationWorker>(context).build().doWork()
        }
        assertEquals(ListenableWorker.Result.success(), result)

        // No request must have reached the server. Short timeout + assertNull
        // — MockWebServer's takeRequest returns null on timeout without
        // failing the test.
        val recorded: RecordedRequest? = mockServer.takeRequest(1, TimeUnit.SECONDS)
        assertNull("Worker with no auth must not hit the wire", recorded)
        assertEquals(0, mockServer.requestCount)

        // Silent-skip must now be a first-class observable outcome. This is
        // the assertion that would have caught the outage — prod had one
        // Log.w line and no structured signal, CI had no signal at all, and
        // the enum-based capture now gives both a test hook and a grep target.
        val outcomes = LocationWorkerTestHooks.capturedOutcomes.toList()
        assertTrue(
            "Expected SKIPPED_NO_AUTH in outcomes, got $outcomes",
            outcomes.contains(LocationWorker.Outcome.SKIPPED_NO_AUTH)
        )
        assertTrue(
            "Expected no POSTED outcome when auth is absent, got $outcomes",
            outcomes.none { it == LocationWorker.Outcome.POSTED }
        )
    }
}
