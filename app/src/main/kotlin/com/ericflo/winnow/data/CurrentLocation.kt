package com.ericflo.winnow.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One location fix for "share my location", from whichever provider is on; nothing is kept or tracked. */
class CurrentLocation(private val context: Context) {
    /**
     * The phone's location: a fix from the last couple of minutes from any provider, otherwise
     * the first fix any enabled provider produces within [timeoutMillis], or null.
     */
    @SuppressLint("MissingPermission") // The caller asks for location permission first.
    suspend fun get(timeoutMillis: Long = 15_000): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList()).filter { it != LocationManager.PASSIVE_PROVIDER }
        if (providers.isEmpty()) return null
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < RECENT_MILLIS }
            .maxByOrNull { it.time }
            ?.let { return it }
        // Every provider at once; the first real fix wins and the rest are cancelled.
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val signals = providers.map { CancellationSignal() }
                var answered = 0
                continuation.invokeOnCancellation { signals.forEach(CancellationSignal::cancel) }
                providers.forEachIndexed { i, provider ->
                    runCatching {
                        manager.getCurrentLocation(provider, signals[i], context.mainExecutor) { location ->
                            answered++
                            if (!continuation.isActive) return@getCurrentLocation
                            if (location != null) {
                                signals.forEach(CancellationSignal::cancel)
                                continuation.resume(location)
                            } else if (answered == providers.size) {
                                continuation.resume(null)
                            }
                        }
                    }.onFailure { answered++ }
                }
            }
        }
    }

    companion object {
        private const val RECENT_MILLIS = 2 * 60_000L

        /** A map link any phone opens: Google Maps on Android, and iPhones offer to open it too. */
        fun mapLink(location: Location): String =
            String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", location.latitude, location.longitude)
    }
}
