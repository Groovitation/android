package io.blaha.groovitation

import android.Manifest
import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityGeolocationBridgeInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val locationPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    @After
    fun tearDown() {
        GeolocationTestHooks.reset()
    }

    @Test
    fun geolocationPermissionBridgeAndForegroundLocationStayDeterministic() {
        GeolocationTestHooks.reset()
        GeolocationTestHooks.overrideFreshLocation = GeolocationTestHooks.TestLocation(
            latitude = TEST_LATITUDE,
            longitude = TEST_LONGITUDE,
            accuracyMeters = TEST_ACCURACY_METERS
        )

        launchGeolocationScenario().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            assertNotNull("Expected a WebView in MainActivity for geolocation bridge test", webView)

            loadHarnessPage(webView!!)
            waitForHarnessBridge(webView, timeoutMs = 20_000)

            triggerPermissionBridgeProbe(webView)
            val permissionProbe = waitForJsonProbe(
                webView,
                "window.__locationPermissionBridgeProbe",
                timeoutMs = 10_000
            )
            assertTrue(
                "Expected native location permission bridge event to report granted=true, probe=$permissionProbe",
                permissionProbe.optBoolean("granted", false)
            )

            GeolocationTestHooks.clearWebViewGeolocationDecision()
            triggerWebViewGeolocationProbe(webView)
            waitForCondition(timeoutMs = 10_000) {
                GeolocationTestHooks.lastWebViewGeolocationDecision != null
            }
            assertEquals(
                GeolocationTestHooks.WebViewGeolocationDecision.AUTO_GRANTED,
                GeolocationTestHooks.lastWebViewGeolocationDecision
            )

            triggerFreshLocationProbe(webView)
            val locationProbe = waitForJsonProbe(
                webView,
                "window.__nativeFreshLocationProbe",
                timeoutMs = 10_000
            )
            assertTrue(
                "Expected native fresh location probe to report success=true, probe=$locationProbe",
                locationProbe.optBoolean("success", false)
            )
            assertEquals(TEST_LATITUDE, locationProbe.getDouble("latitude"), 0.0001)
            assertEquals(TEST_LONGITUDE, locationProbe.getDouble("longitude"), 0.0001)
            assertEquals(TEST_ACCURACY_METERS.toDouble(), locationProbe.getDouble("accuracy"), 0.0001)
        }
    }

    private fun launchGeolocationScenario(): ActivityScenario<MainActivity> {
        val intent = Intent(
            instrumentation.targetContext,
            MainActivity::class.java
        ).apply {
            putExtra(MainActivity.EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitForWebView(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long
    ): WebView? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var found: WebView? = null
            scenario.onActivity { activity ->
                found = findWebView(activity.window.decorView.rootView)
            }
            if (found != null) return found
            Thread.sleep(250)
        }
        return null
    }

    private fun loadHarnessPage(webView: WebView) {
        val latch = CountDownLatch(1)
        webView.post {
            webView.stopLoading()
            webView.loadDataWithBaseURL(
                TEST_BASE_URL,
                HARNESS_HTML,
                "text/html",
                "UTF-8",
                null
            )
            latch.countDown()
        }
        assertTrue("Harness page enqueue should complete", latch.await(5, TimeUnit.SECONDS))
    }

    private fun waitForHarnessBridge(webView: WebView, timeoutMs: Long) {
        waitForCondition(timeoutMs) {
            val probe = evaluateJson(
                webView,
                """
                (function() {
                  return JSON.stringify({
                    harnessReady: !!(document.body && document.body.dataset.harnessReady === '1'),
                    bridgeReady: !!(window.GroovitationNative &&
                      typeof window.GroovitationNative.requestLocationPermission === 'function' &&
                      typeof window.GroovitationNative.requestFreshLocation === 'function' &&
                      typeof window.GroovitationNative.hasLocationPermission === 'function')
                  });
                })();
                """.trimIndent()
            ) ?: return@waitForCondition false

            probe.optBoolean("harnessReady", false) && probe.optBoolean("bridgeReady", false)
        }
    }

    private fun triggerPermissionBridgeProbe(webView: WebView) {
        evaluateJson(
            webView,
            """
            (function() {
              window.__locationPermissionBridgeProbe = null;
              window.addEventListener('groovitation:location-permission', function handler(event) {
                window.__locationPermissionBridgeProbe = {
                  granted: !!(event.detail && event.detail.granted)
                };
                window.removeEventListener('groovitation:location-permission', handler);
              });
              window.GroovitationNative.requestLocationPermission();
              return JSON.stringify({ started: true });
            })();
            """.trimIndent()
        )
    }

    private fun triggerWebViewGeolocationProbe(webView: WebView) {
        evaluateJson(
            webView,
            """
            (function() {
              window.__webViewGeolocationProbe = { started: true };
              navigator.geolocation.getCurrentPosition(
                function(position) {
                  window.__webViewGeolocationProbe = {
                    success: true,
                    latitude: position.coords.latitude,
                    longitude: position.coords.longitude,
                    accuracy: position.coords.accuracy
                  };
                },
                function(error) {
                  window.__webViewGeolocationProbe = {
                    success: false,
                    code: error.code,
                    message: error.message
                  };
                },
                { enableHighAccuracy: false, timeout: 3000, maximumAge: 0 }
              );
              return JSON.stringify(window.__webViewGeolocationProbe);
            })();
            """.trimIndent()
        )
    }

    private fun triggerFreshLocationProbe(webView: WebView) {
        evaluateJson(
            webView,
            """
            (function() {
              window.__nativeFreshLocationProbe = null;
              window.addEventListener('groovitation:location', function handler(event) {
                if (!event.detail) { return; }
                window.__nativeFreshLocationProbe = {
                  success: !!event.detail.success,
                  latitude: event.detail.latitude,
                  longitude: event.detail.longitude,
                  accuracy: event.detail.accuracy
                };
                window.removeEventListener('groovitation:location', handler);
              });
              window.GroovitationNative.requestFreshLocation();
              return JSON.stringify({ started: true });
            })();
            """.trimIndent()
        )
    }

    private fun waitForJsonProbe(
        webView: WebView,
        expression: String,
        timeoutMs: Long
    ): JSONObject {
        var lastProbe: JSONObject? = null
        waitForCondition(timeoutMs) {
            val probe = evaluateJson(
                webView,
                """
                (function() {
                  var probe = $expression;
                  return probe ? JSON.stringify(probe) : null;
                })();
                """.trimIndent()
            )
            if (probe != null) {
                lastProbe = probe
                return@waitForCondition true
            }
            false
        }
        return lastProbe ?: error("Timed out waiting for JSON probe: $expression")
    }

    private fun evaluateJson(webView: WebView, script: String): JSONObject? {
        val latch = CountDownLatch(1)
        var rawResult: String? = null
        webView.post {
            webView.evaluateJavascript(script) { value ->
                rawResult = value
                latch.countDown()
            }
        }

        if (!latch.await(10, TimeUnit.SECONDS)) return null
        val decoded = decodeJsString(rawResult) ?: return null
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for condition after ${timeoutMs}ms")
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

    private fun decodeJsString(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value == "null" || value.isEmpty()) return null
        return runCatching {
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                JSONObject("{\"v\":$value}").getString("v")
            } else {
                value
            }
        }.getOrNull()
    }

    companion object {
        private const val TEST_BASE_URL = "https://groovitation.blaha.io/"
        private const val TEST_LATITUDE = 31.762
        private const val TEST_LONGITUDE = -106.485
        private const val TEST_ACCURACY_METERS = 12f
        private const val HARNESS_HTML = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <title>Geolocation Harness</title>
              </head>
              <body data-harness-ready="1">
                <main id="harness">Geolocation Harness</main>
              </body>
            </html>
        """
    }
}
