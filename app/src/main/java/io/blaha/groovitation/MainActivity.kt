package io.blaha.groovitation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.navigation.NavigationBarView
import com.google.firebase.messaging.FirebaseMessaging
import dev.hotwire.navigation.activities.HotwireActivity
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.util.applyDefaultImeWindowInsets
import io.blaha.groovitation.services.GeofenceManager
import io.blaha.groovitation.services.LocationTrackingService
import io.blaha.groovitation.services.LocationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : HotwireActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "groovitation_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch_complete"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_UPDATE_PROMPT_SHOWN_VERSION = "update_prompt_shown_version"
        private const val KEY_LAST_SEEN_APP_VERSION_CODE = "last_seen_app_version_code"
        private const val KEY_BACKGROUND_LOCATION_SYSTEM_PROMPTED = "background_location_system_prompted"
        private const val KEY_BACKGROUND_LOCATION_DIALOG_SHOWN = "background_location_dialog_shown"
        // Set only when the user explicitly taps "I'm sure" on the rationale
        // dialog. Acts as the hard gate for never re-prompting; all other
        // states (OS-denied, dismissed, permission auto-reset) are treated as
        // re-promptable so we don't silently lock users out of background
        // tracking when they never made an explicit choice.
        private const val KEY_USER_CHOSE_FOREGROUND_ONLY = "background_location_user_chose_foreground_only"
        // True after we've shown the Samsung-specific "Never sleeping apps"
        // onboarding dialog at least once for this install. Cap the dialog
        // to one show per install so it isn't annoying — the deeper
        // recovery for users whose tracking has gone silent for days lives
        // server-side (heartbeat ticket #796).
        private const val KEY_SAMSUNG_ONBOARDING_SHOWN = "samsung_background_onboarding_shown"
        internal const val RECENT_FOREGROUND_LOCATION_MAX_AGE_MS = 120_000L
        internal const val NATIVE_LOCATION_AUTH_REFRESH_DEBOUNCE_MS = 5000L
        private const val WELCOME_NOTIFICATION_ID = 1001
        internal const val IMAGE_INTAKE_CAMERA_PREFIX = "image-intake-camera-"
        private const val IMAGE_INTAKE_CAMERA_SUFFIX = ".jpg"
        private const val IMAGE_INTAKE_STALE_MS = 24L * 60L * 60L * 1000L
        internal const val EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN =
            "io.blaha.groovitation.extra.DISABLE_STARTUP_PERMISSION_CHAIN"
        internal const val EXTRA_SKIP_LOCATION_PERMISSION_CHAIN =
            "io.blaha.groovitation.extra.SKIP_LOCATION_PERMISSION_CHAIN"

        /**
         * Decides whether `promptBackgroundLocationPermission` should proceed
         * (true) or skip (false). Pure function so the matrix is testable
         * without driving the Activity.
         *
         * The historical bug this gate replaces: the previous implementation
         * skipped on `dialogShown == true`, which conflated "we showed the
         * rationale dialog at least once" with "user explicitly chose
         * foreground-only." Users who tapped "Enable background tracking" then
         * got denied at the OS level (or whose permission auto-reset) were
         * silently locked out forever. The new gate respects only the explicit
         * `foregroundOnlyExplicitlyChosen` flag, which is set ONLY when the
         * user taps "I'm sure" on the rationale dialog.
         */
        internal fun shouldPromptForBackgroundLocation(
            sdkInt: Int,
            hasBackgroundPermission: Boolean,
            foregroundOnlyExplicitlyChosen: Boolean,
            promptedThisSession: Boolean
        ): Boolean {
            if (sdkInt < Build.VERSION_CODES.Q) return false
            if (hasBackgroundPermission) return false
            if (foregroundOnlyExplicitlyChosen) return false
            if (promptedThisSession) return false
            return true
        }

        /**
         * Decides whether to fire the OS battery-optimization-exemption
         * prompt right after we've been granted background location.
         * Pure function so the matrix is testable.
         *
         * Mechanism: WorkManager periodic work (15 min) is throttled or
         * cancelled by the OS scheduler when the app is in a restricted
         * standby bucket or otherwise battery-optimized. The exemption
         * intent flips us into the "unrestricted" bucket on stock Android
         * and is the prerequisite for Samsung's "Never sleeping apps"
         * recognition path.
         */
        internal fun shouldRequestBatteryOptimizationExemption(
            sdkInt: Int,
            isIgnoringBatteryOptimizations: Boolean,
            hasBackgroundPermission: Boolean,
            promptedThisSession: Boolean
        ): Boolean {
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is API 23+ but
            // only meaningful once we have the location permission we'd
            // be using in the background.
            if (sdkInt < Build.VERSION_CODES.M) return false
            if (!hasBackgroundPermission) return false
            if (isIgnoringBatteryOptimizations) return false
            // #787: cap to one prompt per app session — firing the modal
            // OS dialog on every onResume within a single session is
            // hostile, but a fresh app launch (common after a Samsung
            // firmware update silently re-restricted us to battery
            // optimizations) is exactly when we want to try again. A
            // session-level flag gives us "re-prompt across restarts"
            // without the "modal every resume" UX.
            if (promptedThisSession) return false
            return true
        }

        /**
         * Decides whether to show the Samsung-specific "Never sleeping apps"
         * onboarding dialog. Pure function so the matrix is testable.
         *
         * Samsung OneUI's "Put unused apps to sleep" routinely shelves apps
         * after a few days of non-use, even when battery-optimization is
         * exempt. The Settings location is unique to Samsung and worth
         * walking the user to explicitly. We cap to once per install so the
         * dialog isn't a recurring nag.
         */
        internal fun shouldShowSamsungSleepingAppsOnboarding(
            manufacturer: String?,
            hasBackgroundPermission: Boolean,
            onboardingAlreadyShown: Boolean
        ): Boolean {
            if (manufacturer?.lowercase() != "samsung") return false
            if (!hasBackgroundPermission) return false
            if (onboardingAlreadyShown) return false
            return true
        }

        internal fun reconcileNotificationPermissionStateForVersion(
            prefs: SharedPreferences,
            currentVersionCode: Int
        ): Boolean {
            val lastSeenVersionCode = prefs.getInt(KEY_LAST_SEEN_APP_VERSION_CODE, -1)
            val requestedBefore = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            val isLegacyUpgrade = lastSeenVersionCode == -1 && requestedBefore
            val isVersionUpgrade = lastSeenVersionCode != -1 && lastSeenVersionCode != currentVersionCode
            val shouldResetRequestedState = isLegacyUpgrade || isVersionUpgrade

            val editor = prefs.edit().putInt(KEY_LAST_SEEN_APP_VERSION_CODE, currentVersionCode)
            if (shouldResetRequestedState) {
                editor.putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
            }
            editor.apply()

            return shouldResetRequestedState
        }

        internal fun persistNotificationPermissionRequested(
            prefs: SharedPreferences,
            currentVersionCode: Int
        ) {
            prefs.edit()
                .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
                .putInt(KEY_LAST_SEEN_APP_VERSION_CODE, currentVersionCode)
                .apply()
        }

        internal fun shouldAutoRequestPermissions(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN, false) != true
        }

        internal fun shouldContinueLocationPermissionChain(intent: Intent?): Boolean {
            if (!BuildConfig.DEBUG) return true
            return intent?.getBooleanExtra(EXTRA_SKIP_LOCATION_PERMISSION_CHAIN, false) != true
        }

        internal fun shouldReplayNativeLocationAfterAuth(
            lastReplayElapsedMs: Long,
            nowElapsedMs: Long
        ): Boolean {
            if (lastReplayElapsedMs <= 0L) return true
            val elapsedSinceLastReplay = nowElapsedMs - lastReplayElapsedMs
            return elapsedSinceLastReplay >= NATIVE_LOCATION_AUTH_REFRESH_DEBOUNCE_MS
        }
    }

    private lateinit var bottomNavigation: BottomNavigationView

    // Session-level flag to avoid re-prompting background permission if already asked this session
    private var backgroundPermissionPromptedThisSession = false
    private var fcmTokenRegistered = false
    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeWebFragment: GroovitationWebFragment? = null
    private var pendingRouteUrl: String? = null
    private var startupUrlOverride: String? = null
    private lateinit var foregroundLocationManager: io.blaha.groovitation.services.ForegroundLocationManager
    private lateinit var modalAwareBackCallback: OnBackPressedCallback
    private var lastBottomNavPathForTest: String? = null
    private var lastNavUsedClearAll: Boolean = false
    private var lastRoutedUrlForTest: String? = null
    private var recentForegroundLocation: Location? = null
    private var recentForegroundLocationRecordedAtMs: Long = 0L
    private var lastNativeLocationAuthReplayElapsedMs: Long = 0L
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraCapture: PendingCameraCapture? = null
    private var activeImageIntakeDialog: BottomSheetDialog? = null
    private val nativeGoogleSignInCoordinator by lazy {
        NativeGoogleSignInCoordinator(
            googleIdTokenProvider = CredentialManagerGoogleIdTokenProvider(this),
            nativeGoogleAuthApi = BackendNativeGoogleAuthApi(BuildConfig.BASE_URL, httpClient),
        )
    }
    private data class PendingCameraCapture(
        val uri: Uri,
        val file: File
    )

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
        continueLocationPermissionFlow()
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
            tryStartBackgroundTracking()
            // Permission alone isn't enough on Samsung / aggressive OEMs —
            // we also need to be off the battery-optimization list and out
            // of "sleeping apps." Otherwise the WorkManager periodic
            // location worker is silently shelved.
            hardenBackgroundTrackingAgainstOemKillers()
        } else {
            Log.w(TAG, "Background location permission denied")
            showBackgroundLocationExplanation()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        finishImageChooser(extractFileChooserUris(result.resultCode, result.data))
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        finishImageChooser(uri?.let { arrayOf(it) })
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capture = pendingCameraCapture
        pendingCameraCapture = null
        if (success && capture != null) {
            finishImageChooser(arrayOf(capture.uri))
        } else {
            capture?.file?.delete()
            finishImageChooser(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startupUrlOverride = resolveStartupUrlOverride(intent)
        setContentView(R.layout.activity_main)

        // Pre-cache HTTP Basic Auth credentials so the first WebView request
        // doesn't trigger a 401 that Hotwire interprets as a page load failure.
        // Without this, cold start shows "Error loading page" because the in-memory
        // credential cache is lost when the app process is killed.
        preCacheHttpAuth()

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

        foregroundLocationManager = io.blaha.groovitation.services.ForegroundLocationManager(applicationContext)
        installModalAwareBackHandler()

        Log.d(TAG, "MainActivity onCreate completed, navigatorConfigurations: ${navigatorConfigurations()}")
        handleAppVersionState()
        if (shouldAutoRequestPermissions(intent)) {
            requestNotificationPermission()
        } else {
            Log.d(TAG, "Skipping startup permission chain for instrumentation test intent")
        }
        handleIntent(intent)
    }

    private fun installModalAwareBackHandler() {
        modalAwareBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fragment = activeWebFragment
                if (fragment == null) {
                    continueDefaultBackNavigation()
                    return
                }

                fragment.closeTopWebModalIfOpen { consumed ->
                    if (consumed) {
                        Log.d(TAG, "Back press consumed by web modal close")
                    } else {
                        continueDefaultBackNavigation()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, modalAwareBackCallback)
    }

    private fun continueDefaultBackNavigation() {
        modalAwareBackCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        modalAwareBackCallback.isEnabled = true
    }

    private fun preCacheHttpAuth() {
        val host = "groovitation.blaha.io"
        val realm = "Restricted groovitation.blaha.io"
        @Suppress("DEPRECATION")
        android.webkit.WebViewDatabase.getInstance(this)
            .setHttpAuthUsernamePassword(host, realm, "groovitation", "aldoofra")
    }

    private val bottomNavListener =
        NavigationBarView.OnItemSelectedListener { item ->
            val previousItemId = bottomNavigation.selectedItemId
            val path = bottomNavPathForItem(item.itemId) ?: return@OnItemSelectedListener false
            lastBottomNavPathForTest = path

            val url = "${BuildConfig.BASE_URL}$path"
            Log.d(TAG, "Bottom navigation: navigating to $url")

            if (previousItemId == R.id.nav_map && item.itemId != R.id.nav_map) {
                activeWebFragment?.flushMapVisibilityState()
            }

            // Events tab is the start location (/). Using route() causes a
            // Hotwire POP that shows stale WebView content from the previous
            // tab instead of performing a fresh Turbo visit. clearAll() resets
            // the back stack and forces a fresh visit to the start location.
            if (item.itemId == R.id.nav_home) {
                lastNavUsedClearAll = true
                delegate.currentNavigator?.clearAll()
            } else {
                lastNavUsedClearAll = false
                routeUrl(url)
            }
            true
        }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(bottomNavListener)
    }

    fun syncBottomNavTab(path: String) {
        val itemId = navItemIdForPath(path) ?: return
        if (bottomNavigation.selectedItemId != itemId) {
            bottomNavigation.setOnItemSelectedListener(null)
            bottomNavigation.selectedItemId = itemId
            bottomNavigation.setOnItemSelectedListener(bottomNavListener)
        }
    }

    override fun onPause() {
        super.onPause()
        // Flush cookies to disk so the session cookie survives process kill.
        // Without this, CookieManager's async background sync may not have
        // completed before Android kills the process on swipe-away.
        CookieManager.getInstance().flush()
        // Stop continuous foreground tracking when we're no longer visible to
        // hand back GPS to the OS; the background geofence chain takes over.
        foregroundLocationManager.stopContinuousTracking()
    }

    override fun onStop() {
        super.onStop()
        // Belt-and-suspenders to onPause: onStop fires in some system-
        // initiated teardown paths (Samsung Deep Sleep, aggressive OEM
        // killers) where onPause may not have already flushed, or where the
        // user took a different path to background the app. Flush again so
        // background workers reading CookieManager from disk see the
        // latest session cookie even when the WebView process gets killed
        // without a clean onPause-then-onStop sequence.
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        LocationTrackingService.refreshCookie(this)
        syncPermissionStatesToWeb()
        tryStartBackgroundTracking()
        requestNativeForegroundLocation()
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
                startLocation = startupUrlOverride ?: "${BuildConfig.BASE_URL}/",
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
                if (url == startupUrlOverride && activeWebFragment == null) {
                    Log.d(TAG, "Intent URL already selected as start location, skipping duplicate deferred route")
                } else {
                    routeUrlWhenReady(url)
                }
            }

            it.data?.let { uri ->
                if (uri.scheme == "groovitation" && uri.host == "oauth-callback") {
                    // OAuth flow completed in Custom Tab — authenticate in WebView.
                    // Backend hands off a single-use code; the live session token never
                    // traverses the deep-link URL.
                    val code = uri.getQueryParameter("code")
                    val redirect = uri.getQueryParameter("redirect") ?: "/"
                    if (code != null) {
                        Log.d(TAG, "OAuth callback received, authenticating in WebView")
                        updateBottomNavForPath(redirect)
                        val authUrl = "${BuildConfig.BASE_URL}/oauth/native-authenticate?code=$code&redirect=$redirect&platform=android"
                        routeUrlWhenReady(authUrl)
                    }
                } else if (uri.scheme == "https" && uri.host == "groovitation.blaha.io") {
                    val path = uri.path ?: "/"
                    Log.d(TAG, "Deep link path: $path")
                    val oauthRedirectPath = if (path == "/oauth/native-authenticate") {
                        uri.getQueryParameter("redirect")
                    } else {
                        null
                    }
                    updateBottomNavForPath(oauthRedirectPath ?: path)
                    routeUrlWhenReady(uri.toString())
                }
            }
        }
    }

    fun startGoogleCredentialSignIn(
        serverClientId: String,
        returnUrl: String,
        fallbackUrl: String,
    ) {
        val cookieHeader = CookieManager.getInstance().getCookie(BuildConfig.BASE_URL)
        lifecycleScope.launch(Dispatchers.Main) {
            when (
                val action = nativeGoogleSignInCoordinator.signIn(
                    NativeGoogleSignInRequest(
                        serverClientId = serverClientId,
                        returnUrl = returnUrl,
                        fallbackUrl = fallbackUrl,
                    ),
                    cookieHeader = cookieHeader,
                )
            ) {
                is NativeGoogleSignInAction.Navigate -> routeUrlWhenReady(action.url)
                is NativeGoogleSignInAction.OpenBrowser ->
                    ExternalBrowserIntentFactory.launch(this@MainActivity, action.url)
            }
        }
    }

    private fun updateBottomNavForUrl(url: String) {
        val path = url.removePrefix(BuildConfig.BASE_URL)
        updateBottomNavForPath(path)
    }

    private fun updateBottomNavForPath(path: String) {
        val itemId = navItemIdForPath(path)
        if (itemId != null && bottomNavigation.selectedItemId != itemId) {
            bottomNavigation.selectedItemId = itemId
        }
    }

    private fun navItemIdForPath(path: String): Int? {
        return when {
            path.startsWith("/plan") -> R.id.nav_home
            path.startsWith("/map") -> R.id.nav_map
            path.startsWith("/interests") -> R.id.nav_interests
            path.startsWith("/friends") -> R.id.nav_friends
            path.startsWith("/users/edit") -> R.id.nav_account
            path == "/" || path.isBlank() -> R.id.nav_home
            else -> null
        }
    }

    internal fun latestBottomNavPathForTest(): String? = lastBottomNavPathForTest

    internal fun lastNavUsedClearAllForTest(): Boolean = lastNavUsedClearAll

    internal fun lastRoutedUrlForTest(): String? = lastRoutedUrlForTest

    internal fun pendingRouteUrlForTest(): String? = pendingRouteUrl

    internal fun bottomNavPathForItemForTest(itemId: Int): String? = bottomNavPathForItem(itemId)

    internal fun hasPendingFileChooserCallbackForTest(): Boolean = pendingFileChooserCallback != null

    internal fun activeImageIntakeDialogForTest(): BottomSheetDialog? = activeImageIntakeDialog

    internal fun handleIntentForTest(intent: Intent) {
        handleIntent(intent)
    }

    private fun bottomNavPathForItem(itemId: Int): String? = when (itemId) {
        R.id.nav_home -> "/"
        R.id.nav_map -> "/map"
        R.id.nav_interests -> "/interests"
        R.id.nav_friends -> "/friends"
        R.id.nav_account -> "/users/edit"
        else -> null
    }

    private fun routeUrl(url: String) {
        lastRoutedUrlForTest = url
        delegate.currentNavigator?.route(url)
    }

    private fun routeUrlWhenReady(url: String) {
        lastRoutedUrlForTest = url
        if (activeWebFragment == null) {
            Log.d(TAG, "Web fragment not ready yet, deferring route to $url")
            pendingRouteUrl = url
            return
        }

        val navigator = delegate.currentNavigator
        if (navigator == null) {
            Log.d(TAG, "Navigator not ready yet, deferring route to $url")
            pendingRouteUrl = url
            return
        }

        pendingRouteUrl = null
        navigator.route(url)
    }

    private fun resolveStartupUrlOverride(intent: Intent?): String? {
        if (!BuildConfig.DEBUG) return null
        if (intent?.getBooleanExtra(EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN, false) != true) return null
        return intent.getStringExtra("url")?.takeIf { it.isNotBlank() }
    }

    private fun flushPendingRouteUrl() {
        val url = pendingRouteUrl ?: return
        val navigator = delegate.currentNavigator ?: return
        Log.d(TAG, "Flushing deferred route to $url")
        pendingRouteUrl = null
        navigator.route(url)
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
                    if (!fromWeb) continueLocationPermissionFlow()
                }
                !fromWeb && wasNotificationPermissionRequested() -> {
                    dispatchNotificationPermissionState(notificationPermissionState())
                    continueLocationPermissionFlow()
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
            if (!fromWeb) continueLocationPermissionFlow()
        }
    }

    private fun continueLocationPermissionFlow() {
        if (!shouldContinueLocationPermissionChain(intent)) {
            Log.d(TAG, "Skipping location permission chain for instrumentation test intent")
            return
        }
        requestLocationPermission()
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            NotificationTapActivityStart.creatorOptions()
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

    fun launchImageChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        cancelPendingImageChooser()
        pendingFileChooserCallback = filePathCallback
        cleanupStaleImageIntakeFiles()

        return runCatching {
            showImageIntakeSheet()
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to launch image chooser", error)
            finishImageChooser(null)
            false
        }
    }

    private fun showImageIntakeSheet() {
        activeImageIntakeDialog?.dismiss()

        val dialog = BottomSheetDialog(this)
        val contentView = layoutInflater.inflate(R.layout.dialog_image_intake_options, null)
        dialog.setContentView(contentView)
        var actionSelected = false

        contentView.findViewById<View>(R.id.image_intake_choose_photos).setOnClickListener {
            actionSelected = true
            dialog.dismiss()
            launchPhotoPicker()
        }
        contentView.findViewById<View>(R.id.image_intake_take_photo).setOnClickListener {
            actionSelected = true
            dialog.dismiss()
            launchCameraCapture()
        }
        contentView.findViewById<View>(R.id.image_intake_browse_files).setOnClickListener {
            actionSelected = true
            dialog.dismiss()
            launchDocumentPicker()
        }

        dialog.setOnDismissListener {
            if (activeImageIntakeDialog === dialog) {
                activeImageIntakeDialog = null
            }
        }
        dialog.setOnCancelListener {
            if (!actionSelected && pendingFileChooserCallback != null) {
                finishImageChooser(null)
            }
        }

        activeImageIntakeDialog = dialog
        dialog.show()
    }

    private fun launchPhotoPicker() {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun launchDocumentPicker() {
        val chooserIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        fileChooserLauncher.launch(chooserIntent)
    }

    private fun launchCameraCapture() {
        val captureFile = File.createTempFile(
            IMAGE_INTAKE_CAMERA_PREFIX,
            IMAGE_INTAKE_CAMERA_SUFFIX,
            cacheDir
        )
        val captureUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            captureFile
        )
        pendingCameraCapture = PendingCameraCapture(captureUri, captureFile)
        takePictureLauncher.launch(captureUri)
    }

    private fun extractFileChooserUris(
        resultCode: Int,
        data: Intent?
    ): Array<Uri>? {
        val parsed = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        if (!parsed.isNullOrEmpty()) return parsed
        if (resultCode != Activity.RESULT_OK) return null

        val uris = linkedSetOf<Uri>()
        data?.data?.let(uris::add)
        data?.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun finishImageChooser(uris: Array<Uri>?) {
        activeImageIntakeDialog?.dismiss()
        activeImageIntakeDialog = null

        val callback = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null

        uris?.forEach(::persistReadPermissionIfPossible)
        callback.onReceiveValue(uris)
    }

    private fun cancelPendingImageChooser() {
        activeImageIntakeDialog?.dismiss()
        activeImageIntakeDialog = null
        pendingCameraCapture?.file?.delete()
        pendingCameraCapture = null
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
    }

    private fun persistReadPermissionIfPossible(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun cleanupStaleImageIntakeFiles() {
        val cutoff = System.currentTimeMillis() - IMAGE_INTAKE_STALE_MS
        cacheDir.listFiles()?.forEach { file ->
            val isImageIntakeFile = file.name.startsWith(IMAGE_INTAKE_CAMERA_PREFIX) ||
                file.name.startsWith(GroovitationWebView.AVATAR_CONVERSION_PREFIX)
            if (isImageIntakeFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    fun registerWebFragment(fragment: GroovitationWebFragment) {
        activeWebFragment = fragment
        flushPendingRouteUrl()
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

    fun syncPermissionStatesToWeb() {
        dispatchNotificationPermissionState(notificationPermissionState())
        dispatchLocationPermissionState(hasLocationPermission())
    }

    fun recordForegroundLocation(location: Location) {
        recentForegroundLocation = Location(location)
        recentForegroundLocationRecordedAtMs = System.currentTimeMillis()
    }

    fun recentForegroundLocation(maxAgeMs: Long = RECENT_FOREGROUND_LOCATION_MAX_AGE_MS): Location? {
        val snapshot = recentForegroundLocation ?: return null
        val ageMs = System.currentTimeMillis() - recentForegroundLocationRecordedAtMs
        if (ageMs < 0L || ageMs > maxAgeMs) {
            return null
        }
        return Location(snapshot)
    }

    fun onNativeLocationAuthReadyFromWeb() {
        if (LocationTrackingService.getPersonUuid(this) == null) {
            Log.d(TAG, "Ignoring native auth replay without personUuid")
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (!shouldReplayNativeLocationAfterAuth(lastNativeLocationAuthReplayElapsedMs, nowElapsedMs)) {
            Log.d(TAG, "Skipping duplicate native auth replay trigger")
            return
        }
        lastNativeLocationAuthReplayElapsedMs = nowElapsedMs

        Log.d(TAG, "Native location auth synced from web, replaying native location work")
        tryStartBackgroundTracking()

        if (hasLocationPermission()) {
            val replayedRecentFix =
                foregroundLocationManager.postRecentForegroundLocationIfFresh(recentForegroundLocation())
            if (!replayedRecentFix) {
                requestNativeForegroundLocation()
            }
        }
    }

    fun currentNotificationPermissionState(): String = notificationPermissionState()

    private fun notificationPermissionState(): String {
        if (BuildConfig.DEBUG) {
            PermissionBridgeTestHooks.overrideNotificationPermissionState?.let { return it }
        }

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
        // Persist last_seen_app_version_code alongside the requested flag so the two
        // keys can never diverge on disk. Without this, the separate apply() writes
        // from handleAppVersionState() and here race through SharedPreferences' async
        // disk writer; a same-version relaunch could observe requested=true with
        // last_seen=-1, misclassify it as a legacy upgrade, reset the flag, and
        // re-prompt the user. See #749.
        persistNotificationPermissionRequested(
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            currentVersionCode = BuildConfig.VERSION_CODE
        )
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
        val shouldPrompt = shouldPromptForBackgroundLocation(
            sdkInt = Build.VERSION.SDK_INT,
            hasBackgroundPermission = hasBackgroundLocationPermission(),
            foregroundOnlyExplicitlyChosen = wasForegroundOnlyExplicitlyChosen(),
            promptedThisSession = backgroundPermissionPromptedThisSession
        )
        if (!shouldPrompt) {
            if (wasForegroundOnlyExplicitlyChosen()) {
                Log.d(TAG, "User explicitly chose foreground-only location, respecting that")
            } else if (backgroundPermissionPromptedThisSession) {
                Log.d(TAG, "Background location already prompted this session, skipping")
            }
            return
        }

        backgroundPermissionPromptedThisSession = true

        // If the system prompt was already shown once (previous launch), show our
        // explanation dialog instead — the ActivityResult callback doesn't always
        // fire when the user presses back from the system location settings screen.
        if (wasBackgroundLocationSystemPrompted()) {
            showBackgroundLocationExplanation()
            return
        }

        markBackgroundLocationSystemPrompted()
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun showBackgroundLocationExplanation() {
        if (wasForegroundOnlyExplicitlyChosen()) return
        markBackgroundLocationDialogShown()

        AlertDialog.Builder(this)
            .setTitle("Background Location")
            .setMessage(
                "Background location lets Groovitation find hangout spots near you and " +
                "your friends — even when the app isn't open.\n\n" +
                "Without it, we can only look while you're using the app."
            )
            .setPositiveButton("Enable background tracking") { dialog, _ ->
                dialog.dismiss()
                requestBackgroundLocationOrOpenSettings()
            }
            .setNegativeButton("I'm sure") { dialog, _ ->
                // This is the single place where we record "user explicitly
                // chose foreground-only." Any other exit (dialog dismissal,
                // OS denial, process death) leaves the flag unset so we can
                // try again later.
                markForegroundOnlyExplicitlyChosen()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Launch the OS background-location prompt, OR deep-link to app Settings if
     * Android has moved the permission into its "don't ask again" state
     * (two denials without rationale). In that state calling `launch()` silently
     * no-ops and the ActivityResult callback fires with `isGranted=false` in
     * the next event loop tick — which would look identical to a fresh denial
     * and trap the user. Detecting the condition up front lets us route them
     * to Settings where they can actually change it.
     */
    private fun requestBackgroundLocationOrOpenSettings() {
        if (isBackgroundLocationPermanentlyDenied()) {
            Log.d(TAG, "Background location permission is permanently denied; opening app settings")
            openAppDetailsSettings()
        } else {
            backgroundLocationPermissionLauncher.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        }
    }

    private fun isBackgroundLocationPermanentlyDenied(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!wasBackgroundLocationSystemPrompted()) return false
        // `shouldShowRequestPermissionRationale` returns true immediately after
        // the first denial and false once Android has decided further prompts
        // would be noise. Combined with "we've prompted at least once," false
        // here means we should route to Settings rather than re-launching the
        // permission dialog.
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings", e)
        }
    }

    private fun wasBackgroundLocationSystemPrompted(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_LOCATION_SYSTEM_PROMPTED, false)
    }

    private fun markBackgroundLocationSystemPrompted() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_LOCATION_SYSTEM_PROMPTED, true)
            .apply()
    }

    private fun wasBackgroundLocationDialogShown(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_LOCATION_DIALOG_SHOWN, false)
    }

    private fun markBackgroundLocationDialogShown() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_LOCATION_DIALOG_SHOWN, true)
            .apply()
    }

    private fun wasForegroundOnlyExplicitlyChosen(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_CHOSE_FOREGROUND_ONLY, false)
    }

    private fun markForegroundOnlyExplicitlyChosen() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_CHOSE_FOREGROUND_ONLY, true)
            .apply()
    }

    private fun wasSamsungOnboardingShown(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAMSUNG_ONBOARDING_SHOWN, false)
    }

    private fun markSamsungOnboardingShown() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SAMSUNG_ONBOARDING_SHOWN, true)
            .apply()
    }

    // #787: session-level cap on the battery-exemption OS dialog. Once per
    // app process lifetime the user sees it; a subsequent MainActivity.onResume
    // is a cheap no-op. A fresh app launch (after kill / firmware update /
    // Samsung re-restricting us) clears this flag and re-offers the dialog —
    // which is exactly what the ticket asks for.
    @Volatile
    private var batteryExemptionPromptedThisSession: Boolean = false

    /**
     * Two-step OEM-killer defense, fired right after background location is
     * granted by the user. Both steps are no-ops if their precondition is
     * already satisfied:
     *
     * 1. Battery-optimization exemption (API 23+, all OEMs). Without this,
     *    `WorkManager` periodic work is throttled or cancelled in standby.
     *    Sensitive permission — declared in manifest with Play Console
     *    justification.
     * 2. Samsung "Never sleeping apps" onboarding (Samsung-only). The OS
     *    permission system reports background location as granted, but
     *    OneUI's separate "sleeping apps" / "deep sleep" mechanism can still
     *    silently shelve us. Walk the user to the right Settings screen
     *    once per install. The recurring re-prompt for users whose
     *    tracking has gone silent for days lives server-side (#796).
     */
    private fun hardenBackgroundTrackingAgainstOemKillers() {
        if (shouldRequestBatteryOptimizationExemption(
                sdkInt = Build.VERSION.SDK_INT,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
                hasBackgroundPermission = hasBackgroundLocationPermission(),
                promptedThisSession = batteryExemptionPromptedThisSession
            )
        ) {
            batteryExemptionPromptedThisSession = true
            requestIgnoreBatteryOptimizations()
        }

        if (shouldShowSamsungSleepingAppsOnboarding(
                manufacturer = Build.MANUFACTURER,
                hasBackgroundPermission = hasBackgroundLocationPermission(),
                onboardingAlreadyShown = wasSamsungOnboardingShown()
            )
        ) {
            showSamsungSleepingAppsOnboarding()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
        }
    }

    private fun showSamsungSleepingAppsOnboarding() {
        markSamsungOnboardingShown()

        AlertDialog.Builder(this)
            .setTitle("One more step on Samsung")
            .setMessage(
                "Samsung phones have a separate 'sleeping apps' setting that " +
                "can stop Groovitation from finding hangout spots in the " +
                "background — even with location permission granted.\n\n" +
                "Tap 'Open Battery settings' and add Groovitation to " +
                "'Never sleeping apps' to keep proximity working when the " +
                "app is closed."
            )
            .setPositiveButton("Open Battery settings") { dialog, _ ->
                dialog.dismiss()
                openBatteryUsageSettings()
            }
            .setNegativeButton("Not now") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun openBatteryUsageSettings() {
        // Try the Samsung-specific deep link first, fall back to the
        // generic per-app battery settings if it's not handled.
        val samsungIntent = Intent().apply {
            setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(samsungIntent)
            return
        } catch (e: Exception) {
            Log.d(TAG, "Samsung battery activity not available, falling back to generic settings")
        }

        val genericIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(genericIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery settings", e)
        }
    }

    fun hasLocationPermission(): Boolean {
        if (BuildConfig.DEBUG) {
            PermissionBridgeTestHooks.overrideLocationPermissionGranted?.let { return it }
        }

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

        Log.d(TAG, "tryStartBackgroundTracking: all conditions met, starting geofence-based tracking")

        // Legacy note: this block used to send LocationTrackingService.ACTION_STOP
        // to shut down the old pre-shim foreground service on upgrade. That
        // intent is asynchronous and its handler calls LocationWorker.cancel()
        // — which would fire *after* the enqueue calls below, canceling the
        // periodic + one-shot we just queued. Root cause of the 2026-04-24
        // Samsung S24+ / S9 tablet background-silence: heartbeat NULL,
        // WorkManager's "active-top 7x canceled" trail, and logcat showing
        // "Periodic work enqueued" immediately followed 1ms later by
        // "All location work cancelled" on every onResume. The service is
        // now a transition shim with no FGS to stop — the intent is
        // unneeded and actively harmful, so the block is removed.

        // Start geofence-based tracking via WorkManager
        LocationWorker.enqueuePeriodicWork(this)
        LocationWorker.enqueueOneShot(this)

        // Existing-install path: the hardenBackgroundTrackingAgainstOemKillers
        // call in backgroundLocationPermissionLauncher only fires on first
        // grant. For users who already had the permission before this APK
        // shipped, we call it here so the OEM-killer defense
        // (battery-optimization exemption + Samsung onboarding) auto-applies
        // on the next app resume — without requiring a permission re-grant.
        // Both inner gates are once-per-install pref-backed, so calling on
        // every resume is a cheap no-op once satisfied.
        hardenBackgroundTrackingAgainstOemKillers()
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

    fun onSignedInStateFromWeb(signedIn: Boolean) {
        // #790: the web layer has just landed a sign-in Set-Cookie (or a
        // sign-out expiry header) in the WebView's in-memory cookie store.
        // Background workers read CookieManager from disk, so an unflushed
        // in-memory cookie is effectively invisible to them if the process
        // gets killed (Samsung kills app processes aggressively). Flushing
        // here on both the in and out path keeps the on-disk cookie store
        // coherent with whatever the web layer just wrote — complements
        // the onPause/onStop flushes for the case where the user navigates
        // *within* the app after sign-in/out without going through a
        // pause/stop cycle first.
        CookieManager.getInstance().flush()

        if (signedIn) return

        Log.d(TAG, "Signed-out state received from web, stopping background tracking")
        LocationWorker.cancel(this)
        GeofenceManager(this).removeAllGeofences()
        LocationTrackingService.clearConfig(this)
    }

    private fun requestNativeForegroundLocation() {
        if (hasLocationPermission()) {
            val dispatcher: (Location) -> Unit = { location ->
                recordForegroundLocation(location)
                activeWebFragment?.dispatchNativeLocationToWeb(location)
            }
            // 30-second aggressive one-shot to snap the stored position now.
            foregroundLocationManager.requestForegroundFix(webDispatcher = dispatcher)
            // Sustained tracking while the app is in the foreground on any
            // tab (#706). Without this the landing-page distance went stale
            // once the one-shot timed out (observed after a 15-minute drive).
            // Stopped in onPause to hand GPS back to the OS; the background
            // geofence chain takes over from there.
            foregroundLocationManager.startContinuousTracking(webDispatcher = dispatcher)
            // Ensure map scripts that listen for fresh native location events
            // receive an immediate resume-time update request as well.
            activeWebFragment?.requestFreshLocationOnResume()
        }
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
        val registrationUrl = "${BuildConfig.BASE_URL}/api/notifications/tokens"
        scope.launch {
            val success = NotificationTokenRegistrar.register(
                context = applicationContext,
                httpClient = httpClient,
                url = registrationUrl,
                token = token,
                cookie = cookie
            )
            if (success) {
                Log.d(TAG, "FCM token registered with server")
            } else {
                Log.w(TAG, "FCM token registration failed")
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
        NotificationTestHooks.fakeFcmToken(this)?.let { token ->
            Log.d(TAG, "Using debug test FCM token override")
            TokenStorage.fcmToken = token
            registerFcmTokenWithServer()
            return
        }
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.d(TAG, "FCM Token: $token")
                    TokenStorage.fcmToken = token
                    // Token fetch can resolve after person/cookie initialization.
                    // Attempt registration now to avoid missing this startup window.
                    registerFcmTokenWithServer()
                } else {
                    Log.w(TAG, "Failed to get FCM token", task.exception)
                }
            }
        } catch (e: Exception) {
            // Firebase not configured - this is OK for testing without google-services.json
            Log.w(TAG, "Firebase not available: ${e.message}")
        }
    }

    private fun handleAppVersionState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val resetApplied = reconcileNotificationPermissionStateForVersion(
            prefs = prefs,
            currentVersionCode = BuildConfig.VERSION_CODE
        )
        if (resetApplied) {
            Log.i(
                TAG,
                "App version changed; reset notification-permission-requested state " +
                    "so this release can prompt again"
            )
        }
    }
}

object TokenStorage {
    var fcmToken: String? = null
}
