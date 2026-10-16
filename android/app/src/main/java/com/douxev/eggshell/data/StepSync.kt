package com.douxev.eggshell.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the pedometer does, in one place: read the counter, keep what it
 * says until the vault can take it, hand it over, and keep the readings coming
 * while the app is closed.
 *
 * # When the counter is read
 *
 * The previous build read it only when the Sport screen opened, and credited
 * whatever had accrued to that day — so two days of walking landed on the
 * morning of the third, and a reboot in between could take them with it. Now:
 *
 * - every 15 minutes, by [StepWorker] (WorkManager, so it survives reboots and
 *   needs no service of its own);
 * - at 23:59 local, by an alarm, so the day's total closes on the right side of
 *   midnight even when Doze has pushed the worker back by hours;
 * - on `ACTION_SHUTDOWN`, because the counter restarts at zero on boot and
 *   anything not read before then is gone;
 * - on unlock and whenever the Sport screen opens.
 *
 * No foreground service and no permanent notification — each read is a single
 * sensor event from a co-processor that is counting anyway.
 *
 * # Where the readings go
 *
 * Into [StepLedger], sealed and off-vault, then into the vault by [flush] as
 * soon as it is open. Every security mode works the same way, paranoid
 * included, exactly like the dose queue: nothing is written to the vault
 * without its key, and a decoy unlock never opens the real session, so it
 * never flushes.
 */
@Singleton
class StepSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val counter: StepCounter,
    private val ledger: StepLedger,
    private val prefs: SportPrefs,
    private val vault: VaultRepository,
    private val sport: SportRepository,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Read the counter and credit the reading to the ledger.
     *
     * @return false when the pedometer is off, the sensor did not answer, or
     * the ledger could not be sealed — in every one of those cases the baseline
     * stays where it was, so the steps are still in the counter for next time.
     */
    suspend fun sample(timeoutMs: Long = StepCounter.READ_TIMEOUT_MS): Boolean {
        if (!prefs.pedometer.value) return false
        val reading = counter.read(timeoutMs) ?: return false
        val now = System.currentTimeMillis()
        val delta = ledger.update { state ->
            val base = if (state.lastRaw == null) firstBaseline(state, reading, now) else state
            StepMath.record(base, reading.raw, reading.boot, now, zone, reading.bootMs)
        } ?: return false
        // A Sport widget showing today's steps reads the ledger when it draws;
        // tell it there is something new to draw. Cheap when there is none.
        if (delta > 0) {
            runCatching {
                com.douxev.eggshell.widget.WidgetRefresh.refreshModule(
                    context, com.douxev.eggshell.modules.AppModule.Sport,
                )
            }
        }
        return true
    }

    /**
     * The baseline for the very first reading.
     *
     * Normally there is none and the first reading credits nothing: the counter
     * holds every step since boot, and a boot three days ago would hand today
     * three days of walking. One case is certain, though — if the phone booted
     * today, everything the counter holds was walked today, so it can be
     * credited instead of being thrown away.
     *
     * Only on the very first switch-on: after « Compter mes pas » was turned
     * off and on again the ledger already holds days, and crediting everything
     * since boot would count again what was counted before the switch-off and
     * add what was walked while counting was off.
     */
    private fun firstBaseline(state: StepState, reading: StepCounter.Reading, now: Long): StepState {
        if (state.days.isNotEmpty()) return state
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        if (reading.bootMs < midnight || reading.bootMs > now) return state
        return state.copy(lastRaw = 0L, lastBoot = reading.boot, lastReadMs = reading.bootMs)
    }

    /**
     * Pour the ledger into the vault, when the vault is open.
     *
     * Every day still in the ledger is written, flushed or not: the vault keeps
     * the larger of stored and incoming, so writing a total twice is harmless,
     * and writing them all heals a vault that was restored from a backup or
     * otherwise lost a day the ledger still remembers.
     *
     * **The one-time seeding.** The build before this one wrote steps straight
     * into the vault. On the first flush after the upgrade, the vault's figure
     * for each day the ledger has counted is added to the ledger's — before
     * that, the two were counting different stretches of the same day, and the
     * vault's MAX would have kept the larger instead of the sum. It runs once,
     * and its result is committed before anything is written, so a crash
     * halfway cannot seed twice.
     *
     * @return true when the vault holds everything the ledger had.
     */
    suspend fun flush(): Boolean {
        if (!vault.isUnlocked) return false
        return ledger.locked { initial, commit ->
            var state = initial
            // Nothing counted: nothing to seed, nothing to write — and no
            // sealed file created for someone who never turned the pedometer on.
            if (state.days.isEmpty()) return@locked true
            if (!state.seeded) {
                val seeded = runCatching {
                    state.days.mapValues { (day, entry) ->
                        val stored = sport.stepDay(day)?.steps ?: 0L
                        StepDayEntry(entry.steps + stored, flushed = false)
                    }
                }.getOrNull() ?: return@locked false
                state = state.copy(seeded = true, days = seeded)
                if (!commit(state)) return@locked false
            }
            val written = runCatching {
                sport.recordStepDays(state.days.mapValues { it.value.steps })
            }.isSuccess
            if (!written) return@locked false
            state = state.copy(days = state.days.mapValues { it.value.copy(flushed = true) })
            commit(StepMath.prune(state, LocalDate.now(zone), zone))
        }
    }

    /** Read, then flush if the vault is open. What every trigger does. */
    suspend fun sampleAndFlush(timeoutMs: Long = StepCounter.READ_TIMEOUT_MS) {
        sample(timeoutMs)
        flush()
    }

    /**
     * Set a day to exactly [steps] — the only way to correct one downward.
     *
     * The ledger's total for that day is set to the same figure, or the next
     * flush would put the old, larger number straight back; and further
     * walking that day adds to the corrected figure rather than starting from
     * a zero the vault's MAX would ignore.
     */
    suspend fun correct(day: LocalDate, steps: Long) {
        val value = steps.coerceAtLeast(0L)
        // Pending readings first, so the correction is the last word on the day.
        flush()
        ledger.locked { state, commit ->
            sport.setSteps(day, value)
            // A corrected day is authoritative, so it must never be seeded on
            // top of: when it is the only day the ledger holds, the upgrade
            // seeding has nothing left to do and is marked done.
            commit(
                state.copy(
                    seeded = state.seeded || (state.days - day).isEmpty(),
                    days = state.days + (day to StepDayEntry(value, flushed = true)),
                    corrected = state.corrected + day,
                )
            )
        }
    }

    /**
     * Where a live display starts from: the raw value of the last reading and
     * the ledger's total for today. A screen following the counter adds the
     * growth since that reading to today's figure, without writing anything.
     */
    suspend fun liveBase(): Pair<Long?, Long> {
        val state = ledger.snapshot()
        return state.lastRaw to (state.days[LocalDate.now(zone)]?.steps ?: 0L)
    }

    /**
     * The days the user corrected by hand, recently enough for an import to
     * reach them. Health Connect skips these: its total for the day is the
     * very figure the correction replaced.
     */
    suspend fun correctedDays(): Set<LocalDate> = ledger.snapshot().corrected

    /** Today's total as the ledger knows it — for a widget, while the vault is shut. */
    suspend fun todayInLedger(): Long? =
        ledger.snapshot().days[LocalDate.now(zone)]?.steps

    // -- Switching on and off -------------------------------------------------

    /** The user just turned the pedometer on: start the readings now. */
    suspend fun enable() {
        ensureScheduled()
        sampleAndFlush()
    }

    /**
     * The user turned it off: stop reading, keep what was counted.
     *
     * The baseline is dropped so switching back on later does not credit the
     * whole interval in between; the per-day totals stay until the vault has
     * them, because « Compter mes pas » off means "stop counting", not "forget
     * what you counted".
     */
    suspend fun disable() {
        cancelSchedule()
        flush()
        ledger.update { it.copy(lastRaw = null, lastBoot = null, lastReadMs = null) to Unit }
    }

    /**
     * Make sure the worker and the midnight alarm exist. Idempotent: called on
     * every app start, after a boot, after an update and when the clock or the
     * timezone changes (the alarm is an absolute instant, and "23:59" moves
     * with the timezone).
     */
    fun ensureScheduled() {
        if (!prefs.pedometer.value) return
        setReceiverEnabled(true)
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                StepWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StepWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build(),
            )
        }
        armDayEnd()
    }

    private fun cancelSchedule() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(StepWorker.NAME) }
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(dayEndIntent()) }
        setReceiverEnabled(false)
    }

    /**
     * [StepReceiver] ships disabled: with the pedometer off, nothing should
     * start this app at every shutdown and clock change.
     */
    private fun setReceiverEnabled(enabled: Boolean) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(context, StepReceiver::class.java),
                if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * The 23:59 reading. Exact where allowed (the app already holds the
     * exact-alarm permission for medication reminders), inexact otherwise —
     * the 15-minute worker and the split in [StepMath] cover the difference.
     */
    fun armDayEnd() {
        if (!prefs.pedometer.value) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextDayEnd(ZonedDateTime.now(zone)).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, dayEndIntent())
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, dayEndIntent())
            }
        }
    }

    private fun dayEndIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        DAY_END_REQUEST,
        Intent(context, StepReceiver::class.java).setAction(StepReceiver.ACTION_DAY_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** WorkManager's floor for periodic work. */
        const val PERIOD_MINUTES = 15L
        private const val DAY_END_REQUEST = 0x5745

        /** The next 23:59 strictly after [now], in [now]'s zone. */
        internal fun nextDayEnd(now: ZonedDateTime): ZonedDateTime {
            val today = now.toLocalDate().atTime(DAY_END).atZone(now.zone)
            return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(DAY_END).atZone(now.zone)
        }

        private val DAY_END: LocalTime = LocalTime.of(23, 59)
    }
}
