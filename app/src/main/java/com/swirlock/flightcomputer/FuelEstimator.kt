package com.swirlock.flightcomputer

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max

enum class FuelStatus { NORMAL, LOW, CRITICAL }

enum class FuelPhase { CLIMB, LEVEL, DESCENT, UNKNOWN }

data class FuelEstimate(
    val remainingFuelL: Double,
    val initialFuelL: Double,
    val burnRateLph: Double,
    val estimatedMinutesRemaining: Double?,
    val status: FuelStatus,
    val phase: FuelPhase,
    val flightActive: Boolean,
)

private data class FuelSpeedBand(
    val maximumHorizontalSpeedKmh: Double,
    val climbLph: Double,
    val levelLph: Double,
    val descentLph: Double,
)

/** Tunable fuel-estimation constants are loaded from app/src/main/assets/fuel_model.json. */
private data class FuelModelConfiguration(
    val initialFuelL: Double,
    val takeoffBurnL: Double,
    val takeoffSpeedKmh: Double,
    val takeoffClimbSpeedMps: Double,
    val takeoffConfirmationMs: Long,
    val climbAtOrAboveMps: Double,
    val descentAtOrBelowMps: Double,
    val speedFilterTimeConstantSeconds: Double,
    val phaseConfirmationMs: Long,
    val speedBands: List<FuelSpeedBand>,
    val unknownPhaseLph: Double,
    val orangeAtL: Double,
    val redAtL: Double,
    val redEnduranceMinutes: Double,
) {
    companion object {
        fun load(context: Context): FuelModelConfiguration {
            val root = context.assets.open("fuel_model.json").bufferedReader().use { JSONObject(it.readText()) }
            val takeoff = root.getJSONObject("takeoffDetection")
            val phase = root.getJSONObject("phaseDetection")
            val warnings = root.getJSONObject("fuelWarnings")
            val bandsJson = root.getJSONArray("speedBands")
            val bands = (0 until bandsJson.length()).map { index ->
                val band = bandsJson.getJSONObject(index)
                FuelSpeedBand(
                    maximumHorizontalSpeedKmh = band.getDouble("maximumHorizontalSpeedKmh"),
                    climbLph = band.getDouble("climbLph"),
                    levelLph = band.getDouble("levelLph"),
                    descentLph = band.getDouble("descentLph"),
                )
            }
            require(bands.isNotEmpty() && bands.zipWithNext().all { (a, b) -> a.maximumHorizontalSpeedKmh < b.maximumHorizontalSpeedKmh })
            return FuelModelConfiguration(
                initialFuelL = root.getDouble("initialFuelL"),
                takeoffBurnL = root.getDouble("takeoffBurnL"),
                takeoffSpeedKmh = takeoff.getDouble("minimumHorizontalSpeedKmh"),
                takeoffClimbSpeedMps = takeoff.getDouble("minimumClimbSpeedMps"),
                takeoffConfirmationMs = (takeoff.getDouble("confirmationSeconds") * 1_000).toLong(),
                climbAtOrAboveMps = phase.getDouble("climbAtOrAboveMps"),
                descentAtOrBelowMps = phase.getDouble("descentAtOrBelowMps"),
                speedFilterTimeConstantSeconds = phase.getDouble("filterTimeConstantSeconds").coerceAtLeast(0.1),
                phaseConfirmationMs = (phase.getDouble("confirmationSeconds") * 1_000).toLong(),
                speedBands = bands,
                unknownPhaseLph = root.getDouble("unknownPhaseLph"),
                orangeAtL = warnings.getDouble("orangeAtL"),
                redAtL = warnings.getDouble("redAtL"),
                redEnduranceMinutes = warnings.getDouble("redEnduranceMinutes"),
            ).also {
                require(it.initialFuelL > 0.0 && it.takeoffBurnL >= 0.0)
                require(it.speedBands.all { band -> band.climbLph >= 0.0 && band.levelLph >= 0.0 && band.descentLph >= 0.0 })
            }
        }
    }
}

/**
 * Best-guess model, not a fuel sensor. Horizontal speed selects a configurable rate band;
 * filtered vertical speed selects climb, level, or descent. Fuel is integrated against elapsed
 * monotonic solution time and remains nonincreasing.
 */
class FuelEstimator private constructor(private val config: FuelModelConfiguration) {
    private var estimate: FuelEstimate? = null
    private var lastTimestampMs: Long? = null
    private var lastFilterTimestampMs: Long? = null
    private var filteredSpeedKmh: Double? = null
    private var filteredVerticalSpeedMps: Double? = null
    private var takeoffCandidateSinceMs: Long? = null
    private var phase: FuelPhase = FuelPhase.UNKNOWN
    private var candidatePhase: FuelPhase? = null
    private var candidatePhaseSinceMs: Long? = null

    fun update(solution: NavigationSolution?): FuelEstimate? {
        solution ?: return estimate
        val nowMs = solution.timestampElapsedRealtimeMs
        if (estimate == null) {
            estimate = makeEstimate(config.initialFuelL, 0.0, FuelPhase.UNKNOWN, flightActive = false)
            lastTimestampMs = nowMs
        }

        val rawSpeedKmh = solution.groundSpeedMps?.toDouble()?.times(3.6)
        val rawVerticalMps = solution.verticalSpeedMps?.toDouble()
        if (rawSpeedKmh != null || rawVerticalMps != null) {
            val previousFilterTimestamp = lastFilterTimestampMs
            val filterDt = previousFilterTimestamp?.let { (nowMs - it).coerceAtLeast(0L) / 1_000.0 } ?: 0.0
            val alpha = if (previousFilterTimestamp == null) 1.0
            else 1.0 - exp(-filterDt / config.speedFilterTimeConstantSeconds)
            if (rawSpeedKmh != null) {
                filteredSpeedKmh = filteredSpeedKmh?.let { it + alpha * (rawSpeedKmh - it) } ?: rawSpeedKmh
            }
            if (rawVerticalMps != null) {
                filteredVerticalSpeedMps = filteredVerticalSpeedMps?.let { it + alpha * (rawVerticalMps - it) } ?: rawVerticalMps
            }
            lastFilterTimestampMs = nowMs
        }

        val current = estimate ?: return null
        val previousTimestamp = lastTimestampMs
        if (previousTimestamp == null || nowMs <= previousTimestamp) return current
        val elapsedMs = nowMs - previousTimestamp
        lastTimestampMs = nowMs

        if (!current.flightActive) {
            val takeoffEvidence = (filteredSpeedKmh ?: 0.0) >= config.takeoffSpeedKmh ||
                (filteredVerticalSpeedMps ?: 0.0) >= config.takeoffClimbSpeedMps
            if (!takeoffEvidence) {
                takeoffCandidateSinceMs = null
                return current
            }
            val candidateSince = takeoffCandidateSinceMs ?: nowMs.also { takeoffCandidateSinceMs = it }
            if (nowMs - candidateSince < config.takeoffConfirmationMs) return current
            val fuelAfterTakeoff = max(0.0, current.remainingFuelL - config.takeoffBurnL)
            updatePhase(nowMs)
            val (rate, selectedPhase) = rateForCurrentMotion()
            estimate = makeEstimate(fuelAfterTakeoff, rate, selectedPhase, flightActive = true)
            // The confirmation interval serves as the takeoff event; do not also integrate it.
            return estimate
        }

        updatePhase(nowMs)
        val (rate, selectedPhase) = rateForCurrentMotion()
        val remaining = max(0.0, current.remainingFuelL - rate * elapsedMs / 3_600_000.0)
        estimate = makeEstimate(remaining, rate, selectedPhase, flightActive = true)
        return estimate
    }

    private fun updatePhase(nowMs: Long) {
        val vertical = filteredVerticalSpeedMps ?: return
        val proposed = when {
            vertical >= config.climbAtOrAboveMps -> FuelPhase.CLIMB
            vertical <= config.descentAtOrBelowMps -> FuelPhase.DESCENT
            else -> FuelPhase.LEVEL
        }
        if (proposed == phase) {
            candidatePhase = null
            candidatePhaseSinceMs = null
        } else if (candidatePhase != proposed) {
            candidatePhase = proposed
            candidatePhaseSinceMs = nowMs
        } else if (nowMs - (candidatePhaseSinceMs ?: nowMs) >= config.phaseConfirmationMs) {
            phase = proposed
            candidatePhase = null
            candidatePhaseSinceMs = null
        }
    }

    private fun rateForCurrentMotion(): Pair<Double, FuelPhase> {
        val selectedPhase = if (filteredVerticalSpeedMps == null) FuelPhase.UNKNOWN else phase
        if (selectedPhase == FuelPhase.UNKNOWN) return config.unknownPhaseLph to selectedPhase
        val speed = filteredSpeedKmh ?: 0.0
        val band = config.speedBands.firstOrNull { speed <= it.maximumHorizontalSpeedKmh } ?: config.speedBands.last()
        val rate = when (selectedPhase) {
            FuelPhase.CLIMB -> band.climbLph
            FuelPhase.LEVEL -> band.levelLph
            FuelPhase.DESCENT -> band.descentLph
            FuelPhase.UNKNOWN -> config.unknownPhaseLph
        }
        return rate to selectedPhase
    }

    private fun makeEstimate(remainingL: Double, rateLph: Double, phase: FuelPhase, flightActive: Boolean): FuelEstimate {
        val minutes = if (rateLph > 0.0) remainingL / rateLph * 60.0 else null
        val status = when {
            remainingL <= config.redAtL || (minutes != null && minutes <= config.redEnduranceMinutes) -> FuelStatus.CRITICAL
            remainingL <= config.orangeAtL -> FuelStatus.LOW
            else -> FuelStatus.NORMAL
        }
        return FuelEstimate(
            remainingFuelL = remainingL,
            initialFuelL = config.initialFuelL,
            burnRateLph = rateLph,
            estimatedMinutesRemaining = minutes,
            status = status,
            phase = phase,
            flightActive = flightActive,
        )
    }

    companion object {
        fun load(context: Context) = FuelEstimator(FuelModelConfiguration.load(context.applicationContext))
    }
}
