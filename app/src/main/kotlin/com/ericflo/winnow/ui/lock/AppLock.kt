package com.ericflo.winnow.ui.lock

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock: after Winnow has been out of sight for [GRACE_MILLIS] (long enough that picking a
 * photo or a contact doesn't count), opening it again needs a fingerprint, face or the screen
 * lock. It fails open: a phone with no way to authenticate isn't allowed to lock the user out
 * of their messages.
 */
class AppLock {
    enum class State { CHECKING, LOCKED, UNLOCKED }

    private val _state = MutableStateFlow(State.CHECKING)
    /** Screens cover themselves unless this is [State.UNLOCKED], and prompt when it's [State.LOCKED]. */
    val state: StateFlow<State> = _state.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var leftAt: Long? = null
    private var unlockedOnce = false
    private var settingsLoaded = false

    /** From settings (the app container watches them). The first value ends [State.CHECKING]. */
    fun update(enabled: Boolean) {
        // Turning it on from inside the app counts as being unlocked right now.
        if (enabled && !_enabled.value && settingsLoaded) unlockedOnce = true
        _enabled.value = enabled
        if (!settingsLoaded) {
            settingsLoaded = true
            _state.value = if (enabled) State.LOCKED else State.UNLOCKED
        } else if (!enabled) {
            _state.value = State.UNLOCKED
        }
    }

    /** Call when a screen starts: locks again after more than [GRACE_MILLIS] away. */
    fun onStart() {
        if (!settingsLoaded) return
        val away = leftAt?.let { SystemClock.elapsedRealtime() - it }
        if (_enabled.value && (!unlockedOnce || (away != null && away > GRACE_MILLIS))) _state.value = State.LOCKED
    }

    fun onStop() {
        leftAt = SystemClock.elapsedRealtime()
    }

    // USE_BIOMETRIC is declared in the manifest (and in the merged one); lint misses it here.
    @SuppressLint("MissingPermission")
    fun authenticate(activity: Activity) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Winnow")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlock()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Nothing to authenticate with any more (the screen lock was removed): let them in.
                if (errorCode in NO_WAY_TO_AUTHENTICATE) unlock()
            }
        })
    }

    private fun unlock() {
        unlockedOnce = true
        leftAt = null
        _state.value = State.UNLOCKED
    }

    private companion object {
        const val GRACE_MILLIS = 60_000L
        val NO_WAY_TO_AUTHENTICATE = setOf(
            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
        )
    }
}

/** Covers the app while it's locked (or while the lock setting is still loading). */
@Composable
fun LockScreen(checking: Boolean, onUnlock: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        if (checking) return@Surface
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize().padding(32.dp),
        ) {
            Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
            Text("Winnow is locked", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}
