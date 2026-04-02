package io.blaha.groovitation

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.clearElement
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.DriverAtoms.webKeys
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityAvatarUploadInstrumentedTest {

    companion object {
        private const val TEST_IMAGE_NAME = "avatar-ci-upload.png"
        private const val FIXTURE_EMAIL = "fixture-user@groovitation.test"
        private const val FIXTURE_PASSWORD = "fixture-password"
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        clearFixtureState()
    }

    @After
    fun tearDown() {
        clearFixtureState()
    }

    @Test
    fun avatarUploadUsesNativeFileChooserAndShowsUpdatedAvatar() {
        seedDeterministicDownload()

        launchAvatarScenario().use { scenario ->
            val webView = waitForWebView(scenario, timeoutMs = 45_000)
            assertNotNull("Expected a WebView to be attached in MainActivity", webView)

            waitForPageState(webView!!, timeoutMs = 60_000) { state ->
                state.path == "/login" && state.hasLoginForm
            }.also { state ->
                assertTrue("Expected the fixture login page to load. State=$state", state.hasLoginForm)
            }

            onWebView().forceJavascriptEnabled()
            onWebView().withElement(findElement(Locator.ID, "login-email"))
                .perform(clearElement())
                .perform(webKeys(FIXTURE_EMAIL))
            onWebView().withElement(findElement(Locator.ID, "login-password"))
                .perform(clearElement())
                .perform(webKeys(FIXTURE_PASSWORD))
            onWebView().withElement(findElement(Locator.ID, "login-submit")).perform(webClick())

            waitForPageState(webView, timeoutMs = 30_000) { state ->
                state.path == "/users/edit" &&
                    state.hasAvatarForm &&
                    state.signedInUser.trim() == FIXTURE_EMAIL
            }.also { state ->
                assertTrue(
                    "Expected to land on the authenticated avatar page after login. State=$state",
                    state.path == "/users/edit" && state.hasAvatarForm
                )
            }

            tapElementWithUserActivation(webView, "avatar-input")
            selectSeededImageFromSystemPicker()
            waitForPageState(webView, timeoutMs = 45_000) { state ->
                state.path == "/users/edit" &&
                    state.hasAvatarForm &&
                    state.selectedAvatarName == TEST_IMAGE_NAME
            }.also { state ->
                assertEquals(
                    "Expected the selected file to be reflected back into the avatar form. State=$state",
                    TEST_IMAGE_NAME,
                    state.selectedAvatarName
                )
            }

            tapElementWithUserActivation(webView, "avatar-save")

            val uploadedState = waitForPageState(webView, timeoutMs = 45_000) { state ->
                state.path == "/users/edit" &&
                    state.hasAvatarForm &&
                    state.uploadVersion >= 1 &&
                    state.statusText.contains(TEST_IMAGE_NAME) &&
                    state.avatarSrc.contains("version=${state.uploadVersion}")
            }

            assertEquals(FIXTURE_EMAIL, uploadedState.signedInUser.trim())
            assertTrue(
                "Expected upload status to mention the seeded file name. State=$uploadedState",
                uploadedState.statusText.contains(TEST_IMAGE_NAME)
            )
            assertTrue(
                "Expected avatar image src to carry the persisted upload version. State=$uploadedState",
                uploadedState.avatarSrc.contains("version=1")
            )
        }
    }

    private fun launchAvatarScenario(): ActivityScenario<MainActivity> {
        waitForFixtureBackend(timeoutMs = 30_000)
        val intent = Intent(
            instrumentation.targetContext,
            MainActivity::class.java
        ).apply {
            putExtra(MainActivity.EXTRA_DISABLE_STARTUP_PERMISSION_CHAIN, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return ActivityScenario.launch(intent)
    }

    private fun clearFixtureState() {
        val targetContext = instrumentation.targetContext
        targetContext.getSharedPreferences("groovitation_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        instrumentation.runOnMainSync {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    private fun waitForFixtureBackend(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastFailure = "fixture backend was never contacted"
        while (System.currentTimeMillis() < deadline) {
            try {
                val connection = (URL("${BuildConfig.BASE_URL}/healthz").openConnection() as HttpURLConnection)
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                try {
                    connection.inputStream.use { input ->
                        if (connection.responseCode in 200..299 && input.bufferedReader().readText().trim() == "ok") {
                            return
                        }
                        lastFailure = "unexpected healthz response ${connection.responseCode}"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                lastFailure = error.message ?: error::class.java.simpleName
            }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for fixture backend at ${BuildConfig.BASE_URL}/healthz ($lastFailure)")
    }

    private fun seedDeterministicDownload() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(TEST_IMAGE_NAME),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val existingId = cursor.getLong(idColumn)
                resolver.delete(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existingId),
                    null,
                    null
                )
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, TEST_IMAGE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        requireNotNull(uri) { "Failed to create seeded download content URI" }
        resolver.openOutputStream(uri)?.use { output ->
            output.write(createDeterministicPng())
        } ?: error("Failed to open seeded download output stream")

        val publishedValues = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, publishedValues, null, null)
    }

    private fun createDeterministicPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 120, 220))
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun selectSeededImageFromSystemPicker() {
        val pickerVisible = waitForAnyObject(
            timeoutMs = 10_000,
            By.text(TEST_IMAGE_NAME),
            By.textContains(TEST_IMAGE_NAME),
            By.descContains(TEST_IMAGE_NAME),
            By.text("Browse"),
            By.textContains("Browse"),
            By.text("Downloads"),
            By.textContains("Downloads"),
            By.desc("More options"),
            By.descContains("More options"),
            By.desc("Show roots"),
            By.descContains("Show roots")
        ) != null
        assertTrue("Expected the Android image picker or document picker to appear", pickerVisible)

        if (waitForSeededFileObject(timeoutMs = 2_000) == null) {
            openDownloadsViaSystemPicker()
        }

        val target = waitForSeededFileObject(timeoutMs = 10_000)
        assertNotNull("Expected the seeded image to be selectable in the picker", target)
        target!!.click()

        val confirmButton = findAnyButton("Open", "Choose")
        confirmButton?.click()

        device.waitForIdle()
    }

    private fun findAnyButton(vararg labels: String): UiObject2? {
        for (label in labels) {
            device.findObject(By.text(label))?.let { return it }
            device.findObject(By.textContains(label))?.let { return it }
        }
        return null
    }

    private fun waitForAnyObject(timeoutMs: Long, vararg selectors: BySelector): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (selector in selectors) {
                device.findObject(selector)?.let { return it }
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun waitForSeededFileObject(timeoutMs: Long): UiObject2? {
        return waitForAnyObject(
            timeoutMs,
            By.text(TEST_IMAGE_NAME),
            By.textContains(TEST_IMAGE_NAME),
            By.descContains(TEST_IMAGE_NAME)
        )
    }

    private fun openDownloadsViaSystemPicker() {
        findAnyButton("Browse")?.let { browseButton ->
            browseButton.click()
            device.waitForIdle()
            if (waitForSeededFileObject(timeoutMs = 1_500) != null) {
                return
            }
        }

        findAnyByDescription("More options")?.click()
        device.waitForIdle()

        findAnyButton("Browse")?.click()
        device.waitForIdle()

        if (waitForSeededFileObject(timeoutMs = 1_500) != null) {
            return
        }

        findAnyByDescription("Show roots")?.click()
        device.waitForIdle()

        findAnyButton("Downloads")?.click()
        device.waitForIdle()
    }

    private fun findAnyByDescription(vararg labels: String): UiObject2? {
        for (label in labels) {
            device.findObject(By.desc(label))?.let { return it }
            device.findObject(By.descContains(label))?.let { return it }
        }
        return null
    }

    private fun tapElementWithUserActivation(webView: WebView, elementId: String) {
        val target = waitForTapTarget(webView, elementId, timeoutMs = 10_000)
        assertNotNull("Expected #$elementId to be visible in the WebView", target)
        injectTouch(webView, target!!)
    }

    private fun waitForTapTarget(
        webView: WebView,
        elementId: String,
        timeoutMs: Long
    ): TapTarget? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastTarget: TapTarget? = null
        while (System.currentTimeMillis() < deadline) {
            val target = evaluateTapTarget(webView, elementId)
            if (target != null) {
                lastTarget = target
                if (target.visible) {
                    return target
                }
            }
            Thread.sleep(250)
        }
        return lastTarget
    }

    private fun evaluateTapTarget(webView: WebView, elementId: String): TapTarget? {
        val script = """
            (function() {
              var element = document.getElementById(${JSONObject.quote(elementId)});
              if (!element) return null;
              element.scrollIntoView({ block: 'center', inline: 'center' });
              var rect = element.getBoundingClientRect();
              var viewportWidth = Math.max(window.innerWidth || 0, 1);
              var viewportHeight = Math.max(window.innerHeight || 0, 1);
              var centerX = rect.left + (rect.width / 2);
              var centerY = rect.top + (rect.height / 2);
              var visible = rect.width > 0 &&
                rect.height > 0 &&
                centerX >= 0 &&
                centerX <= viewportWidth &&
                centerY >= 0 &&
                centerY <= viewportHeight;
              return JSON.stringify({
                visible: visible,
                xFraction: centerX / viewportWidth,
                yFraction: centerY / viewportHeight
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

        if (!latch.await(10, TimeUnit.SECONDS)) return null
        val decoded = decodeJsString(rawResult) ?: return null
        val payload = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        return TapTarget(
            visible = payload.optBoolean("visible", false),
            xFraction = payload.optDouble("xFraction", -1.0).toFloat(),
            yFraction = payload.optDouble("yFraction", -1.0).toFloat()
        )
    }

    private fun injectTouch(webView: WebView, target: TapTarget) {
        instrumentation.runOnMainSync {
            webView.requestFocus()

            val maxLocalX = (webView.width - 2).coerceAtLeast(1).toFloat()
            val maxLocalY = (webView.height - 2).coerceAtLeast(1).toFloat()
            val localX = (target.xFraction * webView.width.toFloat()).coerceIn(1f, maxLocalX)
            val localY = (target.yFraction * webView.height.toFloat()).coerceIn(1f, maxLocalY)

            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                localX,
                localY,
                0
            )
            val upEvent = MotionEvent.obtain(
                downTime,
                downTime + 120,
                MotionEvent.ACTION_UP,
                localX,
                localY,
                0
            )

            try {
                webView.dispatchTouchEvent(downEvent)
                webView.dispatchTouchEvent(upEvent)
            } finally {
                downEvent.recycle()
                upEvent.recycle()
            }
        }

        instrumentation.waitForIdleSync()
        device.waitForIdle()
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

    private fun waitForPageState(
        webView: WebView,
        timeoutMs: Long,
        predicate: (PageState) -> Boolean
    ): PageState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastState = PageState()
        while (System.currentTimeMillis() < deadline) {
            val state = evaluatePageState(webView) ?: lastState
            lastState = state
            if (predicate(state)) {
                return state
            }
            Thread.sleep(500)
        }
        return lastState
    }

    private fun evaluatePageState(webView: WebView): PageState? {
        val script = """
            (function() {
              try {
                var fixture = window.__avatarFixture || {};
                var avatarImage = document.getElementById('avatar-image');
                var avatarStatus = document.getElementById('avatar-status');
                var user = document.getElementById('signed-in-user');
                var avatarInput = document.getElementById('avatar-input');
                var selectedFile = avatarInput && avatarInput.files && avatarInput.files.length > 0
                  ? avatarInput.files[0].name
                  : '';
                return JSON.stringify({
                  path: location.pathname,
                  readyState: document.readyState,
                  hasLoginForm: !!document.getElementById('login-form'),
                  hasAvatarForm: !!document.getElementById('avatar-form'),
                  statusText: avatarStatus ? String(avatarStatus.textContent || '') : '',
                  avatarSrc: avatarImage ? String(avatarImage.getAttribute('src') || '') : '',
                  uploadVersion: fixture.version || 0,
                  signedInUser: user ? String(user.textContent || '') : '',
                  selectedAvatarName: selectedFile
                });
              } catch (e) {
                return JSON.stringify({
                  path: '',
                  readyState: 'error',
                  hasLoginForm: false,
                  hasAvatarForm: false,
                  statusText: String(e),
                  avatarSrc: '',
                  uploadVersion: 0,
                  signedInUser: '',
                  selectedAvatarName: ''
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
        return PageState(
            path = payload.optString("path", ""),
            readyState = payload.optString("readyState", "unknown"),
            hasLoginForm = payload.optBoolean("hasLoginForm", false),
            hasAvatarForm = payload.optBoolean("hasAvatarForm", false),
            statusText = payload.optString("statusText", ""),
            avatarSrc = payload.optString("avatarSrc", ""),
            uploadVersion = payload.optInt("uploadVersion", 0),
            signedInUser = payload.optString("signedInUser", ""),
            selectedAvatarName = payload.optString("selectedAvatarName", "")
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

    private data class PageState(
        val path: String = "",
        val readyState: String = "unknown",
        val hasLoginForm: Boolean = false,
        val hasAvatarForm: Boolean = false,
        val statusText: String = "",
        val avatarSrc: String = "",
        val uploadVersion: Int = 0,
        val signedInUser: String = "",
        val selectedAvatarName: String = ""
    )

    private data class TapTarget(
        val visible: Boolean,
        val xFraction: Float,
        val yFraction: Float
    )
}
