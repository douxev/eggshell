package com.douxev.eggshell.ui.onboarding

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.douxev.eggshell.data.AppAliasManager
import com.douxev.eggshell.data.FeaturesPrefs
import com.douxev.eggshell.data.MedicationRepository
import com.douxev.eggshell.data.ScheduleRepository
import com.douxev.eggshell.data.ThemePrefs
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.reminders.LabReminderManager
import com.douxev.eggshell.reminders.LabReminderPrefs
import com.douxev.eggshell.security.DecoyVerifier
import com.douxev.eggshell.security.VaultPrefs
import com.douxev.eggshell.ui.theme.AppTheme
import uniffi.transition.NewMedication

/**
 * First run in **three** steps (§6.14), where the old wizard had seven.
 *
 *  1. **Qui peut prendre ton téléphone ?** — a situation, not a lock mode. The
 *     answer picks the vault mode; the "on peut me demander de l'ouvrir" branch
 *     chains passphrase → access + decoy PIN → disguised icon, and a discreet
 *     escape hatch still opens the raw four-mode picker.
 *  2. **Ce que tu veux suivre** — the module switches, an optional first
 *     treatment with its daily reminder, and the lab / photo / voice nudges
 *     folded in as switches instead of three screens of their own.
 *  3. **À quoi ça ressemble** — the theme, the "write your secret phrase down"
 *     warning where it applies, and the closing note.
 *
 * Everything past step 1 is skippable, and nothing here is required to start
 * using the app: every choice reappears in Réglages.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: VaultRepository,
    private val decoy: DecoyVerifier,
    private val aliasManager: AppAliasManager,
    private val features: FeaturesPrefs,
    private val medications: MedicationRepository,
    private val schedules: ScheduleRepository,
    private val labs: LabReminderManager,
    private val themePrefs: ThemePrefs,
) : ViewModel() {

    sealed interface Step {
        /** Step 1 — the situation question. */
        data object Security : Step

        // Guided "on peut me demander de l'ouvrir" branch, still inside step 1.
        data object GuidedPassphrase : Step
        data object GuidedDecoy : Step
        data object GuidedIcon : Step

        // Escape hatch: the raw four-mode picker, also inside step 1.
        data object PickMode : Step
        data class SetupPassphrase(val mode: VaultPrefs.Mode) : Step

        data object Confirming : Step

        /** Step 2 — modules, first treatment, nudges. */
        data object Modules : Step

        /** Step 3 — theme, passphrase warning, closing note. */
        data object Look : Step

        data object Done : Step
    }

    /** The situation answers of step 1, in the order they are drawn. */
    enum class Answer { Calm, CloseCircle, CanBeForced }

    /** An optional first treatment, with the daily reminder the user asked for. */
    data class FirstMedication(
        val name: String,
        val dose: Double?,
        val unit: String?,
        val reminderHour: Int?,
        val reminderMinute: Int?,
    )

    /** One of the recurring nudges (lab / photo / voice). */
    data class ReminderRequest(val label: String, val intervalDays: Int)

    private val _step = MutableStateFlow<Step>(Step.Security)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * True while a vault is being provisioned.
     *
     * [VaultRepository.isInitialized] cannot stand in for this: the mode is
     * only written once the KDF has finished, so a Paranoid derivation — which
     * takes seconds on purpose — leaves `isInitialized` false for its whole
     * duration. Anything that started a second init in that window (« Passer »
     * being the reachable one) had two provisionings racing on the same files,
     * and the loser could leave the vault permanently unopenable.
     *
     * The screen binds its skip control to this so the guard shows up as a
     * disabled affordance instead of a tap that does nothing.
     */
    private val _provisioning = MutableStateFlow(false)
    val provisioning: StateFlow<Boolean> = _provisioning.asStateFlow()

    /** Which of the three answers is highlighted; the band commits it. */
    private val _answer = MutableStateFlow<Answer?>(null)
    val answer: StateFlow<Answer?> = _answer.asStateFlow()

    // Feature toggles surfaced to step 2 (live mirror of FeaturesPrefs).
    val medicationsOn: StateFlow<Boolean> = features.medications
    val journalOn: StateFlow<Boolean> = features.journal
    val hormonesOn: StateFlow<Boolean> = features.hormones
    val weightOn: StateFlow<Boolean> = features.weightTracking
    val photosOn: StateFlow<Boolean> = features.photoTab
    val voiceOn: StateFlow<Boolean> = features.voiceTab
    val bleedingOn: StateFlow<Boolean> = features.bleeding
    val appointmentsOn: StateFlow<Boolean> = features.appointments

    fun setMedications(v: Boolean) = features.setMedications(v)
    fun setJournal(v: Boolean) = features.setJournal(v)
    fun setHormones(v: Boolean) = features.setHormones(v)
    fun setWeight(v: Boolean) = features.setWeightTracking(v)
    fun setPhotos(v: Boolean) = features.setPhotoTab(v)
    fun setVoice(v: Boolean) = features.setVoiceTab(v)
    fun setBleeding(v: Boolean) = features.setBleeding(v)
    fun setAppointments(v: Boolean) = features.setAppointments(v)

    /** Whether the chosen mode derives the key from a passphrase the user must
     *  not lose — gates the "note ta phrase secrète" warning of step 3. */
    val needsBackup: Boolean
        get() = repo.currentMode == VaultPrefs.Mode.KEYSTORE_PASSPHRASE ||
            repo.currentMode == VaultPrefs.Mode.PARANOID

    /** 0-based segment of the three-segment progress indicator. */
    fun progressIndex(step: Step): Int = when (step) {
        Step.Modules -> 1
        Step.Look -> 2
        else -> 0
    }

    /** True when the current step has somewhere to go back to. */
    fun canGoBack(step: Step): Boolean = when (step) {
        Step.GuidedPassphrase, Step.PickMode -> true
        is Step.SetupPassphrase -> true
        else -> false
    }

    fun back() {
        _error.value = null
        _step.value = when (val current = _step.value) {
            is Step.GuidedPassphrase -> Step.Security
            is Step.PickMode -> Step.Security
            is Step.SetupPassphrase -> Step.PickMode
            // No going back once the vault exists.
            else -> current
        }
    }

    // -- Step 1: the situation question ---------------------------------------

    fun selectAnswer(answer: Answer) {
        _error.value = null
        _answer.value = answer
    }

    /** Commits the highlighted answer. Called by the action band. */
    fun commitAnswer(activity: FragmentActivity, biometricCopy: VaultRepository.BiometricCopy) {
        when (_answer.value) {
            Answer.Calm -> chooseNoCode()
            Answer.CloseCircle -> chooseBiometric(activity, biometricCopy)
            Answer.CanBeForced -> chooseAtRisk()
            null -> Unit
        }
    }

    /** "Personne, je suis tranquille" — instant Keystore vault. */
    fun chooseNoCode() = initThen({ repo.initializeKeystoreOnly() }, back = Step.Security)

    /** "Mon entourage, parfois" — Keystore vault gated by fingerprint/face. */
    fun chooseBiometric(activity: FragmentActivity, biometricCopy: VaultRepository.BiometricCopy) =
        initThen({ repo.initializeKeystoreBiometric(activity, biometricCopy) }, back = Step.Security)

    /** "On peut me demander de l'ouvrir" — guided Paranoid + decoy + icon. */
    fun chooseAtRisk() {
        _error.value = null
        _step.value = Step.GuidedPassphrase
    }

    /** Discreet escape hatch: the raw four-mode picker. */
    fun chooseAdvanced() {
        _error.value = null
        _step.value = Step.PickMode
    }

    /**
     * "Passer" in the progress row. Skipping before a vault exists still has to
     * create one, so it takes the same default the first card offers — and the
     * screen asks for confirmation first, because that default is the weakest.
     */
    fun skipAll() {
        _error.value = null
        if (repo.isInitialized) {
            _step.value = Step.Done
            return
        }
        provision(onSuccess = Step.Done, back = Step.Security) { repo.initializeKeystoreOnly() }
    }

    private fun initThen(block: suspend () -> Unit, back: Step) =
        provision(onSuccess = Step.Modules, back = back, block = block)

    /**
     * The single door to vault creation. Every caller goes through here so the
     * in-flight flag can't be forgotten by one of them — two concurrent inits
     * write the same key material and can wedge the vault shut for good.
     *
     * A rejected call is not silent: the control that reached it is disabled
     * while [provisioning] is true, so this only ever catches a tap that raced
     * the recomposition.
     */
    private fun provision(onSuccess: Step, back: Step, block: suspend () -> Unit) {
        if (!_provisioning.compareAndSet(expect = false, update = true)) return
        _error.value = null
        _step.value = Step.Confirming
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _step.value = onSuccess }
                .onFailure { fail(it, back) }
            _provisioning.value = false
        }
    }

    // -- Step 1, guided branch ------------------------------------------------

    fun submitGuidedPassphrase(passphrase: String) {
        if (_step.value !is Step.GuidedPassphrase) return
        provision(onSuccess = Step.GuidedDecoy, back = Step.GuidedPassphrase) {
            repo.initializeParanoid(passphrase)
        }
    }

    fun submitGuidedDecoy(accessPin: String, decoyPin: String) {
        _error.value = null
        viewModelScope.launch {
            runCatching {
                require(accessPin.length == 4 && decoyPin.length == 4) {
                    "chaque PIN doit faire 4 chiffres"
                }
                require(accessPin != decoyPin) {
                    "le code d'accès et le PIN de leurre doivent différer"
                }
                decoy.setPair(accessPin, decoyPin)
            }
                .onSuccess { _step.value = Step.GuidedIcon }
                .onFailure { _error.value = describe(it) }
        }
    }

    fun skipDecoy() {
        _error.value = null
        _step.value = Step.GuidedIcon
    }

    fun chooseIcon(variant: AppAliasManager.Variant) {
        _error.value = null
        runCatching { aliasManager.setVariant(variant) }
        _step.value = Step.Modules
    }

    fun skipIcon() {
        _error.value = null
        _step.value = Step.Modules
    }

    // -- Step 1, manual picker ------------------------------------------------

    fun pickMode(mode: VaultPrefs.Mode, activity: FragmentActivity, biometricCopy: VaultRepository.BiometricCopy) {
        _error.value = null
        when (mode) {
            VaultPrefs.Mode.KEYSTORE_ONLY ->
                initThen({ repo.initializeKeystoreOnly() }, back = Step.PickMode)
            VaultPrefs.Mode.KEYSTORE_BIOMETRIC ->
                initThen({ repo.initializeKeystoreBiometric(activity, biometricCopy) }, back = Step.PickMode)
            VaultPrefs.Mode.KEYSTORE_PASSPHRASE,
            VaultPrefs.Mode.PARANOID ->
                _step.value = Step.SetupPassphrase(mode)
        }
    }

    fun submitPassphrase(passphrase: String) {
        val current = _step.value
        if (current !is Step.SetupPassphrase) return
        provision(onSuccess = Step.Modules, back = Step.PickMode) {
            when (current.mode) {
                VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> repo.initializeKeystorePassphrase(passphrase)
                VaultPrefs.Mode.PARANOID -> repo.initializeParanoid(passphrase)
                else -> error("unexpected mode for passphrase setup")
            }
        }
    }

    // -- Step 2: modules, first treatment, nudges -----------------------------

    /**
     * Persists everything step 2 collected, then moves on. The module switches
     * are already live (they write straight through to [FeaturesPrefs]); what
     * lands here is the optional treatment and the recurring nudges, each of
     * which is only created if the user actually asked for it.
     */
    fun proceedFromModules(
        medication: FirstMedication?,
        labReminder: ReminderRequest?,
        photoReminder: ReminderRequest?,
        voiceReminder: ReminderRequest?,
    ) {
        _error.value = null
        viewModelScope.launch {
            if (medication != null) {
                runCatching {
                    val med = medications.add(
                        NewMedication(
                            name = medication.name.trim(),
                            kind = "other",
                            route = "oral",
                            defaultDose = medication.dose,
                            defaultDoseUnit = medication.unit?.ifBlank { null },
                            color = null,
                            notes = null,
                        )
                    )
                    val hour = medication.reminderHour
                    val minute = medication.reminderMinute
                    if (hour != null && minute != null) {
                        schedules.createDaily(med.id, hour, minute)
                    }
                }.onFailure { _error.value = describe(it) }
            }
            createReminder(labReminder, LabReminderPrefs.CATEGORY_LAB)
            createReminder(photoReminder, LabReminderPrefs.CATEGORY_PHOTO)
            createReminder(voiceReminder, LabReminderPrefs.CATEGORY_VOICE)
            _step.value = Step.Look
        }
    }

    private fun createReminder(request: ReminderRequest?, category: String) {
        request ?: return
        runCatching { labs.createInterval(request.label, request.intervalDays, category) }
            .onFailure { _error.value = describe(it) }
    }

    // -- Step 3: theme, warning, closing note ---------------------------------

    val selectedTheme: StateFlow<AppTheme> = themePrefs.theme

    /** Live theme preview — applied immediately so the whole app reflects it. */
    fun setTheme(theme: AppTheme) {
        themePrefs.set(theme)
    }

    fun finish() {
        _step.value = Step.Done
    }

    private fun fail(t: Throwable, back: Step) {
        _error.value = describe(t)
        _step.value = back
    }

    // Class name + message — keystore/biometric failures often have a null or
    // one-word message; the class tells us which variant fired.
    private fun describe(t: Throwable): String =
        "${t::class.java.simpleName}: ${t.message ?: "no detail"}"
}
