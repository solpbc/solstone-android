// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.metadata

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.solstone.core.metadata.ImuHandle
import app.solstone.core.metadata.ImuListener
import app.solstone.core.metadata.ImuSensorPort
import app.solstone.core.metadata.LinearAccelerationSample
import app.solstone.core.metadata.RotationVectorSample
import java.util.concurrent.atomic.AtomicBoolean

class AndroidImuSensorPort(
    private val sensorManager: SensorManager,
    private val clock: () -> Long,
) : ImuSensorPort {
    override fun start(listener: ImuListener): ImuHandle {
        val listeners = mutableListOf<SensorEventListener>()
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensor ->
            val eventListener = rotationListener(listener)
            if (sensorManager.registerListener(eventListener, sensor, SensorManager.SENSOR_DELAY_GAME)) {
                listeners += eventListener
            }
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            val eventListener = linearAccelerationListener(listener)
            if (sensorManager.registerListener(eventListener, sensor, SensorManager.SENSOR_DELAY_GAME)) {
                listeners += eventListener
            }
        }
        return object : ImuHandle {
            private val stopped = AtomicBoolean(false)

            override fun stop() {
                if (!stopped.compareAndSet(false, true)) return
                listeners.forEach(sensorManager::unregisterListener)
            }
        }
    }

    private fun rotationListener(listener: ImuListener): SensorEventListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = event.values
                if (values.size < 3) return
                listener.onRotationVector(
                    RotationVectorSample(
                        x = values[0],
                        y = values[1],
                        z = values[2],
                        w = if (values.size >= 4) values[3] else null,
                        timestampEpochMs = clock(),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

    private fun linearAccelerationListener(listener: ImuListener): SensorEventListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = event.values
                if (values.size < 3) return
                listener.onLinearAcceleration(
                    LinearAccelerationSample(
                        x = values[0],
                        y = values[1],
                        z = values[2],
                        timestampEpochMs = clock(),
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
}
