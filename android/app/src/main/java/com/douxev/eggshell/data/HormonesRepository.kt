package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.transition.HormoneMeasurement
import uniffi.transition.NewHormoneMeasurement
import uniffi.transition.convertHormoneValue

@Singleton
class HormonesRepository @Inject constructor(
    private val vault: VaultRepository,
) {
    suspend fun add(m: NewHormoneMeasurement): HormoneMeasurement =
        withContext(Dispatchers.IO) { vault.requireSession().addHormoneMeasurement(m) }

    suspend fun listForHormone(hormone: String, offset: Long = 0, limit: Long = 200): List<HormoneMeasurement> =
        withContext(Dispatchers.IO) { vault.requireSession().listHormoneMeasurements(hormone, offset, limit) }

    suspend fun distinct(): List<String> =
        withContext(Dispatchers.IO) { vault.requireSession().distinctHormones() }

    suspend fun delete(id: Long) =
        withContext(Dispatchers.IO) { vault.requireSession().deleteHormoneMeasurement(id) }

    /**
     * The Rust core's UDL doesn't expose an in-place update for hormone
     * measurements (same story as journal entries), so we delete-then-add.
     * The returned measurement has a fresh id; callers that hold the old
     * id should discard it.
     */
    suspend fun replace(id: Long, m: NewHormoneMeasurement): HormoneMeasurement =
        withContext(Dispatchers.IO) {
            val session = vault.requireSession()
            session.deleteHormoneMeasurement(id)
            session.addHormoneMeasurement(m)
        }

    fun convert(value: Double, from: String, to: String, hormone: String): Double? =
        convertHormoneValue(value, from, to, hormone)
}
