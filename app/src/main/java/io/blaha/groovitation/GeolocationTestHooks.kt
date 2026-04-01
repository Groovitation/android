package io.blaha.groovitation

import android.location.Location
import android.os.SystemClock

internal object GeolocationTestHooks {

    internal enum class WebViewGeolocationDecision {
        AUTO_GRANTED,
        DENIED
    }

    internal data class TestLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val altitude: Double? = null
    ) {
        fun toAndroidLocation(provider: String = "geolocation-test-hook"): Location {
            return Location(provider).apply {
                latitude = this@TestLocation.latitude
                longitude = this@TestLocation.longitude
                accuracy = accuracyMeters
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                this@TestLocation.altitude?.let { injectedAltitude ->
                    altitude = injectedAltitude
                }
            }
        }
    }

    @Volatile
    var overrideFreshLocation: TestLocation? = null

    @Volatile
    var lastWebViewGeolocationDecision: WebViewGeolocationDecision? = null

    fun recordWebViewGeolocationDecision(decision: WebViewGeolocationDecision) {
        lastWebViewGeolocationDecision = decision
    }

    fun clearWebViewGeolocationDecision() {
        lastWebViewGeolocationDecision = null
    }

    fun reset() {
        overrideFreshLocation = null
        lastWebViewGeolocationDecision = null
    }
}
