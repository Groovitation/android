package io.blaha.groovitation.services

import android.location.Location
import org.json.JSONObject

/**
 * Test seam for [LocationWorker]. When [enabled] is true the worker uses
 * injected location data, captures upload payloads, and skips geofence
 * operations — allowing deterministic CI verification of the one-shot
 * background-location path without Play Services or a live backend.
 */
internal object LocationWorkerTestHooks {

    data class CapturedUpload(
        val personUuid: String,
        val payload: JSONObject
    )

    /** Master switch — production code checks this before every hook. */
    @Volatile
    var enabled: Boolean = false

    /** When non-null and [enabled], returned instead of a real fused-location fix. */
    @Volatile
    var overrideLocation: Location? = null

    /** Uploads captured in place of real HTTP POST calls. Thread-safe list. */
    val capturedUploads: MutableList<CapturedUpload> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** When true and [enabled], geofence register/refresh calls are skipped. */
    @Volatile
    var suppressGeofence: Boolean = false

    /**
     * Outcomes captured from `LocationWorker.doWork()` exits. Always
     * appended (not gated on [enabled]) so the structured-outcome path
     * is observable from tests without requiring the full test-mode
     * setup. Production lifecycle is unaffected — callers should `reset()`
     * between test cases.
     */
    val capturedOutcomes: MutableList<LocationWorker.Outcome> =
        java.util.Collections.synchronizedList(mutableListOf())

    fun reset() {
        enabled = false
        overrideLocation = null
        capturedUploads.clear()
        suppressGeofence = false
        capturedOutcomes.clear()
    }
}
