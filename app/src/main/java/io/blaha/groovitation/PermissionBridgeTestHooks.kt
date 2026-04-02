package io.blaha.groovitation

internal object PermissionBridgeTestHooks {

    @Volatile
    var overrideNotificationPermissionState: String? = null

    @Volatile
    var overrideLocationPermissionGranted: Boolean? = null

    fun reset() {
        overrideNotificationPermissionState = null
        overrideLocationPermissionGranted = null
    }
}
