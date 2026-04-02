package io.blaha.groovitation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityPermissionBridgeInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val probeUrl: String = "${BuildConfig.BASE_URL}/test/permission-bridge"

    @After
    fun tearDown() {
        PermissionBridgeTestHooks.reset()
        setNotificationPermissionRequested(false)
    }

    @Test
    fun notificationPermissionBridgeSyncsIntoWebViewAcrossChangeAndRelaunch() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

        PermissionBridgeTestHooks.overrideNotificationPermissionState = "denied"

        launchProbePage().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            val initialProbe = waitForProbe(
                webView = webView,
                timeoutMs = 60_000
            ) { probe ->
                probe.ready &&
                    probe.path == "/test/permission-bridge" &&
                    probe.notificationState == "denied"
            }
            assertEquals("denied", initialProbe.notificationState)

            val afterGrant = changePermissionsAndWaitForProbe(
                scenario = scenario,
                webView = webView,
                mutatePermissions = {
                    PermissionBridgeTestHooks.overrideNotificationPermissionState = "granted"
                },
                predicate = { before, probe ->
                    probe.notificationState == "granted" &&
                        probe.notificationEventCount > before.notificationEventCount
                }
            )
            assertEquals("granted", afterGrant.notificationState)
        }

        launchProbePage().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            val relaunchedGranted = waitForProbe(
                webView = webView,
                timeoutMs = 60_000
            ) { probe ->
                probe.ready &&
                    probe.path == "/test/permission-bridge" &&
                    probe.notificationState == "granted"
            }
            assertEquals("granted", relaunchedGranted.notificationState)

            val afterRevoke = changePermissionsAndWaitForProbe(
                scenario = scenario,
                webView = webView,
                mutatePermissions = {
                    PermissionBridgeTestHooks.overrideNotificationPermissionState = "denied"
                },
                predicate = { before, probe ->
                    probe.notificationState == "denied" &&
                        probe.notificationEventCount > before.notificationEventCount
                }
            )
            assertEquals("denied", afterRevoke.notificationState)
        }

        launchProbePage().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            val relaunchedDenied = waitForProbe(
                webView = webView,
                timeoutMs = 60_000
            ) { probe ->
                probe.ready &&
                    probe.path == "/test/permission-bridge" &&
                    probe.notificationState == "denied"
            }
            assertEquals("denied", relaunchedDenied.notificationState)
        }
    }

    @Test
    fun locationPermissionBridgeSyncsIntoWebViewAcrossChangeAndRelaunch() {
        PermissionBridgeTestHooks.overrideLocationPermissionGranted = false

        launchProbePage().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            val initialProbe = waitForProbe(
                webView = webView,
                timeoutMs = 60_000
            ) { probe ->
                probe.ready &&
                    probe.path == "/test/permission-bridge" &&
                    probe.locationState == "denied"
            }
            assertEquals("denied", initialProbe.locationState)

            val afterGrant = changePermissionsAndWaitForProbe(
                scenario = scenario,
                webView = webView,
                mutatePermissions = {
                    PermissionBridgeTestHooks.overrideLocationPermissionGranted = true
                },
                predicate = { before, probe ->
                    probe.locationState == "granted" &&
                        probe.locationEventCount > before.locationEventCount
                }
            )
            assertEquals("granted", afterGrant.locationState)
        }

        launchProbePage().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            val relaunchedGranted = waitForProbe(
                webView = webView,
                timeoutMs = 60_000
            ) { probe ->
                probe.ready &&
                    probe.path == "/test/permission-bridge" &&
                    probe.locationState == "granted"
            }
            assertEquals("granted", relaunchedGranted.locationState)

            val afterRevoke = changePermissionsAndWaitForProbe(
                scenario = scenario,
                webView = webView,
                mutatePermissions = {
                    PermissionBridgeTestHooks.overrideLocationPermissionGranted = false
                },
                predicate = { before, probe ->
                    probe.locationState == "denied" &&
                        probe.locationEventCount > before.locationEventCount
                }
            )
            assertEquals("denied", afterRevoke.locationState)
        }

        launchProbePage().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            val relaunchedDenied = waitForProbe(
                webView = webView,
                timeoutMs = 60_000
            ) { probe ->
                probe.ready &&
                    probe.path == "/test/permission-bridge" &&
                    probe.locationState == "denied"
            }
            assertEquals("denied", relaunchedDenied.locationState)
        }
    }

    private fun launchProbePage(): ActivityScenario<MainActivity> {
        val intent = Intent(targetContext, MainActivity::class.java).apply {
            putExtra("url", probeUrl)
            putExtra(MainActivity.EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityScenario.launch(intent)
    }

    private fun changePermissionsAndWaitForProbe(
        scenario: ActivityScenario<MainActivity>,
        webView: WebView,
        mutatePermissions: (ProbeSnapshot) -> Unit,
        predicate: (ProbeSnapshot, ProbeSnapshot) -> Boolean
    ): ProbeSnapshot {
        val before = evaluateProbe(webView)
        mutatePermissions(before)
        scenario.onActivity { activity ->
            activity.syncPermissionStatesToWeb()
        }
        instrumentation.waitForIdleSync()
        return waitForProbe(
            webView = webView,
            timeoutMs = 20_000
        ) { probe ->
            predicate(before, probe)
        }
    }

    private fun waitForWebView(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long
    ): WebView {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var found: WebView? = null
            scenario.onActivity { activity ->
                found = findWebView(activity.window.decorView.rootView)
            }
            if (found != null) return found!!
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for WebView")
    }

    private fun waitForProbe(
        webView: WebView,
        timeoutMs: Long,
        predicate: (ProbeSnapshot) -> Boolean
    ): ProbeSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastProbe = evaluateProbe(webView)
        while (System.currentTimeMillis() < deadline) {
            val probe = evaluateProbe(webView)
            lastProbe = probe
            if (predicate(probe)) return probe
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for permission bridge probe. Last probe=$lastProbe")
    }

    private fun evaluateProbe(webView: WebView): ProbeSnapshot {
        val script = """
            (function() {
              var probe = window.__permissionBridgeProbe || {};
              return JSON.stringify({
                ready: !!probe.ready,
                notificationState: probe.notificationState || 'missing',
                notificationEventCount: Number(probe.notificationEventCount || 0),
                locationState: probe.locationState || 'missing',
                locationEventCount: Number(probe.locationEventCount || 0),
                nativeReadCount: Number(probe.nativeReadCount || 0),
                lastSource: probe.lastSource || '',
                path: location.pathname
              });
            })();
        """.trimIndent()

        val latch = CountDownLatch(1)
        var rawResult: String? = null
        webView.post {
            webView.evaluateJavascript(script) { value ->
                rawResult = value
                latch.countDown()
            }
        }

        assertTrue("Timed out evaluating permission probe", latch.await(10, TimeUnit.SECONDS))
        val decoded = decodeJsString(rawResult)
        val payload = JSONObject(decoded)
        return ProbeSnapshot(
            ready = payload.optBoolean("ready", false),
            notificationState = payload.optString("notificationState", "missing"),
            notificationEventCount = payload.optInt("notificationEventCount", 0),
            locationState = payload.optString("locationState", "missing"),
            locationEventCount = payload.optInt("locationEventCount", 0),
            nativeReadCount = payload.optInt("nativeReadCount", 0),
            lastSource = payload.optString("lastSource", ""),
            path = payload.optString("path", "")
        )
    }

    private fun decodeJsString(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "null") {
            throw AssertionError("Permission bridge probe returned no JSON: raw=$raw")
        }
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            JSONObject("""{"v":$value}""").getString("v")
        } else {
            value
        }
    }

    private fun findWebView(view: android.view.View?): WebView? {
        when (view) {
            null -> return null
            is WebView -> return view
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) {
                    val found = findWebView(view.getChildAt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun setNotificationPermissionRequested(requested: Boolean) {
        targetContext.getSharedPreferences("groovitation_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("notification_permission_requested", requested)
            .apply()
    }

    data class ProbeSnapshot(
        val ready: Boolean,
        val notificationState: String,
        val notificationEventCount: Int,
        val locationState: String,
        val locationEventCount: Int,
        val nativeReadCount: Int,
        val lastSource: String,
        val path: String
    )
}
