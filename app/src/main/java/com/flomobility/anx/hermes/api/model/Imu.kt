package com.flomobility.anx.hermes.api.model

import com.flomobility.anx.hermes.other.GsonUtils

data class Imu(
    val linearAcceleration: LinearAcceleration,
    val angularVelocity: AngularVelocity,
    val quaternion: Quaternion
): BaseData {

    companion object {
        fun new(linearAcceleration: LinearAcceleration?,
                angularVelocity: AngularVelocity?,
                quaternion: Quaternion?): Imu {
            return Imu(
                linearAcceleration ?: LinearAcceleration(0.0, 0.0, 0.0),
                angularVelocity ?: AngularVelocity(0.0, 0.0, 0.0),
                quaternion ?: Quaternion(0.0, 0.0, 0.0)
            )
        }
    }

    override fun toJson(): String {
        val imuMap = hashMapOf(
            "a" to listOf(linearAcceleration.x, linearAcceleration.y, linearAcceleration.z),
            "w" to listOf(angularVelocity.x, angularVelocity.y, angularVelocity.z),
            "mu" to listOf(quaternion.x, quaternion.y, quaternion.z)

        )
        return GsonUtils.getGson().toJson(imuMap)
    }
}

data class LinearAcceleration(
    val x: Double,
    val y: Double,
    val z: Double
)

data class AngularVelocity(
    val x: Double,
    val y: Double,
    val z: Double
)
