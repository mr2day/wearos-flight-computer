package com.swirlock.flightcomputer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log

/**
 * Separate GNSS adapter for a later GPS-enabled build. It is deliberately not instantiated by
 * the current MAG + inertial exercise. Enabling it later requires adding location permissions
 * and selecting this provider at the app composition root.
 */
class GpsNavigationProvider(context: Context) : NavigationProvider {
    override val source = NavigationSource.GPS

    private val appContext = context.applicationContext
    private val locations = appContext.getSystemService(LocationManager::class.java)
    private var callback: ((NavigationSolution?) -> Unit)? = null
    private var latest: NavigationSolution? = null
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()
            val direction = location.takeIf {
                it.hasBearing() && it.hasSpeed() && it.speed >= MIN_TRACK_SPEED_MPS &&
                    (!it.hasAccuracy() || it.accuracy <= MAX_TRACK_ACCURACY_M)
            }?.bearing
            latest = NavigationSolution(
                source = NavigationSource.GPS,
                directionDegrees = direction,
                groundSpeedMps = location.takeIf { it.hasSpeed() }?.speed,
                latitudeDegrees = location.takeIf { it.hasAccuracy() && it.accuracy <= MAX_POSITION_ACCURACY_M }
                    ?.latitude,
                longitudeDegrees = location.takeIf { it.hasAccuracy() && it.accuracy <= MAX_POSITION_ACCURACY_M }
                    ?.longitude,
                confidence = location.takeIf { it.hasAccuracy() }
                    ?.let { (1f - it.accuracy / MAX_POSITION_ACCURACY_M).coerceIn(0f, 1f) },
                timestampElapsedRealtimeMs = now,
            )
            callback?.invoke(latest)
        }
    }

    override fun start(onSolutionChanged: (NavigationSolution?) -> Unit) {
        callback = onSolutionChanged
        if (started) return
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "GPS provider inactive: location permission is not enabled in this build")
            return
        }
        try {
            if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
            locations.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_UPDATE_INTERVAL_MS,
                0f,
                appContext.mainExecutor,
                listener,
            )
            started = true
        } catch (error: SecurityException) {
            Log.w(TAG, "GPS provider could not start", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "GPS provider is unavailable on this device", error)
        }
    }

    override fun currentSolution(): NavigationSolution? {
        val value = latest ?: return null
        return value.takeIf { SystemClock.elapsedRealtime() - it.timestampElapsedRealtimeMs <= MAX_FIX_AGE_MS }
    }

    override fun stop() {
        if (started) locations.removeUpdates(listener)
        started = false
        callback = null
    }

    private companion object {
        const val TAG = "SwirlockGPS"
        const val GPS_UPDATE_INTERVAL_MS = 1_000L
        const val MAX_FIX_AGE_MS = 5_000L
        const val MIN_TRACK_SPEED_MPS = 1.5f
        const val MAX_TRACK_ACCURACY_M = 30f
        const val MAX_POSITION_ACCURACY_M = 50f
    }
}
