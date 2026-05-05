package io.blaha.groovitation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class NotificationTokenRegistrarTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        NotificationTestHooks.clear(application)
        ShadowLog.clear()
    }

    @Test
    fun successfulRegistrationReturnsTrue() {
        val result = registerWith(clientReturning(code = 200, message = "OK", body = "{}"))

        assertTrue(result)
    }

    @Test
    fun unauthorizedRegistrationLogsStatusAndBody() {
        val result = registerWith(
            clientReturning(
                code = 401,
                message = "Unauthorized",
                body = "missing session cookie"
            )
        )

        assertFalse(result)
        assertLogContains("HTTP 401 Unauthorized")
        assertLogContains("missing session cookie")
    }

    @Test
    fun serverErrorRegistrationLogsStatus() {
        val result = registerWith(clientReturning(code = 503, message = "Unavailable", body = "retry later"))

        assertFalse(result)
        assertLogContains("HTTP 503 Unavailable")
    }

    @Test
    fun failureLogTruncatesResponseBodyPreview() {
        val longBody = "x".repeat(600)

        val result = registerWith(clientReturning(code = 400, message = "Bad Request", body = longBody))

        assertFalse(result)
        val message = failureLogMessage()
        assertTrue(message.contains("HTTP 400 Bad Request"))
        assertTrue(message.contains("body=${"x".repeat(512)}"))
        assertFalse(message.contains("x".repeat(513)))
    }

    @Test
    fun networkExceptionReturnsFalse() {
        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("network down")
            }
            .build()

        val result = registerWith(client)

        assertFalse(result)
    }

    private fun registerWith(httpClient: OkHttpClient): Boolean =
        NotificationTokenRegistrar.register(
            context = application,
            httpClient = httpClient,
            url = "https://example.test/api/notifications/tokens",
            token = "fcm-token",
            cookie = "session=abc"
        )

    private fun clientReturning(code: Int, message: String, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body(body.toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()

    private fun assertLogContains(expected: String) {
        assertTrue(
            "expected NotificationTokenReg log to contain '$expected'",
            failureLogMessage().contains(expected)
        )
    }

    private fun failureLogMessage(): String =
        ShadowLog.getLogsForTag("NotificationTokenReg")
            .joinToString("\n") { it.msg }
}
