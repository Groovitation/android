package io.blaha.groovitation

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.firebase.messaging.FirebaseMessaging
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.util.applyDefaultImeWindowInsets
import io.blaha.groovitation.services.LocationTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : HotwireActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "groovitation_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch_complete"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_UPDATE_PROMPT_SHOWN_VERSION = "update_prompt_shown_version"
        private const val WELCOME_NOTIFICATION_ID = 1001
    }

    private lateinit var bottomNavigation: BottomNavigationView

    // Session-level flag to avoid re-prompting background permission if already asked this session
    private var backgroundPermissionPromptedThisSession = false
    private var fcmTokenRegistered = false
    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeWebFragment: GroovitationWebFragment? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
            fetchFcmToken()
            showWelcomeNotificationIfFirstLaunch()
        } else {
            Log.w(TAG, "Notification permission denied")
        }
        dispatchNotificationPermissionState(notificationPermissionState())
        // Chain: notification → location permission
        requestLocationPermission()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.d(TAG, "Location permission granted (fine=$fineGranted, coarse=$coarseGranted)")
            // Chain: foreground location → background location dialog
            promptBackgroundLocationPermission()
        } else {
            Log.w(TAG, "Location permission denied")
        }
        dispatchLocationPermissionState(fineGranted || coarseGranted)
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Background location permission granted")
        } else {
            Log.w(TAG, "Background location permission denied")
        }
        // Either way, try to start — tryStartBackgroundTracking checks conditions
        tryStartBackgroundTracking()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Apply window insets to the nav host
        findViewById<View>(R.id.main_nav_host).applyDefaultImeWindowInsets()

        // Setup bottom navigation
        bottomNavigation = findViewById(R.id.bottom_navigation)
        setupBottomNavigation()

        // Apply window insets to bottom navigation for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        Log.d(TAG, "MainActivity onCreate completed, navigatorConfigurations: ${navigatorConfigurations()}")
        requestNotificationPermission()
        handleIntent(intent)
    }

    private val bottomNavListener =
        NavigationBarView.OnItemSelectedListener { item ->
            val path = when (item.itemId) {
                R.id.nav_map -> "/map"
                R.id.nav_plan -> "/plan"
                R.id.nav_friends -> "/friends"
                R.id.nav_interests -> "/interests"
                R.id.nav_account -> "/users/edit"
                else -> return@OnItemSelectedListener false
            }

            val url = "${BuildConfig.BASE_URL}$path"
            Log.d(TAG, "Bottom navigation: navigating to $url")

            // Navigate in the current navigator
            delegate.currentNavigator?.route(url)
            true
        }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(bottomNavListener)
    }

    fun syncBottomNavTab(path: String) {
        val itemId = when {
            path.startsWith("/map") -> R.id.nav_map
            path.startsWith("/plan") -> R.id.nav_plan
            path.startsWith("/friends") -> R.id.nav_friends
            path.startsWith("/interests") -> R.id.nav_interests
            path.startsWith("/users/edit") -> R.id.nav_account
            else -> return
        }
        if (bottomNavigation.selectedItemId != itemId) {
            bottomNavigation.setOnItemSelectedListener(null)
            bottomNavigation.selectedItemId = itemId
            bottomNavigation.setOnItemSelectedListener(bottomNavListener)
        }
    }

    override fun onResume() {
        super.onResume()
        LocationTrackingService.refreshCookie(this)
        tryStartBackgroundTracking()
        checkForAppUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun navigatorConfigurations(): List<NavigatorConfiguration> {
        return listOf(
            NavigatorConfiguration(
                name = "main",
                startLocation = "${BuildConfig.BASE_URL}/",
                navigatorHostId = R.id.main_nav_host
            )
        )
    }

    fun setBottomNavVisible(visible: Boolean) {
        bottomNavigation.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val url = it.getStringExtra("url")
            if (!url.isNullOrEmpty()) {
                Log.d(TAG, "Deep link URL from notification: $url")
                updateBottomNavForUrl(url)
                // Navigate the WebView to this URL
                delegate.currentNavigator?.route(url)
            }

            it.data?.let { uri ->
                if (uri.scheme == "https" && uri.host == "groovitation.blaha.io") {
                    val path = uri.path ?: "/"
                    Log.d(TAG, "Deep link path: $path")
                    updateBottomNavForPath(path)
                    delegate.currentNavigator?.route(uri.toString())
                }
            }
        }
    }

    private fun updateBottomNavForUrl(url: String) {
        val path = url.removePrefix(BuildConfig.BASE_URL)
        updateBottomNavForPath(path)
    }

    private fun updateBottomNavForPath(path: String) {
        val itemId = when {
            path.startsWith("/map") -> R.id.nav_map
            path.startsWith("/plan") -> R.id.nav_plan
            path.startsWith("/friends") -> R.id.nav_friends
            path.startsWith("/interests") -> R.id.nav_interests
            path.startsWith("/users/edit") -> R.id.nav_account
            else -> null
        }
        if (itemId != null && bottomNavigation.selectedItemId != itemId) {
            bottomNavigation.selectedItemId = itemId
        }
    }

    private fun requestNotificationPermission(fromWeb: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    fetchFcmToken()
                    showWelcomeNotificationIfFirstLaunch()
                    if (fromWeb) {
                        dispatchNotificationPermissionState(notificationPermissionState())
                    }
                    // Chain: notification already granted → location permission
                    if (!fromWeb) requestLocationPermission()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    markNotificationPermissionRequested()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    markNotificationPermissionRequested()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            fetchFcmToken()
            showWelcomeNotificationIfFirstLaunch()
            if (fromWeb) {
                dispatchNotificationPermissionState(notificationPermissionState())
            }
            // Chain: pre-Tiramisu (no notification dialog) → location permission
            if (!fromWeb) requestLocationPermission()
        }
    }

    private fun showWelcomeNotificationIfFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstLaunchComplete = prefs.getBoolean(KEY_FIRST_LAUNCH, false)

        if (!firstLaunchComplete) {
            Log.d(TAG, "First launch - showing welcome notification")
            showWelcomeNotification()
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
        }
    }

    private fun showWelcomeNotification() {
        // Intent to open /map when notification is tapped
        val mapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("url", "${BuildConfig.BASE_URL}/map")
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, GroovitationApplication.CHANNEL_DEFAULT)
            .setContentTitle("Welcome to Groovitation!")
            .setContentText("We'll let you know when we see an opportunity.")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(WELCOME_NOTIFICATION_ID, notification)

        Log.d(TAG, "Welcome notification shown")
    }

    fun requestNotificationPermissionFromWeb() {
        requestNotificationPermission(fromWeb = true)
    }

    fun requestLocationPermissionFromWeb() {
        if (hasLocationPermission()) {
            dispatchLocationPermissionState(true)
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    fun registerWebFragment(fragment: GroovitationWebFragment) {
        activeWebFragment = fragment
    }

    fun unregisterWebFragment(fragment: GroovitationWebFragment) {
        if (activeWebFragment == fragment) {
            activeWebFragment = null
        }
    }

    private fun dispatchNotificationPermissionState(state: String) {
        activeWebFragment?.dispatchNotificationPermissionState(state)
    }

    private fun dispatchLocationPermissionState(granted: Boolean) {
        activeWebFragment?.dispatchLocationPermissionState(granted)
    }

    private fun notificationPermissionState(): String {
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted && enabled) return "granted"
            if (!granted && !wasNotificationPermissionRequested()) return "prompt"
            return "denied"
        }

        return if (enabled) "granted" else "denied"
    }

    private fun markNotificationPermissionRequested() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            .apply()
    }

    private fun wasNotificationPermissionRequested(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
    }

    fun requestLocationPermission() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val anyGranted = permissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (anyGranted) {
            // Already granted — chain to background and dispatch state to web
            promptBackgroundLocationPermission()
            dispatchLocationPermissionState(true)
        } else {
            locationPermissionLauncher.launch(permissions)
        }
    }

    private fun promptBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (hasBackgroundLocationPermission()) return
        if (backgroundPermissionPromptedThisSession) {
            Log.d(TAG, "Background location already prompted this session, skipping")
            return
        }

        backgroundPermissionPromptedThisSession = true
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }
    }

    /**
     * Central convergence point for background tracking.
     *
     * Called from every state change: permission grants, personId received, onResume.
     * Checks all conditions and takes the appropriate next step:
     * - If personUuid not yet known: do nothing (wait for web app to provide it)
     * - If foreground location not granted: do nothing (already prompted in onCreate)
     * - If background location not granted: prompt once per session
     * - If all conditions met: start the service
     */
    fun tryStartBackgroundTracking() {
        val personUuid = LocationTrackingService.getPersonUuid(this)
        if (personUuid == null) {
            Log.d(TAG, "tryStartBackgroundTracking: no personUuid yet")
            return
        }

        if (!hasLocationPermission()) {
            Log.d(TAG, "tryStartBackgroundTracking: no foreground location permission")
            return
        }

        if (!hasBackgroundLocationPermission()) {
            Log.d(TAG, "tryStartBackgroundTracking: no background location permission, prompting")
            promptBackgroundLocationPermission()
            return
        }

        Log.d(TAG, "tryStartBackgroundTracking: all conditions met, starting service")
        LocationTrackingService.startIfEnabled(this)
    }

    /**
     * Called by GroovitationWebFragment when the web app provides the person UUID
     * via the GroovitationNative JavaScript interface.
     */
    fun onPersonIdReceived(personId: String) {
        Log.d(TAG, "Person ID received from web: $personId")
        LocationTrackingService.saveConfig(this, personId)
        tryStartBackgroundTracking()
        registerFcmTokenWithServer()
    }

    /**
     * Called by LocationComponent when the web app provides the person UUID
     * via bridge message. Saves config and tries to start tracking.
     */
    fun enableBackgroundTracking(personUuid: String) {
        LocationTrackingService.saveConfig(this, personUuid)
        tryStartBackgroundTracking()
    }

    /**
     * Register the FCM token with the server so it can send push notifications.
     * Requires: authenticated session (cookie) and FCM token available.
     */
    private fun registerFcmTokenWithServer() {
        if (fcmTokenRegistered) return
        val token = TokenStorage.fcmToken ?: return

        val cookie = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
        if (cookie.isNullOrEmpty()) {
            Log.d(TAG, "No session cookie yet, deferring FCM token registration")
            return
        }

        fcmTokenRegistered = true
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("token", token)
                    put("platform", "android")
                }

                val request = Request.Builder()
                    .url("${BuildConfig.BASE_URL}/api/notifications/tokens")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cookie", cookie)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "FCM token registered with server")
                } else {
                    Log.w(TAG, "FCM token registration failed: ${response.code}")
                    fcmTokenRegistered = false
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error registering FCM token", e)
                fcmTokenRegistered = false
            }
        }
    }

    private fun checkForAppUpdate() {
        lifecycleScope.launch(Dispatchers.Main) {
            val updateInfo = UpdateChecker.checkForUpdate() ?: return@launch
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastShownVersion = prefs.getString(KEY_UPDATE_PROMPT_SHOWN_VERSION, null)
            if (lastShownVersion == updateInfo.latestVersionName) {
                Log.d(TAG, "Update prompt already shown for ${updateInfo.latestVersionName}")
                return@launch
            }

            if (supportFragmentManager.findFragmentByTag("update_dialog") != null) {
                Log.d(TAG, "Update dialog already visible")
                return@launch
            }

            UpdateDialogFragment.newInstance(
                currentVersion = BuildConfig.VERSION_NAME,
                latestVersion = updateInfo.latestVersionName,
                downloadUrl = updateInfo.downloadUrl
            ).show(supportFragmentManager, "update_dialog")

            prefs.edit().putString(KEY_UPDATE_PROMPT_SHOWN_VERSION, updateInfo.latestVersionName).apply()
        }
    }

    private fun fetchFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "FCM Token: $token")
                    TokenStorage.fcmToken = token
                } else {
                    Log.w(TAG, "Failed to get FCM token", task.exception)
                }
            }
        } catch (e: Exception) {
            // Firebase not configured - this is OK for testing without google-services.json
            Log.w(TAG, "Firebase not available: ${e.message}")
        }
    }
}

object TokenStorage {
    var fcmToken: String? = null
}
