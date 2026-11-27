package com.swirlock.flightcomputer

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Three-vector in SI units. The INS navigation frame is local ENU: east, north, up. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    companion object {
        fun from(values: FloatArray) = Vec3(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
    }
}

/** Unit quaternion rotating body-frame vectors into the local navigation frame. */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(other: Quaternion) = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun normalized(): Quaternion {
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        if (magnitude <= 1e-12) return IDENTITY
        return Quaternion(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
    }

    fun rotate(vector: Vec3): Vec3 {
        val qVector = Vec3(x, y, z)
        val twiceCross = qVector.cross(vector) * 2.0
        return vector + twiceCross * w + qVector.cross(twiceCross)
    }

    fun integrateBodyRate(angularRateRadPerSec: Vec3, dtSeconds: Double): Quaternion {
        val angle = angularRateRadPerSec.norm() * dtSeconds
        if (angle <= 1e-12) return this
        val halfAngle = angle * 0.5
        val scale = sin(halfAngle) / angularRateRadPerSec.norm()
        val delta = Quaternion(
            cos(halfAngle),
            angularRateRadPerSec.x * scale,
            angularRateRadPerSec.y * scale,
            angularRateRadPerSec.z * scale,
        )
        return (this * delta).normalized()
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /** Convert Android's row-major body-to-world 3x3 rotation matrix. */
        fun fromRotationMatrix(m: FloatArray): Quaternion {
            require(m.size >= 9)
            val m00 = m[0].toDouble(); val m01 = m[1].toDouble(); val m02 = m[2].toDouble()
            val m10 = m[3].toDouble(); val m11 = m[4].toDouble(); val m12 = m[5].toDouble()
            val m20 = m[6].toDouble(); val m21 = m[7].toDouble(); val m22 = m[8].toDouble()
            val trace = m00 + m11 + m22
            return when {
                trace > 0.0 -> {
                    val s = sqrt(trace + 1.0) * 2.0
                    Quaternion(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1.0 + m00 - m11 - m22) * 2.0
                    Quaternion((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1.0 + m11 - m00 - m22) * 2.0
                    Quaternion((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s)
                }
                else -> {
                    val s = sqrt(1.0 + m22 - m00 - m11) * 2.0
                    Quaternion((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s)
                }
            }.normalized()
        }
    }
}

data class InertialState(
    val attitudeBodyToEnu: Quaternion,
    val positionEnuM: Vec3,
    val velocityEnuMps: Vec3,
    val accelBiasBodyMps2: Vec3,
    val gyroBiasBodyRadPerSec: Vec3,
    val initializedAtNs: Long,
    val timestampNs: Long,
    val droppedAccelIntervals: Long,
)

/**
 * Short-range local strapdown INS. It uses raw accelerometer specific force and body-frame
 * gyro rate, initialized from a stationary alignment. Earth rotation/transport terms are
 * intentionally omitted for this first local ENU implementation.
 */
class StrapdownIns(
    private val gravityMps2: Double = 9.80665,
    private val maxAccelIntervalSeconds: Double = 0.5,
) {
    private var state: InertialState? = null
    private var previousGyroTimestampNs: Long? = null
    private var previousAccelTimestampNs: Long? = null
    private var previousAccelerationEnu: Vec3? = null

    fun currentState(): InertialState? = state

    /** Preserve the navigation state across Activity pauses without integrating an unknown gap. */
    fun resetSampleHistory() {
        previousGyroTimestampNs = null
        previousAccelTimestampNs = null
        previousAccelerationEnu = null
    }

    fun initialize(
        attitudeBodyToEnu: Quaternion,
        accelBiasBodyMps2: Vec3,
        gyroBiasBodyRadPerSec: Vec3,
        timestampNs: Long,
    ) {
        state = InertialState(
            attitudeBodyToEnu = attitudeBodyToEnu.normalized(),
            positionEnuM = Vec3(0.0, 0.0, 0.0),
            velocityEnuMps = Vec3(0.0, 0.0, 0.0),
            accelBiasBodyMps2 = accelBiasBodyMps2,
            gyroBiasBodyRadPerSec = gyroBiasBodyRadPerSec,
            initializedAtNs = timestampNs,
            timestampNs = timestampNs,
            droppedAccelIntervals = 0L,
        )
        previousGyroTimestampNs = timestampNs
        previousAccelTimestampNs = timestampNs
        previousAccelerationEnu = Vec3(0.0, 0.0, 0.0)
    }

    fun updateGyroscope(timestampNs: Long, angularRateBodyRadPerSec: Vec3) {
        val current = state ?: return
        val previousTimestamp = previousGyroTimestampNs
        if (previousTimestamp != null && timestampNs <= previousTimestamp) return
        previousGyroTimestampNs = timestampNs
        if (previousTimestamp == null) return
        val dt = (timestampNs - previousTimestamp) * NANOS_TO_SECONDS
        val correctedRate = angularRateBodyRadPerSec - current.gyroBiasBodyRadPerSec
        // Subdivide long callback intervals while holding the measured angular rate constant.
        val steps = ceil(dt / MAX_GYRO_STEP_SECONDS).toInt().coerceAtLeast(1)
        val stepSeconds = dt / steps
        var attitude = current.attitudeBodyToEnu
        repeat(steps) { attitude = attitude.integrateBodyRate(correctedRate, stepSeconds) }
        state = current.copy(attitudeBodyToEnu = attitude, timestampNs = maxOf(current.timestampNs, timestampNs))
    }

    fun updateAccelerometer(timestampNs: Long, specificForceBodyMps2: Vec3) {
        val current = state ?: return
        val previousTimestamp = previousAccelTimestampNs
        val correctedSpecificForce = specificForceBodyMps2 - current.accelBiasBodyMps2
        val forceEnu = current.attitudeBodyToEnu.rotate(correctedSpecificForce)
        // Android's stationary accelerometer points +Up; ENU gravity points -Up.
        val accelerationEnu = forceEnu - Vec3(0.0, 0.0, gravityMps2)
        val previousAcceleration = previousAccelerationEnu
        if (previousTimestamp != null && timestampNs <= previousTimestamp) return
        previousAccelTimestampNs = timestampNs
        previousAccelerationEnu = accelerationEnu
        if (previousTimestamp == null || previousAcceleration == null) return

        val dt = (timestampNs - previousTimestamp) * NANOS_TO_SECONDS
        if (dt > maxAccelIntervalSeconds) {
            state = current.copy(
                timestampNs = maxOf(current.timestampNs, timestampNs),
                droppedAccelIntervals = current.droppedAccelIntervals + 1,
            )
            return
        }

        val averageAcceleration = (previousAcceleration + accelerationEnu) * 0.5
        val nextVelocity = current.velocityEnuMps + averageAcceleration * dt
        val nextPosition = current.positionEnuM + (current.velocityEnuMps + nextVelocity) * (0.5 * dt)
        state = current.copy(
            positionEnuM = nextPosition,
            velocityEnuMps = nextVelocity,
            timestampNs = maxOf(current.timestampNs, timestampNs),
        )
    }

    private companion object {
        const val NANOS_TO_SECONDS = 1e-9
        const val MAX_GYRO_STEP_SECONDS = 0.02
    }
}
