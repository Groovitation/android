package io.blaha.groovitation

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityWebStylingInstrumentedTest {

    @Test
    fun launchLoadsCoreWebStylesheets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            assertNotNull("Expected a WebView to be attached in MainActivity", webView)

            val styleProbe = waitForStyleProbe(webView!!, timeoutMs = 60_000)

            assertTrue(
                "Expected at least one core stylesheet (/assets/application.css or bootstrap) " +
                    "to be loaded. Probe=$styleProbe",
                styleProbe.hasCoreCss
            )
            assertTrue(
                "Expected multiple stylesheets to be active after launch. Probe=$styleProbe",
                styleProbe.stylesheetCount >= 2
            )
        }
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

    private fun waitForStyleProbe(
        webView: WebView,
        timeoutMs: Long
    ): StyleProbe {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastProbe = StyleProbe(
            hasCoreCss = false,
            stylesheetCount = 0,
            linkCount = 0,
            readyState = "unknown",
            path = ""
        )

        while (System.currentTimeMillis() < deadline) {
            val probe = evaluateStyleProbe(webView) ?: lastProbe
            lastProbe = probe
            if (probe.readyState == "complete" && probe.hasCoreCss && probe.stylesheetCount >= 2) {
                return probe
            }
            Thread.sleep(500)
        }

        return lastProbe
    }

    private fun evaluateStyleProbe(webView: WebView): StyleProbe? {
        val script = """
            (function() {
              try {
                var sheetHrefs = Array.prototype.map.call(
                  document.styleSheets || [],
                  function(sheet) { return sheet && sheet.href ? String(sheet.href) : ''; }
                );
                var hasCoreCss = sheetHrefs.some(function(href) {
                  return href.indexOf('/assets/application.css') !== -1 ||
                         href.indexOf('/bootstrap.min.css') !== -1 ||
                         href.indexOf('/bootstrap.rtl.min.css') !== -1;
                });
                return JSON.stringify({
                  hasCoreCss: hasCoreCss,
                  stylesheetCount: sheetHrefs.length,
                  linkCount: document.querySelectorAll('link[rel="stylesheet"]').length,
                  readyState: document.readyState,
                  path: location.pathname
                });
              } catch (e) {
                return JSON.stringify({
                  hasCoreCss: false,
                  stylesheetCount: 0,
                  linkCount: 0,
                  readyState: document.readyState,
                  path: location.pathname,
                  error: String(e)
                });
              }
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

        if (!latch.await(10, TimeUnit.SECONDS)) return null
        val decoded = decodeJsString(rawResult) ?: return null
        val payload = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        return StyleProbe(
            hasCoreCss = payload.optBoolean("hasCoreCss", false),
            stylesheetCount = payload.optInt("stylesheetCount", 0),
            linkCount = payload.optInt("linkCount", 0),
            readyState = payload.optString("readyState", "unknown"),
            path = payload.optString("path", "")
        )
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

    data class StyleProbe(
        val hasCoreCss: Boolean,
        val stylesheetCount: Int,
        val linkCount: Int,
        val readyState: String,
        val path: String
    )
}
