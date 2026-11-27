package com.swirlock.flightcomputer

/** The independent input/estimator selected for the current navigation solution. */
enum class NavigationSource { MAG_INERTIAL, GPS }

/**
 * Sensor-independent navigation state consumed by the instrument UI and flight session.
 * Values may be null until a provider has initialized the corresponding estimate.
 */
data class NavigationSolution(
    val source: NavigationSource,
    val directionDegrees: Float? = null,
    val magneticHeadingValid: Boolean = false,
    val groundTrackDegrees: Float? = null,
    val groundSpeedMps: Float? = null,
    val verticalSpeedMps: Float? = null,
    val latitudeDegrees: Double? = null,
    val longitudeDegrees: Double? = null,
    val localEastM: Double? = null,
    val localNorthM: Double? = null,
    val localUpM: Double? = null,
    val homeBearingDegrees: Float? = null,
    val homeDistanceM: Float? = null,
    val confidence: Float? = null,
    val insElapsedSeconds: Long? = null,
    val timestampElapsedRealtimeMs: Long,
)

/** A provider owns one input path and publishes solutions without exposing hardware APIs. */
interface NavigationProvider {
    val source: NavigationSource
    fun start(onSolutionChanged: (NavigationSolution?) -> Unit)
    fun currentSolution(): NavigationSolution?
    fun stop()
}
