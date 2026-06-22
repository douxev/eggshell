package com.douxev.eggshell.data

import com.douxev.eggshell.reminders.AlarmScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.Appointment
import uniffi.transition.NewAppointment

/**
 * Appointments / notes ("RDV"). Scalar fields live in the encrypted vault; the
 * optional reminder is mirrored to a one-shot [AlarmScheduler] alarm keyed on
 * the appointment id, kept in sync on every add/update/delete. The alarm only
 * carries the numeric id — the notification body never reveals appointment
 * details (see [com.douxev.eggshell.reminders.ReminderNotifications.showAppointment]).
 */
@Singleton
class AppointmentRepository @Inject constructor(
    private val vault: VaultRepository,
    private val alarmScheduler: AlarmScheduler,
) {
    suspend fun list(offset: Long = 0, limit: Long = 500): List<Appointment> =
        withContext(Dispatchers.IO) { vault.requireSession().listAppointments(offset, limit) }

    suspend fun get(id: Long): Appointment? =
        withContext(Dispatchers.IO) { vault.requireSession().getAppointment(id) }

    suspend fun add(entry: NewAppointment): Appointment = withContext(Dispatchers.IO) {
        val saved = vault.requireSession().addAppointment(entry)
        syncReminder(saved.id, saved.reminderAtMs)
        saved
    }

    suspend fun update(id: Long, entry: NewAppointment): Appointment = withContext(Dispatchers.IO) {
        val saved = vault.requireSession().updateAppointment(id, entry)
        syncReminder(saved.id, saved.reminderAtMs)
        saved
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        vault.requireSession().deleteAppointment(id)
        alarmScheduler.cancelAppointment(id)
    }

    /**
     * Re-arm every future appointment reminder. Appointment alarms don't
     * survive a reboot (the reminder time lives in the locked vault, so
     * [com.douxev.eggshell.reminders.BootReceiver] can't restore them); calling
     * this on vault unlock — when the DB is readable again — re-registers any
     * still-pending alarm before its fire time.
     */
    suspend fun reschedulePending() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        runCatching { vault.requireSession().listAppointments(0, 1000) }
            .getOrDefault(emptyList())
            .forEach { a -> a.reminderAtMs?.let { if (it > now) alarmScheduler.scheduleAppointment(a.id, it) } }
    }

    /** Arm the reminder if it's set and in the future; otherwise clear it. */
    private fun syncReminder(id: Long, reminderAtMs: Long?) {
        if (reminderAtMs != null && reminderAtMs > System.currentTimeMillis()) {
            alarmScheduler.scheduleAppointment(id, reminderAtMs)
        } else {
            alarmScheduler.cancelAppointment(id)
        }
    }
}
