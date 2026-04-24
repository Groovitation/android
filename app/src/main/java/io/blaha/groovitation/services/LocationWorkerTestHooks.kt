package io.blaha.groovitation.services

import android.location.Location
import org.json.JSONObject

/**
 * Test seam for [LocationWorker]. When [enabled] is true the worker uses
 * injected location data and (optionally) an overridden base URL so the
 * real HTTP POST path can be exercised against a test server — allowing
 * deterministic CI verification of the one-shot background-location path
 * without Play Services or a live backend.
 *
 * #770: the old "capture payload, skip POST" shape was removed. The worker
 * now always invokes `postLocationPayload`; tests seed [baseUrlOverride]
 * to point that at a `MockWebServer` and assert on what the server
 * received, which is the only way to prove the POST actually hit the wire.
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

    /**
     * #770: when non-null and [enabled], replaces `BuildConfig.BASE_URL` in
     * the worker's POST target so instrumented tests can redirect the real
     * HTTP path at a `MockWebServer`. Pass the raw server base without a
     * trailing slash (e.g. `http://127.0.0.1:12345`); the worker appends
     * `/people/{uuid}/location` to it.
     */
    @Volatile
    var baseUrlOverride: String? = null

    /**
     * #770: retained as a data-class declaration for backwards-compatible
     * import sites, but the worker no longer populates this list. Tests
     * assert on `MockWebServer.takeRequest()` instead — the old list-based
     * capture could not distinguish "payload built" from "request actually
     * sent", which is the exact ambiguity #770 is fixing. Safe to remove
     * entirely once the last caller migrates.
     */
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
        baseUrlOverride = null
        capturedUploads.clear()
        suppressGeofence = false
        capturedOutcomes.clear()
    }
}
