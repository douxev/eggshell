package com.douxev.eggshell.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-view feature flags, surfaced in Réglages → Fonctionnalités.
 *
 * When a feature is off:
 *   - its bottom-tab entry is hidden,
 *   - any settings-hub shortcut to it is hidden,
 *   - the NavHost route still exists so deep-links (widget journal action,
 *     today-screen CTAs that haven't been wired into the toggles yet) keep
 *     working.
 *
 * Today is the only non-togglable scaffold; everything else (including
 * Médics) can be hidden. Médics defaults on because it's the core use case,
 * but a user who only tracks moods / hormones / voice can hide it.
 *
 * Defaults:
 *   - Médics / Journal / Courbes / Poids: on (core features).
 *   - Photos / Voix: off (always opt-in).
 */
@Singleton
class FeaturesPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        SecurePrefs.get(context, PREFS_NAME)

    private val _medications = MutableStateFlow(prefs.getBoolean(KEY_MEDS, true))
    val medications: StateFlow<Boolean> = _medications.asStateFlow()

    private val _journal = MutableStateFlow(prefs.getBoolean(KEY_JOURNAL, true))
    val journal: StateFlow<Boolean> = _journal.asStateFlow()

    private val _hormones = MutableStateFlow(prefs.getBoolean(KEY_HORMONES, true))
    val hormones: StateFlow<Boolean> = _hormones.asStateFlow()

    /** Sub-feature of hormones: the Poids tab inside Courbes. */
    private val _weightTracking = MutableStateFlow(prefs.getBoolean(KEY_WEIGHT, true))
    val weightTracking: StateFlow<Boolean> = _weightTracking.asStateFlow()

    private val _photoTab = MutableStateFlow(prefs.getBoolean(KEY_PHOTO, false))
    val photoTab: StateFlow<Boolean> = _photoTab.asStateFlow()

    private val _voiceTab = MutableStateFlow(prefs.getBoolean(KEY_VOICE, false))
    val voiceTab: StateFlow<Boolean> = _voiceTab.asStateFlow()

    /** Bleeding / cycle tracking. Opt-in and off by default: bleeding is a
     *  strong sex-assigned-at-birth signal, so it stays hidden until enabled. */
    private val _bleeding = MutableStateFlow(prefs.getBoolean(KEY_BLEEDING, false))
    val bleeding: StateFlow<Boolean> = _bleeding.asStateFlow()

    /** Appointments / notes ("RDV"). Opt-in: appointment content (clinic,
     *  professional) can be very identifying, so it stays hidden until enabled. */
    private val _appointments = MutableStateFlow(prefs.getBoolean(KEY_APPOINTMENTS, false))
    val appointments: StateFlow<Boolean> = _appointments.asStateFlow()

    fun setMedications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDS, enabled).apply()
        _medications.value = enabled
    }

    fun setJournal(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_JOURNAL, enabled).apply()
        _journal.value = enabled
    }

    fun setHormones(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HORMONES, enabled).apply()
        _hormones.value = enabled
    }

    fun setWeightTracking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEIGHT, enabled).apply()
        _weightTracking.value = enabled
    }

    fun setPhotoTab(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHOTO, enabled).apply()
        _photoTab.value = enabled
    }

    fun setVoiceTab(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
        _voiceTab.value = enabled
    }

    fun setBleeding(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLEEDING, enabled).apply()
        _bleeding.value = enabled
    }

    fun setAppointments(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APPOINTMENTS, enabled).apply()
        _appointments.value = enabled
    }

    companion object {
        // Pref-file name unchanged so existing installs keep their photo/voice
        // tab + weight-tracking choices across the rename.
        private const val PREFS_NAME = "transition_nav_tabs"
        private const val KEY_MEDS = "feature_medications"
        private const val KEY_JOURNAL = "feature_journal"
        private const val KEY_HORMONES = "feature_hormones"
        private const val KEY_WEIGHT = "weight_tracking"
        private const val KEY_PHOTO = "show_photo"
        private const val KEY_VOICE = "show_voice"
        private const val KEY_BLEEDING = "feature_bleeding"
        private const val KEY_APPOINTMENTS = "feature_appointments"
    }
}
