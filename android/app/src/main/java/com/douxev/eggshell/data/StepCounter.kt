package com.douxev.eggshell.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's own hardware step counter, read once on demand.
 *
 * `TYPE_STEP_COUNTER`, not `TYPE_STEP_DETECTOR`. The counter is maintained by a
 * low-power co-processor and read on demand; the detector fires an event per
 * step and would wake the CPU thousands of times a day to compute a number the
 * hardware is already keeping. There is no foreground service and no permanent
 * notification: [StepSync] reads this from a periodic worker, an alarm just
 * before midnight and the shutdown broadcast, which is enough to place every
 * step on the right day and costs nothing between reads.
 *
 * This class only talks to the sensor. What a reading *means* — the reboot
 * rule, which day it belongs to — is [StepMath], pure and tested; where it is
 * kept until the vault opens is [StepLedger].
 */
@Singleton
class StepCounter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * One reading: the raw cumulative value, the boot it belongs to, and when
     * that boot happened (wall clock).
     */
    data class Reading(val raw: Long, val boot: Int?, val bootMs: Long)

    private val sensorManager: SensorManager?
        get() = context.getSystemService(SensorManager::class.java)

    /**
     * Whether this device has a step counter at all.
     *
     * Asked of the package manager, not the sensor list: from Android 10 the
     * sensor list hides the counter from an app that lacks ACTIVITY_RECOGNITION,
     * so a revoked permission used to read as "this phone cannot count steps" —
     * the wrong thing to tell someone whose fix is one tap away.
     */
    val isAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) ||
            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** ACTIVITY_RECOGNITION is a runtime permission from API 29; below, none is needed. */
    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Read the counter once, or null when it is unavailable, not permitted, or
     * does not answer within [timeoutMs].
     *
     * Null is not zero, and callers must not treat it as one: "the sensor said
     * nothing" and "you did not move" are different facts, and writing the
     * second when the first is true is how a day gets flattened.
     */
    suspend fun read(timeoutMs: Long = READ_TIMEOUT_MS): Reading? {
        if (!hasPermission) return null
        val manager = sensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        // An on-change sensor reports its current value when it is activated,
        // so this normally answers at once. Bounded anyway, so a sensor hub
        // that never answers cannot hang a worker or the shutdown broadcast.
        val raw = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Long?> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull()?.toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                val registered = runCatching {
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                }.getOrDefault(false)
                if (!registered && cont.isActive) cont.resume(null)
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        } ?: return null
        return Reading(
            raw = raw,
            boot = bootCount(),
            bootMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Follow the counter live, for as long as a screen shows it.
     *
     * Not a service and not a background listener: the Sport screen opens this
     * when it becomes visible and closes it when it is not, so the number moves
     * as the person walks with the screen in front of them and nothing at all
     * runs once it is gone. Nothing is written from here — the readings that
     * count are still [StepSync]'s; this only feeds the display.
     *
     * @return a handle to close, or null when the sensor is unavailable.
     */
    fun listen(onRaw: (Long) -> Unit): AutoCloseable? {
        if (!hasPermission) return null
        val manager = sensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.toLong()?.let(onRaw)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }.getOrDefault(false)
        if (!registered) return null
        return AutoCloseable { manager.unregisterListener(listener) }
    }

    /**
     * How many times this device has booted, or null where the platform does
     * not keep the count. Stored next to each reading, it is what tells a
     * reboot apart from a counter that merely grew — see [StepMath.delta].
     */
    private fun bootCount(): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrNull()?.takeIf { it >= 0 }

    companion object {
        const val READ_TIMEOUT_MS = 5_000L

        /** The shutdown broadcast leaves little time; a reading late is a reading lost. */
        const val SHUTDOWN_TIMEOUT_MS = 2_000L
    }
}
