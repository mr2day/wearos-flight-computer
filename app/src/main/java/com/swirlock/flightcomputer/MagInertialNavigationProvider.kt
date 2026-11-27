package com.swirlock.flightcomputer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GPS-independent input and estimator component. A stationary ground interval initializes a
 * local magnetic ENU frame and sensor biases; raw gyro and accelerometer samples then propagate
 * attitude, velocity, and position through [StrapdownIns].
 */
class MagInertialNavigationProvider(
    context: Context,
) : NavigationProvider, SensorEventListener {
    override val source = NavigationSource.MAG_INERTIAL

    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(SensorManager::class.java)
    private val ins = StrapdownIns()
    private val lastSensorLogMs = mutableMapOf<Int, Long>()
    private var sensorsStarted = false
    private var onSolutionChanged: ((NavigationSolution?) -> Unit)? = null
    private var latestAccelerometer: TimedVector? = null
    private var latestGyroscope: TimedVector? = null
    private var latestMagneticField: TimedVector? = null
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var filteredMagneticHeading: Double? = null
    private var lastMagneticHeadingUpdateNs: Long? = null
    private var stationarySinceNs: Long? = null
    private var lastAlignmentAccelNs = Long.MIN_VALUE
    private var lastAlignmentGyroNs = Long.MIN_VALUE
    private var lastAlignmentMagNs = Long.MIN_VALUE
    private val alignmentAccelerometer = mutableListOf<Vec3>()
    private val alignmentGyroscope = mutableListOf<Vec3>()
    private val alignmentMagneticField = mutableListOf<Vec3>()
    private var lastPublishTimestampNs = 0L
    private var lastStateLogNs = 0L

    override fun start(onSolutionChanged: (NavigationSolution?) -> Unit) {
        this.onSolutionChanged = onSolutionChanged
        if (sensorsStarted) return
        sensorsStarted = true
        val requiredTypes = intArrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
        )
        for (type in requiredTypes) {
            val sensor = sensors.getDefaultSensor(type)
            if (sensor == null) {
                Log.w(TAG, "Sensor unavailable: ${sensorTypeName(type)}")
                continue
            }
            Log.i(TAG, "Sensor available: ${sensor.name}; vendor=${sensor.vendor}; range=${sensor.maximumRange}")
            val samplePeriodUs = when (type) {
                Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE -> IMU_SAMPLE_PERIOD_US
                Sensor.TYPE_MAGNETIC_FIELD -> MAGNETOMETER_SAMPLE_PERIOD_US
                else -> DIAGNOSTIC_SAMPLE_PERIOD_US
            }
            val registered = sensors.registerListener(this, sensor, samplePeriodUs)
            Log.i(TAG, "Sensor registration ${sensorTypeName(type)}=${if (registered) "ok" else "failed"}")
        }
        Log.i(TAG, "GPS is not part of this provider; waiting for stationary local-frame alignment")
    }

    override fun currentSolution(): NavigationSolution? {
        val state = ins.currentState() ?: return null
        val position = state.positionEnuM
        val velocity = state.velocityEnuMps
        val horizontalSpeed = hypot(velocity.x, velocity.y)
        val inertialHeading = screenTopHeading(state.attitudeBodyToEnu)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val magneticHeadingFresh = lastMagneticHeadingUpdateNs?.let {
            nowNs - it <= MAGNETIC_HEADING_STALE_NS
        } == true
        val heading = if (magneticHeadingFresh) filteredMagneticHeading else inertialHeading
        val homeDistance = hypot(position.x, position.y)
        val homeBearing = if (homeDistance > MIN_HOME_DISTANCE_M) {
            normalizeDegrees(Math.toDegrees(atan2(-position.x, -position.y)))
        } else {
            null
        }
        val groundTrack = if (horizontalSpeed >= MIN_TRACK_SPEED_MPS) {
            normalizeDegrees(Math.toDegrees(atan2(velocity.x, velocity.y))).toFloat()
        } else {
            null
        }
        return NavigationSolution(
            source = NavigationSource.MAG_INERTIAL,
            directionDegrees = heading?.toFloat(),
            magneticHeadingValid = magneticHeadingFresh,
            groundTrackDegrees = groundTrack,
            groundSpeedMps = horizontalSpeed.toFloat(),
            verticalSpeedMps = velocity.z.toFloat(),
            localEastM = position.x,
            localNorthM = position.y,
            localUpM = position.z,
            homeBearingDegrees = homeBearing?.toFloat(),
            homeDistanceM = homeDistance.toFloat(),
            confidence = null, // No validated watch-specific error model exists yet.
            insElapsedSeconds = ((SystemClock.elapsedRealtimeNanos() - state.initializedAtNs) / NANOS_PER_SECOND)
                .coerceAtLeast(0L),
            timestampElapsedRealtimeMs = state.timestampNs / NANOS_PER_MILLISECOND,
        )
    }

    override fun stop() {
        if (sensorsStarted) {
            sensors.unregisterListener(this)
            sensorsStarted = false
        }
        // Preserve position, but never integrate across an Activity/sensor gap as if it were known.
        ins.resetSampleHistory()
        onSolutionChanged = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccelerometer = TimedVector(event.timestamp, Vec3.from(event.values))
                if (ins.currentState() == null) updateStationaryAlignment(event.timestamp)
                else ins.updateAccelerometer(event.timestamp, Vec3.from(event.values))
                updateMagneticCompassHeading(event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroscope = TimedVector(event.timestamp, Vec3.from(event.values))
                if (ins.currentState() == null) updateStationaryAlignment(event.timestamp)
                else ins.updateGyroscope(event.timestamp, Vec3.from(event.values))
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                latestMagneticField = TimedVector(event.timestamp, Vec3.from(event.values))
                if (ins.currentState() == null) updateStationaryAlignment(event.timestamp)
                else updateMagneticCompassHeading(event.timestamp)
            }
        }

        logSensorIfDue(event)
        publishIfDue(event.timestamp)
        logInertialStateIfDue(event.timestamp)
    }

    private fun updateStationaryAlignment(nowNs: Long) {
        val accel = latestAccelerometer
        val gyro = latestGyroscope
        if (accel == null || gyro == null ||
            nowNs - accel.timestampNs > SENSOR_FRESHNESS_NS || nowNs - gyro.timestampNs > SENSOR_FRESHNESS_NS
        ) {
            resetAlignmentWindow()
            return
        }

        val field = latestMagneticField?.takeIf { nowNs - it.timestampNs <= SENSOR_FRESHNESS_NS }
        val fieldMagnitude = field?.value?.norm()
        val magneticUsable = fieldMagnitude != null && fieldMagnitude in MIN_PLAUSIBLE_FIELD_UT..MAX_PLAUSIBLE_FIELD_UT &&
            magneticAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        val accelMagnitude = accel.value.norm()
        val stationary = accelMagnitude in MIN_STATIONARY_ACCEL..MAX_STATIONARY_ACCEL &&
            gyro.value.norm() <= MAX_STATIONARY_GYRO_RAD_PER_SEC
        if (!stationary) {
            resetAlignmentWindow()
            return
        }

        if (stationarySinceNs == null) {
            stationarySinceNs = nowNs
            alignmentAccelerometer.clear()
            alignmentGyroscope.clear()
            alignmentMagneticField.clear()
            lastAlignmentAccelNs = Long.MIN_VALUE
            lastAlignmentGyroNs = Long.MIN_VALUE
            lastAlignmentMagNs = Long.MIN_VALUE
        }
        if (accel.timestampNs > lastAlignmentAccelNs) {
            alignmentAccelerometer += accel.value
            lastAlignmentAccelNs = accel.timestampNs
        }
        if (gyro.timestampNs > lastAlignmentGyroNs) {
            alignmentGyroscope += gyro.value
            lastAlignmentGyroNs = gyro.timestampNs
        }
        if (magneticUsable && field.timestampNs > lastAlignmentMagNs) {
            alignmentMagneticField += field.value
            lastAlignmentMagNs = field.timestampNs
        }
        if (nowNs - (stationarySinceNs ?: nowNs) < ALIGNMENT_DURATION_NS) return
        if (alignmentAccelerometer.size < MIN_ALIGNMENT_SAMPLES || alignmentGyroscope.size < MIN_ALIGNMENT_SAMPLES) return

        val meanAccel = alignmentAccelerometer.mean()
        val meanGyro = alignmentGyroscope.mean()
        val meanMag = alignmentMagneticField.takeIf { it.size >= MIN_ALIGNMENT_SAMPLES }?.mean()
        val rotation = FloatArray(9)
        val magneticAligned = meanMag != null && SensorManager.getRotationMatrix(
            rotation,
            null,
            meanAccel.toFloatArray(),
            meanMag.toFloatArray(),
        )
        if (!magneticAligned) {
            // A gravity + screen-top frame still gives a valid local trajectory with arbitrary yaw.
            makeArbitraryYawAlignment(meanAccel, rotation)
        }

        val attitude = Quaternion.fromRotationMatrix(rotation)
        val expectedStationarySpecificForceBody = Vec3(
            rotation[6].toDouble() * GRAVITY_MPS2,
            rotation[7].toDouble() * GRAVITY_MPS2,
            rotation[8].toDouble() * GRAVITY_MPS2,
        )
        val accelBias = meanAccel - expectedStationarySpecificForceBody
        val initializationTimeNs = minOf(lastAlignmentAccelNs, lastAlignmentGyroNs)
        ins.initialize(attitude, accelBias, meanGyro, initializationTimeNs)
        stationarySinceNs = null
        Log.i(
            TAG,
            "INS initialized at local HOME origin; alignment=${if (magneticAligned) "magnetic" else "arbitrary-yaw"} " +
                "accelBias=${accelBias.toShortString()}m/s2 gyroBias=${meanGyro.toShortString()}rad/s",
        )
        publishIfDue(nowNs)
    }

    private fun makeArbitraryYawAlignment(meanAccel: Vec3, rotation: FloatArray) {
        val upBody = meanAccel * (1.0 / meanAccel.norm())
        val screenTop = Vec3(0.0, 1.0, 0.0)
        var northBody = screenTop - upBody * screenTop.dot(upBody)
        if (northBody.norm() < MIN_ALIGNMENT_AXIS_NORM) {
            val screenRight = Vec3(1.0, 0.0, 0.0)
            northBody = screenRight - upBody * screenRight.dot(upBody)
        }
        northBody = northBody * (1.0 / northBody.norm())
        val eastBody = northBody.cross(upBody)
        val rows = arrayOf(eastBody, northBody, upBody)
        for (row in 0..2) {
            rotation[row * 3] = rows[row].x.toFloat()
            rotation[row * 3 + 1] = rows[row].y.toFloat()
            rotation[row * 3 + 2] = rows[row].z.toFloat()
        }
    }

    private fun updateMagneticCompassHeading(nowNs: Long) {
        val accel = latestAccelerometer ?: return
        val magnetic = latestMagneticField ?: return
        if (nowNs - accel.timestampNs > SENSOR_FRESHNESS_NS || nowNs - magnetic.timestampNs > SENSOR_FRESHNESS_NS) return
        if (magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val magnitude = magnetic.value.norm()
        if (magnitude !in MIN_PLAUSIBLE_FIELD_UT..MAX_PLAUSIBLE_FIELD_UT) return
        val rotation = FloatArray(9)
        if (!SensorManager.getRotationMatrix(
                rotation,
                null,
                accel.value.toFloatArray(),
                magnetic.value.toFloatArray(),
            )
        ) return
        val rawHeading = headingFromScreenTop(rotation) ?: return
        val lowConfidence = magneticAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        filteredMagneticHeading = smoothCircularHeading(filteredMagneticHeading, rawHeading, lowConfidence)
        lastMagneticHeadingUpdateNs = nowNs
    }

    private fun publishIfDue(timestampNs: Long) {
        if (timestampNs - lastPublishTimestampNs < MIN_PUBLISH_INTERVAL_NS) return
        lastPublishTimestampNs = timestampNs
        onSolutionChanged?.invoke(currentSolution())
    }

    private fun logInertialStateIfDue(timestampNs: Long) {
        if (timestampNs - lastStateLogNs < STATE_LOG_INTERVAL_NS) return
        lastStateLogNs = timestampNs
        val state = ins.currentState() ?: return
        Log.i(
            TAG,
            "INS local E=${"%.1f".format(state.positionEnuM.x)}m " +
                "N=${"%.1f".format(state.positionEnuM.y)}m " +
                "vE=${"%.2f".format(state.velocityEnuMps.x)}m/s " +
                "vN=${"%.2f".format(state.velocityEnuMps.y)}m/s " +
                "magHeading=${filteredMagneticHeading?.roundToInt() ?: -1}deg " +
                "droppedIntervals=${state.droppedAccelIntervals}",
        )
    }

    private fun logSensorIfDue(event: SensorEvent) {
        val nowMs = SystemClock.elapsedRealtime()
        val previous = lastSensorLogMs[event.sensor.type] ?: 0L
        if (nowMs - previous < SENSOR_LOG_INTERVAL_MS) return
        lastSensorLogMs[event.sensor.type] = nowMs
        val values = when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> "${"%.2f".format(event.values[0])}hPa"
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD -> event.values.take(3).joinToString(prefix = "[", postfix = "]") {
                "%.3f".format(it)
            }
            else -> return
        }
        Log.i(TAG, "${sensorTypeName(event.sensor.type)}=$values")
    }

    private fun resetAlignmentWindow() {
        stationarySinceNs = null
        alignmentAccelerometer.clear()
        alignmentGyroscope.clear()
        alignmentMagneticField.clear()
        lastAlignmentAccelNs = Long.MIN_VALUE
        lastAlignmentGyroNs = Long.MIN_VALUE
        lastAlignmentMagNs = Long.MIN_VALUE
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
    }

    private fun headingFromScreenTop(rotation: FloatArray): Double? {
        if (rotation.size < 9) return null
        val east = rotation[1]
        val north = rotation[4]
        val horizontalProjection = sqrt(east * east + north * north)
        if (horizontalProjection < MIN_SCREEN_TOP_HORIZONTAL_PROJECTION) return null
        return normalizeDegrees(Math.toDegrees(atan2(east.toDouble(), north.toDouble())))
    }

    private fun screenTopHeading(attitude: Quaternion): Double? {
        val screenTopEnu = attitude.rotate(Vec3(0.0, 1.0, 0.0))
        val horizontalProjection = hypot(screenTopEnu.x, screenTopEnu.y)
        if (horizontalProjection < MIN_SCREEN_TOP_HORIZONTAL_PROJECTION) return null
        return normalizeDegrees(Math.toDegrees(atan2(screenTopEnu.x, screenTopEnu.y)))
    }

    private fun smoothCircularHeading(previous: Double?, raw: Double, lowConfidence: Boolean): Double {
        if (previous == null) return raw
        val delta = normalizeSignedDegrees(raw - previous)
        val absoluteDelta = kotlin.math.abs(delta)
        val deadband = if (lowConfidence) LOW_CONFIDENCE_DEADBAND_DEGREES else MIN_HEADING_CHANGE_DEGREES
        if (absoluteDelta < deadband) return previous
        val alpha = when {
            absoluteDelta > FAST_TURN_THRESHOLD_DEGREES -> FAST_TURN_FILTER_ALPHA
            absoluteDelta > MEDIUM_TURN_THRESHOLD_DEGREES -> MEDIUM_TURN_FILTER_ALPHA
            lowConfidence -> LOW_CONFIDENCE_FILTER_ALPHA
            else -> STABLE_HEADING_FILTER_ALPHA
        }
        return normalizeDegrees(previous + delta * alpha)
    }

    private fun List<Vec3>.mean(): Vec3 {
        if (isEmpty()) return Vec3(0.0, 0.0, 0.0)
        return fold(Vec3(0.0, 0.0, 0.0), Vec3::plus) * (1.0 / size)
    }

    private fun Vec3.toFloatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
    private fun Vec3.dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    private fun Vec3.toShortString() = "[${"%.4f".format(x)},${"%.4f".format(y)},${"%.4f".format(z)}]"

    private fun normalizeDegrees(value: Double) = (value % 360.0 + 360.0) % 360.0
    private fun normalizeSignedDegrees(value: Double) = (normalizeDegrees(value + 180.0) - 180.0)

    private fun sensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_ACCELEROMETER -> "acceleration"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetic field"
        else -> "sensor-$type"
    }

    private data class TimedVector(val timestampNs: Long, val value: Vec3)

    private companion object {
        const val TAG = "SwirlockINS"
        const val IMU_SAMPLE_PERIOD_US = 10_000
        const val MAGNETOMETER_SAMPLE_PERIOD_US = 20_000
        const val DIAGNOSTIC_SAMPLE_PERIOD_US = 200_000
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SENSOR_LOG_INTERVAL_MS = 1_000L
        const val STATE_LOG_INTERVAL_NS = 1_000_000_000L
        const val MIN_PUBLISH_INTERVAL_NS = 50_000_000L
        const val SENSOR_FRESHNESS_NS = 150_000_000L
        const val MAGNETIC_HEADING_STALE_NS = 2_000_000_000L
        const val ALIGNMENT_DURATION_NS = 2_000_000_000L
        const val MIN_ALIGNMENT_SAMPLES = 20
        const val MIN_STATIONARY_ACCEL = 9.3
        const val MAX_STATIONARY_ACCEL = 10.3
        const val MAX_STATIONARY_GYRO_RAD_PER_SEC = 0.12
        const val MIN_PLAUSIBLE_FIELD_UT = 15.0
        const val MAX_PLAUSIBLE_FIELD_UT = 100.0
        const val MIN_ALIGNMENT_AXIS_NORM = 0.1
        const val MIN_SCREEN_TOP_HORIZONTAL_PROJECTION = 0.15
        const val MIN_HEADING_CHANGE_DEGREES = 0.7
        const val LOW_CONFIDENCE_DEADBAND_DEGREES = 2.5
        const val MEDIUM_TURN_THRESHOLD_DEGREES = 8.0
        const val FAST_TURN_THRESHOLD_DEGREES = 25.0
        const val STABLE_HEADING_FILTER_ALPHA = 0.12
        const val MEDIUM_TURN_FILTER_ALPHA = 0.3
        const val FAST_TURN_FILTER_ALPHA = 0.65
        const val LOW_CONFIDENCE_FILTER_ALPHA = 0.04
        const val GRAVITY_MPS2 = 9.80665
        const val MIN_HOME_DISTANCE_M = 0.5
        const val MIN_TRACK_SPEED_MPS = 1.0
    }
}
