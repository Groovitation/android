package io.blaha.groovitation

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class NativeGoogleSignInRequest(
    val serverClientId: String,
    val returnUrl: String,
    val fallbackUrl: String,
)

data class NativeGoogleAuthResult(
    val authUrl: String,
)

sealed class NativeGoogleSignInAction {
    data class Navigate(val url: String) : NativeGoogleSignInAction()
    data class OpenBrowser(val url: String) : NativeGoogleSignInAction()
}

interface GoogleIdTokenProvider {
    suspend fun getIdToken(serverClientId: String): String?
}

interface NativeGoogleAuthApi {
    suspend fun authenticate(idToken: String, returnUrl: String, cookieHeader: String?): NativeGoogleAuthResult?
}

class NativeGoogleSignInCoordinator(
    private val googleIdTokenProvider: GoogleIdTokenProvider,
    private val nativeGoogleAuthApi: NativeGoogleAuthApi,
) {
    suspend fun signIn(
        request: NativeGoogleSignInRequest,
        cookieHeader: String?,
    ): NativeGoogleSignInAction {
        val idToken = googleIdTokenProvider.getIdToken(request.serverClientId)
            ?: return NativeGoogleSignInAction.OpenBrowser(request.fallbackUrl)
        val authResult = nativeGoogleAuthApi.authenticate(idToken, request.returnUrl, cookieHeader)
            ?: return NativeGoogleSignInAction.OpenBrowser(request.fallbackUrl)
        return NativeGoogleSignInAction.Navigate(authResult.authUrl)
    }
}

class CredentialManagerGoogleIdTokenProvider(
    private val activity: Activity,
) : GoogleIdTokenProvider {
    companion object {
        private const val TAG = "NativeGoogleSignIn"
    }

    private val credentialManager = CredentialManager.create(activity)

    override suspend fun getIdToken(serverClientId: String): String? {
        if (serverClientId.isBlank()) return null

        val authorizedAccountToken = requestGoogleIdToken(
            serverClientId = serverClientId,
            filterByAuthorizedAccounts = true,
            autoSelectEnabled = true,
        )
        if (authorizedAccountToken != null) return authorizedAccountToken

        return requestGoogleIdToken(
            serverClientId = serverClientId,
            filterByAuthorizedAccounts = false,
            autoSelectEnabled = false,
        )
    }

    private suspend fun requestGoogleIdToken(
        serverClientId: String,
        filterByAuthorizedAccounts: Boolean,
        autoSelectEnabled: Boolean,
    ): String? {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setAutoSelectEnabled(autoSelectEnabled)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential as? CustomCredential ?: return null
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return null
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: NoCredentialException) {
            null
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(TAG, "Failed to parse Google ID token credential", e)
            null
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager Google sign-in failed", e)
            null
        }
    }
}

class BackendNativeGoogleAuthApi(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
) : NativeGoogleAuthApi {
    companion object {
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    }

    override suspend fun authenticate(
        idToken: String,
        returnUrl: String,
        cookieHeader: String?,
    ): NativeGoogleAuthResult? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("id_token", idToken)
            .put("return_url", returnUrl)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE.toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/oauth/google/native-id-token-authenticate")
            .post(body)
            .header("Accept", "application/json")
            .apply {
                if (!cookieHeader.isNullOrBlank()) {
                    header("Cookie", cookieHeader)
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val responseBody = response.body?.string().orEmpty()
            val payload = JSONObject(responseBody)
            val authUrl = payload.optString("auth_url")
            if (authUrl.isBlank()) null else NativeGoogleAuthResult(authUrl)
        }
    }
}
