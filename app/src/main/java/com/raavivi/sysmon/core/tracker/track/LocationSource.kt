package com.raavivi.sysmon.core.tracker.track

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.raavivi.sysmon.core.tracker.model.Fix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Thin wrapper over [com.google.android.gms.location.FusedLocationProviderClient]. */
class LocationSource(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Starts periodic sampling: balanced power, one fix every [sampleS] whether or not the phone
     * moved, so a stationary phone still leaves per-minute stamps.
     */
    @SuppressLint("MissingPermission")
    fun startUpdates(sampleS: Int, callback: LocationCallback) {
        val ms = sampleS.coerceAtLeast(MIN_SAMPLE_S) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Permission revoked: the status screen shows it; no updates until fixed.
        }
    }

    fun stopUpdates(callback: LocationCallback) {
        client.removeLocationUpdates(callback)
    }

    /** One fresh high-accuracy fix, or null on timeout, missing permission or no location. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(timeoutMs: Long = CURRENT_TIMEOUT_MS): Location? = try {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(0)
            .build()
        withTimeoutOrNull(timeoutMs + 5_000) { client.getCurrentLocation(request, null).await() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MIN_SAMPLE_S = 30
        const val CURRENT_TIMEOUT_MS = 30_000L
    }
}

/** Builds a [Fix] from an Android [Location] plus the current device status. */
fun Location.toFix(source: String, status: DeviceStatus): Fix = Fix(
    ts = time / 1000.0,
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    altitudeM = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    provider = provider,
    source = source,
    batteryPct = status.batteryPct,
    charging = status.charging,
    network = status.network,
)
