package io.blaha.groovitation.components

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class BiometricComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, delegate) {

    companion object {
        private const val TAG = "BiometricComponent"
    }

    private val fragment: Fragment
        get() = delegate.destination.fragment

    override fun onReceive(message: Message) {
        Log.d(TAG, "Received message: ${message.event}")

        when (message.event) {
            "authenticate" -> handleAuthenticate(message)
            "checkAvailability" -> handleCheckAvailability(message)
            else -> Log.w(TAG, "Unknown event: ${message.event}")
        }
    }

    private fun handleCheckAvailability(message: Message) {
        val context = fragment.context ?: return

        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        val available = canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
        replyTo("checkAvailability", AvailabilityReply(available = available))
    }

    private fun handleAuthenticate(message: Message) {
        val activity = fragment.activity as? FragmentActivity
        if (activity == null) {
            replyTo("authenticate", AuthReply(success = false, error = "Activity not available"))
            return
        }

        val biometricManager = BiometricManager.from(activity)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            replyTo("authenticate", AuthReply(success = false, error = "Biometric authentication not available"))
            return
        }

        val data = message.data<AuthenticateData>()
        val title = data?.title ?: "Authenticate"
        val subtitle = data?.subtitle ?: "Confirm your identity"
        val cancelText = data?.cancelText ?: "Cancel"

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(cancelText)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.d(TAG, "Biometric authentication succeeded")
                replyTo("authenticate", AuthReply(success = true))
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Biometric authentication failed")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.e(TAG, "Biometric authentication error: $errString (code: $errorCode)")
                val errorMessage = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "User cancelled"
                    BiometricPrompt.ERROR_LOCKOUT -> "Too many attempts. Try again later."
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Biometric locked. Use device credentials."
                    else -> errString.toString()
                }
                replyTo("authenticate", AuthReply(success = false, error = errorMessage))
            }
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)
    }

    @Serializable
    data class AuthenticateData(
        @SerialName("title") val title: String? = null,
        @SerialName("subtitle") val subtitle: String? = null,
        @SerialName("cancelText") val cancelText: String? = null
    )

    @Serializable
    data class AuthReply(
        @SerialName("success") val success: Boolean,
        @SerialName("error") val error: String? = null
    )

    @Serializable
    data class AvailabilityReply(
        @SerialName("available") val available: Boolean
    )
}
