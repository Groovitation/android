package io.blaha.groovitation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeGoogleSignInCoordinatorTest {

    @Test
    fun signInNavigatesWhenCredentialManagerAndBackendSucceed() = runTest {
        val coordinator = NativeGoogleSignInCoordinator(
            googleIdTokenProvider = object : GoogleIdTokenProvider {
                override suspend fun getIdToken(serverClientId: String): String? = "google-id-token"
            },
            nativeGoogleAuthApi = object : NativeGoogleAuthApi {
                override suspend fun authenticate(
                    idToken: String,
                    returnUrl: String,
                    cookieHeader: String?,
                ): NativeGoogleAuthResult? = NativeGoogleAuthResult("https://groovitation.blaha.io/oauth/native-authenticate?token=abc")
            },
        )

        val action = coordinator.signIn(
            NativeGoogleSignInRequest(
                serverClientId = "server-client-id",
                returnUrl = "/plan",
                fallbackUrl = "https://groovitation.blaha.io/oauth/google/authorize?return_url=/plan",
            ),
            cookieHeader = "_user_interface_session=guest-token",
        )

        assertEquals(
            NativeGoogleSignInAction.Navigate("https://groovitation.blaha.io/oauth/native-authenticate?token=abc"),
            action,
        )
    }

    @Test
    fun signInFallsBackToBrowserWhenNoIdTokenIsAvailable() = runTest {
        val fallbackUrl = "https://groovitation.blaha.io/oauth/google/authorize?return_url=/"
        val coordinator = NativeGoogleSignInCoordinator(
            googleIdTokenProvider = object : GoogleIdTokenProvider {
                override suspend fun getIdToken(serverClientId: String): String? = null
            },
            nativeGoogleAuthApi = object : NativeGoogleAuthApi {
                override suspend fun authenticate(
                    idToken: String,
                    returnUrl: String,
                    cookieHeader: String?,
                ): NativeGoogleAuthResult? = error("backend should not be called")
            },
        )

        val action = coordinator.signIn(
            NativeGoogleSignInRequest(
                serverClientId = "server-client-id",
                returnUrl = "/",
                fallbackUrl = fallbackUrl,
            ),
            cookieHeader = null,
        )

        assertEquals(NativeGoogleSignInAction.OpenBrowser(fallbackUrl), action)
    }

    @Test
    fun signInFallsBackToBrowserWhenBackendRejectsIdToken() = runTest {
        val fallbackUrl = "https://groovitation.blaha.io/oauth/google/authorize?return_url=/users/sign_up"
        val coordinator = NativeGoogleSignInCoordinator(
            googleIdTokenProvider = object : GoogleIdTokenProvider {
                override suspend fun getIdToken(serverClientId: String): String? = "google-id-token"
            },
            nativeGoogleAuthApi = object : NativeGoogleAuthApi {
                override suspend fun authenticate(
                    idToken: String,
                    returnUrl: String,
                    cookieHeader: String?,
                ): NativeGoogleAuthResult? = null
            },
        )

        val action = coordinator.signIn(
            NativeGoogleSignInRequest(
                serverClientId = "server-client-id",
                returnUrl = "/users/sign_up",
                fallbackUrl = fallbackUrl,
            ),
            cookieHeader = "_user_interface_session=guest-token",
        )

        assertEquals(NativeGoogleSignInAction.OpenBrowser(fallbackUrl), action)
    }
}
