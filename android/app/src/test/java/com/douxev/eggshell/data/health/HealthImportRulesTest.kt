package com.douxev.eggshell.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.douxev.eggshell.data.SportRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules of a Health Connect import that fail without a sound: a
 * workout filed under no type, a run imported twice, a window that leaves a
 * gap nobody ever reads.
 */
class HealthImportRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------- window

    @Test
    fun `the first sync reads thirty days`() {
        assertEquals(now - 30 * day, HealthImportRules.windowStart(now, null))
        assertEquals(now - 30 * day, HealthImportRules.windowStart(now, 0L))
    }

    @Test
    fun `a later sync starts two days before the last one`() {
        val last = now - 5 * day
        assertEquals(last - 2 * day, HealthImportRules.windowStart(now, last))
    }

    @Test
    fun `a sync recorded in the future does not push the window past now`() {
        val future = now + 10 * day
        assertEquals(now - 2 * day, HealthImportRules.windowStart(now, future))
    }

    // -------------------------------------------------------------- dedupe

    private fun existing(startedMs: Long, source: String) =
        HealthImportRules.Existing(startedMs, source)

    @Test
    fun `an import close to another import is the same workout`() {
        val others = listOf(existing(now + 45_000, SportRepository.SOURCE_WATCH))
        assertTrue(HealthImportRules.isDuplicate(now, others))
    }

    @Test
    fun `an earlier health import a few seconds off is the same workout`() {
        val others = listOf(existing(now - 30_000, SportRepository.SOURCE_HEALTH))
        assertTrue(HealthImportRules.isDuplicate(now, others))
    }

    @Test
    fun `just past the minute it is another workout`() {
        val others = listOf(existing(now + 61_000, SportRepository.SOURCE_WATCH))
        assertFalse(HealthImportRules.isDuplicate(now, others))
    }

    @Test
    fun `a manual session nearby is the user's own and does not hide the import`() {
        val others = listOf(existing(now + 20_000, SportRepository.SOURCE_MANUAL))
        assertFalse(HealthImportRules.isDuplicate(now, others))
    }

    @Test
    fun `the exact same start is a duplicate whoever logged it`() {
        val others = listOf(existing(now, SportRepository.SOURCE_MANUAL))
        assertTrue(HealthImportRules.isDuplicate(now, others))
    }

    @Test
    fun `nothing in the vault means nothing to skip`() {
        assertFalse(HealthImportRules.isDuplicate(now, emptyList()))
    }

    // -------------------------------------------------------- exercise hint

    @Test
    fun `common workouts map to the words the activity matcher knows`() {
        val cases = mapOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "running",
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "running",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "biking",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "biking",
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "walking",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "swimming",
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "swimming",
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "strength",
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "strength",
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "yoga",
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "rowing",
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "elliptical",
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "skiing",
        )
        cases.forEach { (type, hint) ->
            assertEquals("type $type", hint, HealthImportRules.exerciseHint(type))
        }
    }

    @Test
    fun `an unmapped type gives no hint rather than a wrong one`() {
        assertNull(HealthImportRules.exerciseHint(ExerciseSessionRecord.EXERCISE_TYPE_GOLF))
        assertNull(HealthImportRules.exerciseHint(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
    }

    // ----------------------------------------------------- values for the vault

    @Test
    fun `heart rates outside what the vault accepts are dropped, not clamped`() {
        assertEquals(20L, HealthImportRules.heartRateOrNull(20L))
        assertEquals(300L, HealthImportRules.heartRateOrNull(300L))
        assertNull(HealthImportRules.heartRateOrNull(19L))
        assertNull(HealthImportRules.heartRateOrNull(301L))
        assertNull(HealthImportRules.heartRateOrNull(null))
    }

    @Test
    fun `a duration is whole seconds and never negative`() {
        assertEquals(1_800L, HealthImportRules.durationSeconds(now, now + 1_800_999))
        assertEquals(0L, HealthImportRules.durationSeconds(now, now - 5_000))
    }
}
