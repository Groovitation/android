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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.messaging.FirebaseMessaging
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.util.applyDefaultImeWindowInsets
import io.blaha.groovitation.services.LocationTrackingService

class MainActivity : HotwireActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "groovitation_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch_complete"
        private const val WELCOME_NOTIFICATION_ID = 1001
    }

    private lateinit var bottomNavigation: BottomNavigationView

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
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.d(TAG, "Location permission granted (fine=$fineGranted, coarse=$coarseGranted)")
            requestBackgroundLocationPermission()
        } else {
            Log.w(TAG, "Location permission denied")
        }
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Background location permission granted")
            LocationTrackingService.startIfEnabled(this)
        } else {
            Log.w(TAG, "Background location permission denied")
        }
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
        requestLocationPermission()
        handleIntent(intent)
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            val path = when (item.itemId) {
                R.id.nav_map -> "/map"
                R.id.nav_plan -> "/plan"
                R.id.nav_friends -> "/friends"
                R.id.nav_interests -> "/interests"
                R.id.nav_account -> "/users/edit"
                else -> return@setOnItemSelectedListener false
            }

            val url = "${BuildConfig.BASE_URL}$path"
            Log.d(TAG, "Bottom navigation: navigating to $url")

            // Navigate in the current navigator
            delegate.currentNavigator?.route(url)
            true
        }

        // Default to Map tab
        bottomNavigation.selectedItemId = R.id.nav_map
    }

    override fun onResume() {
        super.onResume()
        LocationTrackingService.refreshCookie(this)

        if (hasLocationPermission() && !hasBackgroundLocationPermission()) {
            // Foreground granted but background not yet — prompt for "Allow all the time"
            requestBackgroundLocationPermission()
        } else if (hasLocationPermission() && hasBackgroundLocationPermission()) {
            LocationTrackingService.startIfEnabled(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun navigatorConfigurations(): List<NavigatorConfiguration> {
        return listOf(
            NavigatorConfiguration(
                name = "main",
                startLocation = "${BuildConfig.BASE_URL}/map",
                navigatorHostId = R.id.main_nav_host
            )
        )
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val url = it.getStringExtra("url")
            if (!url.isNullOrEmpty()) {
                Log.d(TAG, "Deep link URL from notification: $url")
                updateBottomNavForUrl(url)
            }

            it.data?.let { uri ->
                if (uri.scheme == "https" && uri.host == "groovitation.blaha.io") {
                    val path = uri.path ?: "/"
                    Log.d(TAG, "Deep link path: $path")
                    updateBottomNavForPath(path)
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    fetchFcmToken()
                    showWelcomeNotificationIfFirstLaunch()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            fetchFcmToken()
            showWelcomeNotificationIfFirstLaunch()
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

    fun requestLocationPermission() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val anyGranted = permissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!anyGranted) {
            locationPermissionLauncher.launch(permissions)
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
            LocationTrackingService.startIfEnabled(this)
            return
        }
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
     * Called by LocationComponent when the web app provides the person UUID.
     * Saves config and starts the background service.
     */
    fun enableBackgroundTracking(personUuid: String) {
        LocationTrackingService.saveConfig(this, personUuid)

        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (!hasBackgroundLocationPermission()) {
            requestBackgroundLocationPermission()
            return
        }

        LocationTrackingService.startIfEnabled(this)
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
