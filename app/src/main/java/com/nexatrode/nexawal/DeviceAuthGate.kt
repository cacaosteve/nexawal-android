package com.nexatrode.nexawal

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DeviceAuthGate {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean {
        val mgr = context.getSystemService(BiometricManager::class.java) ?: return false
        return try {
            mgr.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun authenticate(
        activity: ComponentActivity,
        title: String,
        subtitle: String,
    ) {
        if (!isAvailable(activity)) {
            throw IllegalStateException("Biometric or device credential authentication is not available")
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            val executor = Executor { command -> activity.runOnUiThread(command) }
            val cancellationSignal = CancellationSignal()

            try {
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()

                prompt.authenticate(
                    cancellationSignal,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            if (!continuation.isActive) return
                            continuation.resumeWithException(
                                IllegalStateException(errString?.toString() ?: "Authentication failed")
                            )
                        }

                        override fun onAuthenticationFailed() {
                            // Let the system continue prompting until the user succeeds or cancels.
                        }
                    }
                )
            } catch (t: SecurityException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Biometric permission is missing or unavailable")
                    )
                }
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
            }
        }
    }
}
