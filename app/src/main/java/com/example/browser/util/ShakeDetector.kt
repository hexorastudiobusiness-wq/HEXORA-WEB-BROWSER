package com.example.browser.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * "Shake to report a bug".
 *
 * The first version counted *samples* above a threshold and called three of
 * them a shake. On a 50 Hz sensor one bump produces five to ten such samples,
 * so putting the phone down, picking it up, or knocking the table opened the
 * report dialog. The debounce only stopped a second report; the false first
 * one went through every time.
 *
 * What actually separates a shake from a bump is **direction**: a shake
 * reverses the acceleration vector several times inside a fraction of a
 * second, while a phone being set down moves in one direction and stops. So
 * this detector counts reversals, not magnitude:
 *
 *  - a peak counts only after the reading falls back below [RELEASE] (a real
 *    edge, not a run of consecutive samples);
 *  - each peak is compared with the previous one and counts only when the two
 *    point in opposite directions;
 *  - the peaks have to happen inside [REVERSAL_WINDOW_MS], which a person can
 *    only do by shaking on purpose;
 *  - after a report, everything is quiet for [DEBOUNCE_MS].
 *
 * `TYPE_LINEAR_ACCELERATION` is preferred where it exists (the platform has
 * already removed gravity); otherwise gravity is estimated with a low-pass
 * filter, the same way the old code did.
 *
 * The listener is registered only while the Activity is resumed.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var gravitySubtracted = false

    // Gravity estimate, used only for the accelerometer path.
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    /** True when the reading has come back down, i.e. a new peak may count. */
    private var armedForPeak = true

    /** Last counted peak, as a direction. */
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLast = false

    private var reversals = 0
    private var windowStart = 0L
    private var lastPeakAt = 0L
    private var lastShakeAt = 0L

    /** @return false when the device has no motion sensor (emulators, TVs). */
    fun start(context: Context): Boolean {
        if (sensorManager != null) return true
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        if (manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) {
            gravitySubtracted = true
            sensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        } else {
            gravitySubtracted = false
            sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        val chosen = sensor ?: return false
        reset()
        val registered = manager.registerListener(this, chosen, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            sensor = null
            return false
        }
        sensorManager = manager
        return true
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        sensor = null
        reset()
    }

    private fun reset() {
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        armedForPeak = true
        hasLast = false
        reversals = 0
        windowStart = 0L
        lastPeakAt = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ax: Float
        val ay: Float
        val az: Float
        if (gravitySubtracted) {
            ax = event.values[0]
            ay = event.values[1]
            az = event.values[2]
        } else {
            // Low-pass estimate of gravity, then subtract it.
            gravityX = gravityX * 0.8f + event.values[0] * 0.2f
            gravityY = gravityY * 0.8f + event.values[1] * 0.2f
            gravityZ = gravityZ * 0.8f + event.values[2] * 0.2f
            ax = event.values[0] - gravityX
            ay = event.values[1] - gravityY
            az = event.values[2] - gravityZ
        }
        val force = sqrt(ax * ax + ay * ay + az * az)
        val now = SystemClock.elapsedRealtime()

        if (force < RELEASE) {
            // Back to rest: the next peak may count, and a gap long enough to
            // be a different movement clears the sequence.
            armedForPeak = true
            if (hasLast && now - lastPeakAt > REVERSAL_WINDOW_MS) {
                hasLast = false
                reversals = 0
            }
            return
        }
        if (force < THRESHOLD) return
        if (!armedForPeak) return
        if (now - lastShakeAt < DEBOUNCE_MS) return

        armedForPeak = false
        lastPeakAt = now

        if (!hasLast || now - windowStart > REVERSAL_WINDOW_MS) {
            windowStart = now
            reversals = 1
            lastX = ax
            lastY = ay
            lastZ = az
            hasLast = true
            return
        }

        val dot = ax * lastX + ay * lastY + az * lastZ
        lastX = ax
        lastY = ay
        lastZ = az
        // Only a change of direction counts. Same-direction peaks are the
        // rings of one jolt, which is exactly what a table bump looks like.
        if (dot < 0f) {
            reversals++
            if (reversals >= REQUIRED_REVERSALS) {
                lastShakeAt = now
                hasLast = false
                AppLog.event("shake detected (reversals=$reversals)")
                reversals = 0
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Peak magnitude, in m/s², above gravity. A deliberate shake clears this. */
    private companion object {
        const val THRESHOLD = 12.5f

        /** Below this the sensor is considered at rest again. */
        const val RELEASE = 4.5f

        /** Direction changes needed; a shake is back-and-forth, not one jolt. */
        const val REQUIRED_REVERSALS = 3

        /** All peaks must land inside this window to count as one movement. */
        const val REVERSAL_WINDOW_MS = 800L

        /** One shake, one report. */
        const val DEBOUNCE_MS = 3000L
    }
}
