package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.blaha.groovitation.services.LocationWorker
import io.blaha.groovitation.services.LocationWorkerTestHooks
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * #798 test #1 of 4.
 *
 * Android framework contract:
 * During Doze, the system defers jobs with network constraints until a
 * maintenance window or Doze exits. On a real device the worker would
 * typically run when a maintenance window opens inside this test's
 * budget; on the CI emulator, Doze enforcement is weaker than on real
 * hardware and WorkManager is observed to run the worker anyway.
 * (https://developer.android.com/training/monitoring-device-state/doze-standby#understand_doze)
 *
 * What we assert here (per ticket acceptance wording): "assert the worker
 * eventually runs (WorkManager will retry at maintenance windows) OR log
 * that it doesn't so we have a documented baseline." Both observed
 * behaviors are acceptable — the failure cases are:
 *  - the enqueue itself throws / never registers a WorkInfo
 *  - the WorkInfo terminates in FAILED (real regression — worker crashed)
 *  - `dumpsys deviceidle force-idle` is rejected by the framework
 *
 * The test captures the observed state in an assertion message so the CI
 * log preserves the documented baseline regardless of which branch fired.
 *
 * Cleanup always runs `deviceidle unforce` so a red test never leaves the
 * emulator in forced Doze for the next job.
 */
@RunWith(AndroidJUnit4::class)
class DozeInstrumentedTest {

    @get:Rule
    val locationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LocationWorkerTestHooks.reset()
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        runShell("dumpsys deviceidle unforce")
        runShell("dumpsys deviceidle disable")
        runShell("dumpsys deviceidle enable")
        runShell("dumpsys battery reset")
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        LocationWorkerTestHooks.reset()
    }

    @Test
    fun oneShotWorkerBehaviorUnderForcedDozeIsObservableAndDoesNotCrash() {
        // Enter Doze. `force-idle` requires the device to look unplugged and
        // have the screen off — the sequence below matches AOSP's idle
        // reproducer script.
        runShell("dumpsys battery unplug")
        runShell("dumpsys deviceidle enable")
        val forceIdleOutput = runShell("dumpsys deviceidle force-idle")
        val idleState = runShell("dumpsys deviceidle get deep").trim()

        // The framework reliably accepts the idle-state transition on
        // every Android version we ship against. If this ever flips to
        // something like "ACTIVE" after force-idle, the test's premise is
        // invalid and we need to re-investigate.
        assertTrue(
            "Expected `dumpsys deviceidle get deep` to report an idle-family " +
                "state after force-idle, got '$idleState' (force-idle output: '$forceIdleOutput')",
            idleState.startsWith("IDLE")
        )

        val request = OneTimeWorkRequestBuilder<LocationWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        val uniqueName = "issue-798-doze-oneshot-${System.nanoTime()}"
        val workManager = WorkManager.getInstance(context)

        // Clear capturedOutcomes immediately before enqueue so the assertion
        // below is scoped to just this one-shot's emissions. Before the
        // 2026-04-24 self-cancel fix (human/scruff3-stop-self-cancel),
        // Application.onCreate's periodic enqueue was being silently
        // cancelled on every MainActivity.onResume and never emitted an
        // outcome — this test's "outcomes.size <= 1" assertion happened to
        // pass because of that bug. With the bug fixed, the periodic
        // legitimately runs during the test session and can emit its own
        // SKIPPED_NO_PERSON_UUID; scoping the capture to just-after-enqueue
        // preserves the test's real intent ("this one-shot produced exactly
        // one Outcome") without being coupled to app-startup side effects.
        LocationWorkerTestHooks.capturedOutcomes.clear()

        workManager.enqueueUniqueWork(
            uniqueName,
            androidx.work.ExistingWorkPolicy.REPLACE,
            request
        ).result.get(5, TimeUnit.SECONDS)

        // Observe: either the worker stays ENQUEUED (real-device Doze
        // defer behavior) or it reaches SUCCEEDED (emulator/flagged-off
        // Doze behavior — acceptable per ticket). FAILED or CANCELLED
        // would indicate a real regression.
        val deadline = System.currentTimeMillis() + 10_000
        var lastState: WorkInfo.State? = null
        while (System.currentTimeMillis() < deadline) {
            val infos = workManager.getWorkInfosForUniqueWork(uniqueName)
                .get(2, TimeUnit.SECONDS)
            val info = infos.firstOrNull()
            if (info != null) {
                lastState = info.state
                if (info.state.isFinished) break
            }
            Thread.sleep(200)
        }

        // Document the observed state on the CI log so future changes in
        // emulator Doze behavior are visible even when the test passes.
        println(
            "[#798 Doze] device deep-idle state=$idleState; " +
                "LocationWorker terminal state=$lastState"
        )

        assertNotNull(
            "Expected WorkManager to register a WorkInfo for the one-shot LocationWorker " +
                "while in forced Doze; no WorkInfo observed within 10s",
            lastState
        )
        // The only real regression is a FAILED/CANCELLED outcome — both
        // ENQUEUED (deferred) and SUCCEEDED (ran at maintenance window /
        // emulator behavior) are acceptable per ticket.
        val acceptableTerminalStates = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.SUCCEEDED
        )
        assertTrue(
            "LocationWorker ended in state=$lastState under forced Doze; " +
                "expected either deferred (ENQUEUED/BLOCKED/RUNNING) or completed (SUCCEEDED). " +
                "FAILED/CANCELLED indicates a crash or permission/config regression.",
            lastState in acceptableTerminalStates
        )

        // Sanity on the structured-outcome contract: if the worker DID run,
        // it should have emitted exactly one Outcome (either POSTED for a
        // well-configured test device, or one of the documented SKIPPED_*
        // paths for a missing prereq). Empty is also fine — the worker
        // never ran.
        val outcomes = LocationWorkerTestHooks.capturedOutcomes.toList()
        assertFalse(
            "LocationWorker emitted more than one Outcome in a single run: $outcomes",
            outcomes.size > 1
        )
    }

    /**
     * Runs a shell command and blocks until it finishes, returning stdout.
     * Reading the output of the ParcelFileDescriptor is what forces the
     * command to run to completion — `pfd.close()` alone returns before
     * the shell has actually executed the command, which is how the
     * earlier (#798 pipeline #10860) version of this test race-ordered
     * the deviceidle state-change against the subsequent enqueue.
     */
    private fun runShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }
}
