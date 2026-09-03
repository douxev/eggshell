package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Daily step totals from the device\'s own hardware counter.
 *
 * `TYPE_STEP_COUNTER`, not `TYPE_STEP_DETECTOR`. The counter is maintained by a
 * low-power co-processor and read on demand; the detector fires an event per
 * step and would wake the CPU thousands of times a day to compute a number the
 * hardware is already keeping. There is no foreground service and no permanent
 * notification: the counter is read when the app is open and by the periodic
 * worker, which is enough for a daily total and costs nothing between reads.
 *
 * # The reboot problem
 *
 * The counter reports steps **since the device last booted**, and resets to
 * zero on every reboot. So a raw reading is meaningless on its own — what
 * matters is how much it has grown since the last time we looked.
 *
 * This class keeps the last raw reading and the boot it belonged to. A reading
 * lower than the last one means the device rebooted, and the delta is the whole
 * new reading rather than a negative number. The daily total in the vault only
 * ever moves up ([SportRepository.recordSteps] takes the max), so even if this
 * bookkeeping were wrong the day could not be erased — two independent guards,
 * because losing a day of someone\'s activity to an off-by-one is exactly the
 * silent kind of loss this app must not have.
 *
 * The bookkeeping lives in plain prefs rather than the vault: it has to survive
 * a reboot and be readable before any unlock, and a raw counter value says
 * nothing about anyone.
 */
@Singleton
class StepCounter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences = SecurePrefs.get(context, PREFS_NAME)

    private val sensorManager: SensorManager?
        get() = context.getSystemService(SensorManager::class.java)

    /** Whether this device can count steps at all. */
    val isAvailable: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /**
     * Read the counter once and return how many steps have accrued since the
     * previous read, or null when the sensor is unavailable or does not answer.
     *
     * Null is not zero, and callers must not treat it as one: "the sensor said
     * nothing" and "you did not move" are different facts, and writing the
     * second when the first is true is how a day gets flattened.
     */
    suspend fun readDelta(): Long? {
        val manager = sensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        // The counter only emits on change; a device that has been still since
        // boot may never deliver. Bounded so a quiet sensor cannot hang a
        // caller — SENSOR_DELAY_FASTEST makes it prompt when it does answer.
        val raw = withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine<Long?> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull()?.toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
            }
        } ?: return null

        return consume(raw)
    }

    /**
     * Turn a raw cumulative reading into a delta, and remember it.
     *
     * Internal and pure enough to test: this is where the reboot rule lives,
     * and a mistake in it is invisible — the number still looks like a step
     * count.
     */
    internal fun consume(raw: Long): Long {
        val previous = prefs.getLong(KEY_LAST_RAW, -1L)
        prefs.edit().putLong(KEY_LAST_RAW, raw).apply()
        return when {
            // First ever read: we have no baseline, so nothing can be
            // attributed yet. Claiming the whole counter would credit the user
            // with every step since their last reboot, possibly days of them.
            previous < 0 -> 0L
            // Counter went backwards: the device rebooted. Everything it reports
            // now has happened since.
            raw < previous -> raw
            else -> raw - previous
        }
    }

    /** Forget the baseline — used when the user turns the pedometer off. */
    fun reset() {
        prefs.edit().remove(KEY_LAST_RAW).apply()
    }

    /** The local day a reading should be credited to. */
    fun today(): LocalDate = LocalDate.now()

    companion object {
        private const val PREFS_NAME = "transition_step_counter"
        private const val KEY_LAST_RAW = "last_raw"
        private const val READ_TIMEOUT_MS = 3_000L
    }
}
