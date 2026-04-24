package io.blaha.groovitation.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the structured-outcome contract for [LocationWorker.Outcome].
 *
 * The point of the enum is to make every `doWork()` exit observable from
 * tests and from prod logs (greppable on `LocationWorker outcome=`). If
 * someone adds a new exit path to `doWork()` and forgets to wire it through
 * [LocationWorker.emitOutcome], that exit becomes invisible again — exactly
 * the silent-skip regression class this enum was created to prevent. These
 * tests guard the wire-up shape, not the specific outcomes (which are
 * exercised in the instrumented suite).
 */
@RunWith(RobolectricTestRunner::class)
class LocationWorkerOutcomeTest {

    @After
    fun tearDown() {
        LocationWorkerTestHooks.reset()
    }

    @Test
    fun `emitOutcome records each call into capturedOutcomes in order`() {
        LocationWorker.emitOutcome(LocationWorker.Outcome.SKIPPED_NO_PERSON_UUID)
        LocationWorker.emitOutcome(LocationWorker.Outcome.POSTED, "code=200")
        LocationWorker.emitOutcome(LocationWorker.Outcome.HTTP_FAILED, "code=503")

        assertEquals(
            listOf(
                LocationWorker.Outcome.SKIPPED_NO_PERSON_UUID,
                LocationWorker.Outcome.POSTED,
                LocationWorker.Outcome.HTTP_FAILED
            ),
            LocationWorkerTestHooks.capturedOutcomes.toList()
        )
    }

    @Test
    fun `every Outcome value is structurally emittable without throwing`() {
        // Ensures any future enum value plugged into emitOutcome's `when`
        // gets a log-level mapping. A new Outcome added without a branch
        // here would fall through and (depending on Kotlin compile mode)
        // either error at compile time or fail this test.
        LocationWorker.Outcome.values().forEach { outcome ->
            LocationWorker.emitOutcome(outcome)
        }

        assertEquals(
            LocationWorker.Outcome.values().size,
            LocationWorkerTestHooks.capturedOutcomes.size
        )
        assertTrue(
            "Every captured outcome should equal the value passed in",
            LocationWorker.Outcome.values().toList() == LocationWorkerTestHooks.capturedOutcomes.toList()
        )
    }

    @Test
    fun `Outcome enum covers the silent-skip cases that motivated this change`() {
        // If you find yourself deleting one of these from the enum, confirm
        // there's no remaining doWork() path that needs to emit it. The
        // SKIPPED_NO_AUTH case especially is the original symptom — that
        // branch existed silently for months before this enum.
        val names = LocationWorker.Outcome.values().map { it.name }.toSet()
        assertTrue("POSTED must exist", "POSTED" in names)
        assertTrue("SKIPPED_NO_PERSON_UUID must exist", "SKIPPED_NO_PERSON_UUID" in names)
        assertTrue("SKIPPED_NO_LOCATION_PERMISSION must exist", "SKIPPED_NO_LOCATION_PERMISSION" in names)
        assertTrue("SKIPPED_NO_LOCATION_FIX must exist", "SKIPPED_NO_LOCATION_FIX" in names)
        assertTrue("SKIPPED_NO_AUTH must exist", "SKIPPED_NO_AUTH" in names)
        assertTrue("HTTP_FAILED must exist", "HTTP_FAILED" in names)
        assertTrue("PERMISSION_REVOKED_AT_RUNTIME must exist", "PERMISSION_REVOKED_AT_RUNTIME" in names)
    }
}
