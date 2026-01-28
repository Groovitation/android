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
import com.google.firebase.messaging.FirebaseMessaging
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.util.applyDefaultImeWindowInsets

class MainActivity : HotwireActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "groovitation_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch_complete"
        private const val WELCOME_NOTIFICATION_ID = 1001
    }

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
        } else {
            Log.w(TAG, "Location permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_nav_host).applyDefaultImeWindowInsets()
        Log.d(TAG, "MainActivity onCreate completed, navigatorConfigurations: ${navigatorConfigurations()}")
        requestNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun navigatorConfigurations(): List<NavigatorConfiguration> {
        return listOf(
            NavigatorConfiguration(
                name = "main",
                startLocation = BuildConfig.BASE_URL,
                navigatorHostId = R.id.main_nav_host
            )
        )
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val url = it.getStringExtra("url")
            if (!url.isNullOrEmpty()) {
                Log.d(TAG, "Deep link URL from notification: $url")
            }

            it.data?.let { uri ->
                if (uri.scheme == "https" && uri.host == "groovitation.blaha.io") {
                    val path = uri.path ?: "/"
                    Log.d(TAG, "Deep link path: $path")
                }
            }
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
