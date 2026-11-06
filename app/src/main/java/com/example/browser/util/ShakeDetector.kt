package com.example.browser.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * "Shake to report a bug".
 *
 * A shake is a burst, not one big reading: a single hard jolt happens when a
 * phone is put down, but three direction changes inside a second only happen
 * when somebody is shaking it. Motion is measured as the acceleration minus the
 * gravity estimate, so slow tilting never triggers it, and long spikes are
 * debounced so one shake produces one report.
 *
 * The listener is registered only while the Activity is resumed.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var peaks = 0
    private var firstPeakAt = 0L
    private var lastShakeAt = 0L

    /** @return false when the device has no accelerometer (emulators, TVs). */
    fun start(context: Context): Boolean {
        if (sensorManager != null) return true
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sensorManager = manager
        accelerometer = sensor
        peaks = 0
        return manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        accelerometer = null
        peaks = 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Low-pass: the gravity component of the reading, in m/s².
        gravityX = gravityX * 0.8f + x * 0.2f
        gravityY = gravityY * 0.8f + y * 0.2f
        gravityZ = gravityZ * 0.8f + z * 0.2f

        val ax = x - gravityX
        val ay = y - gravityY
        val az = z - gravityZ
        val force = kotlin.math.sqrt(ax * ax + ay * ay + az * az)

        if (force < SHAKE_THRESHOLD) return

        val now = System.currentTimeMillis()
        if (now - lastShakeAt < DEBOUNCE_MS) return

        if (peaks == 0 || now - firstPeakAt > PEAK_WINDOW_MS) {
            peaks = 1
            firstPeakAt = now
            return
        }

        peaks++
        if (peaks >= REQUIRED_PEAKS) {
            peaks = 0
            lastShakeAt = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** m/s² above gravity; a deliberate shake exceeds this easily. */
        const val SHAKE_THRESHOLD = 9.0f
        const val REQUIRED_PEAKS = 3
        const val PEAK_WINDOW_MS = 1200L
        const val DEBOUNCE_MS = 2500L
    }
}
