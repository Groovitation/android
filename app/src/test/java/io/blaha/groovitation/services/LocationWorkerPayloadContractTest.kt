package io.blaha.groovitation.services

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cross-repo contract test for the `/people/{uuid}/location` JSON payload
 * (#773). Locks down the field names, the `source` string, the `deviceType`
 * string, and the exhaustive set of keys so that a refactor on either side
 * can't silently drift the wire format.
 *
 * Source of truth: `app/src/test/resources/contracts/android-location-payload-v1.json`,
 * mirrored byte-identical to
 * `pekko-backend/src/test/resources/contracts/android-location-payload-v1.json`
 * in core. The matching server-side spec is `LocationPayloadContractSpec`.
 *
 * If you intentionally change the payload shape (new field, new source value,
 * renamed key), update BOTH fixture copies and BOTH contract tests in the
 * same coordinated PR pair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocationWorkerPayloadContractTest {

    @Test
    fun `buildLocationPayloadJson matches the v1 contract fixture exactly`() {
        val fixtureText = javaClass.getResourceAsStream("/contracts/android-location-payload-v1.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing /contracts/android-location-payload-v1.json on test classpath")
        val fixture = JSONObject(fixtureText)

        val produced = LocationWorker.buildLocationPayloadJson(
            latitude = fixture.getDouble("latitude"),
            longitude = fixture.getDouble("longitude"),
            accuracy = fixture.getDouble("accuracy"),
            altitude = fixture.getDouble("altitude"),
            deviceId = fixture.getString("deviceId"),
            timestamp = fixture.getLong("timestamp")
        )

        // Exhaustive key set. New keys on either side must move the fixture
        // forward in lockstep — silent additions are exactly the drift this
        // contract is designed to catch.
        val expectedKeys = fixture.keys().asSequence().toSet()
        val producedKeys = produced.keys().asSequence().toSet()
        assertEquals(
            "payload keys diverged from fixture",
            expectedKeys,
            producedKeys
        )

        // Field-by-field value check. Doubles compared with delta to avoid
        // 31.7619 vs 31.76190000000001 reformatting noise.
        assertEquals(fixture.getDouble("latitude"), produced.getDouble("latitude"), 1e-9)
        assertEquals(fixture.getDouble("longitude"), produced.getDouble("longitude"), 1e-9)
        assertEquals(fixture.getDouble("accuracy"), produced.getDouble("accuracy"), 1e-9)
        assertEquals(fixture.getDouble("altitude"), produced.getDouble("altitude"), 1e-9)
        assertEquals(fixture.getString("deviceType"), produced.getString("deviceType"))
        assertEquals(fixture.getString("source"), produced.getString("source"))
        assertEquals(fixture.getString("deviceId"), produced.getString("deviceId"))
        assertEquals(fixture.getLong("timestamp"), produced.getLong("timestamp"))

        // The two strings the server pipeline filters on. Spelling these out
        // explicitly so a typo regression in the fixture itself is caught
        // here too — without this the test would pass for a mutually-bad
        // fixture-and-code pair.
        assertEquals("background-gps", produced.getString("source"))
        assertEquals("android", produced.getString("deviceType"))
    }

    @Test
    fun `altitude omitted from payload when null (still satisfies contract on present fields)`() {
        val produced = LocationWorker.buildLocationPayloadJson(
            latitude = 31.7619,
            longitude = -106.485,
            accuracy = 12.0,
            altitude = null,
            deviceId = "abc123def456ghi789",
            timestamp = 1735689600000L
        )

        assertFalse("altitude must be omitted, not null-ed", produced.has("altitude"))
        assertTrue("latitude must be present", produced.has("latitude"))
        assertTrue("longitude must be present", produced.has("longitude"))
        assertTrue("accuracy must be present", produced.has("accuracy"))
        assertTrue("deviceType must be present", produced.has("deviceType"))
        assertTrue("source must be present", produced.has("source"))
        assertTrue("deviceId must be present", produced.has("deviceId"))
        assertTrue("timestamp must be present", produced.has("timestamp"))
    }
}
