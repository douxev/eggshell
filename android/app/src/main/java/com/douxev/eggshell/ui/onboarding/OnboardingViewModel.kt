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
 * Onboarding as two halves, both built around progressive disclosure:
 *
 *  1. **Security** — three plain-language choices (no-code / biometric / "I need
 *     protection" → Paranoid + decoy + neutral icon), plus a discreet "configure
 *     manually" path to the four raw modes.
 *  2. **Setup wizard** — pick which features to use, then a skippable one-screen
 *     config per enabled feature, a theme picker, an optional backup reminder
 *     (passphrase modes only), the doctor-export pitch, and a recap.
 *
 * The wizard pages are computed from the feature toggles, so the user only ever
 * sees screens for things they turned on. Everything past "Features" is
 * skippable — nothing here is required to start using the app.
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

    /** Pages of the post-security setup wizard. */
    enum class ConfigPage { Features, Medication, Labs, Photos, Voice, Theme, Backup, Export, Recap }

    sealed interface Step {
        data object Welcome : Step
        data object ChooseScenario : Step

        // Guided at-risk flow.
        data object GuidedPassphrase : Step
        data object GuidedDecoy : Step
        data object GuidedIcon : Step

        // Advanced / manual path (the original four-mode picker).
        data object PickMode : Step
        data class SetupPassphrase(val mode: VaultPrefs.Mode) : Step

        data object Confirming : Step
        data class Config(val page: ConfigPage) : Step
        data object Done : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Welcome)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Feature toggles surfaced to the Features page (live mirror of FeaturesPrefs).
    val medicationsOn: StateFlow<Boolean> = features.medications
    val journalOn: StateFlow<Boolean> = features.journal
    val hormonesOn: StateFlow<Boolean> = features.hormones
    val weightOn: StateFlow<Boolean> = features.weightTracking
    val photosOn: StateFlow<Boolean> = features.photoTab
    val voiceOn: StateFlow<Boolean> = features.voiceTab

    fun setMedications(v: Boolean) = features.setMedications(v)
    fun setJournal(v: Boolean) = features.setJournal(v)
    fun setHormones(v: Boolean) = features.setHormones(v)
    fun setWeight(v: Boolean) = features.setWeightTracking(v)
    fun setPhotos(v: Boolean) = features.setPhotoTab(v)
    fun setVoice(v: Boolean) = features.setVoiceTab(v)

    /** Whether the chosen mode derives the key from a passphrase the user must
     *  not lose — gates the backup-reminder page. */
    val needsBackup: Boolean
        get() = repo.currentMode == VaultPrefs.Mode.KEYSTORE_PASSPHRASE ||
            repo.currentMode == VaultPrefs.Mode.PARANOID

    private var wizardPages: List<ConfigPage> = emptyList()
    private var wizardIndex: Int = 0

    fun next() {
        _error.value = null
        if (_step.value is Step.Welcome) _step.value = Step.ChooseScenario
    }

    fun back() {
        _error.value = null
        _step.value = when (_step.value) {
            is Step.ChooseScenario -> Step.Welcome
            is Step.GuidedPassphrase -> Step.ChooseScenario
            is Step.PickMode -> Step.ChooseScenario
            is Step.SetupPassphrase -> Step.PickMode
            // No going back once the vault exists or inside the wizard.
            else -> _step.value
        }
    }

    // -- Security scenario entry points ---------------------------------------

    /** "Safe / no-code" — instant Keystore vault. */
    fun chooseNoCode() = initThen({ repo.initializeKeystoreOnly() }, back = Step.ChooseScenario)

    /** "Biometric" — Keystore vault gated by fingerprint/face. */
    fun chooseBiometric(activity: FragmentActivity, biometricCopy: VaultRepository.BiometricCopy) =
        initThen({ repo.initializeKeystoreBiometric(activity, biometricCopy) }, back = Step.ChooseScenario)

    /** "At-risk" — start the guided Paranoid + decoy + icon flow. */
    fun chooseAtRisk() {
        _error.value = null
        _step.value = Step.GuidedPassphrase
    }

    /** Power-user escape hatch: the raw four-mode picker. */
    fun chooseAdvanced() {
        _error.value = null
        _step.value = Step.PickMode
    }

    private fun initThen(block: suspend () -> Unit, back: Step) {
        _error.value = null
        _step.value = Step.Confirming
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { enterWizard() }
                .onFailure { fail(it, back) }
        }
    }

    // -- Guided at-risk steps -------------------------------------------------

    fun submitGuidedPassphrase(passphrase: String) {
        if (_step.value !is Step.GuidedPassphrase) return
        _error.value = null
        _step.value = Step.Confirming
        viewModelScope.launch {
            runCatching { repo.initializeParanoid(passphrase) }
                .onSuccess { _step.value = Step.GuidedDecoy }
                .onFailure { fail(it, Step.GuidedPassphrase) }
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
        enterWizard()
    }

    fun skipIcon() {
        _error.value = null
        enterWizard()
    }

    // -- Advanced / manual path -----------------------------------------------

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
        _step.value = Step.Confirming
        viewModelScope.launch {
            runCatching {
                when (current.mode) {
                    VaultPrefs.Mode.KEYSTORE_PASSPHRASE -> repo.initializeKeystorePassphrase(passphrase)
                    VaultPrefs.Mode.PARANOID -> repo.initializeParanoid(passphrase)
                    else -> error("unexpected mode for passphrase setup")
                }
            }
                .onSuccess { enterWizard() }
                .onFailure { fail(it, Step.PickMode) }
        }
    }

    // -- Setup wizard ---------------------------------------------------------

    private fun enterWizard() {
        _error.value = null
        wizardPages = listOf(ConfigPage.Features)
        wizardIndex = 0
        _step.value = Step.Config(ConfigPage.Features)
    }

    /** Leave the Features page: compute the rest of the wizard from the toggles. */
    fun proceedFromFeatures() {
        _error.value = null
        wizardPages = buildList {
            add(ConfigPage.Features)
            if (features.medications.value) add(ConfigPage.Medication)
            if (features.hormones.value) add(ConfigPage.Labs)
            if (features.photoTab.value) add(ConfigPage.Photos)
            if (features.voiceTab.value) add(ConfigPage.Voice)
            // Journal / Poids have nothing to configure — no page.
            add(ConfigPage.Theme)
            if (needsBackup) add(ConfigPage.Backup)
            add(ConfigPage.Export)
            add(ConfigPage.Recap)
        }
        wizardIndex = 1
        _step.value = Step.Config(wizardPages.getOrElse(1) { ConfigPage.Recap })
    }

    /** Advance to the next wizard page (used by both "continue" and "skip"). */
    fun nextWizardPage() {
        _error.value = null
        wizardIndex += 1
        _step.value =
            if (wizardIndex >= wizardPages.size) Step.Done
            else Step.Config(wizardPages[wizardIndex])
    }

    // Per-feature config actions. Each persists then advances the wizard.

    fun addMedication(name: String, dose: Double?, unit: String?, reminderHour: Int?, reminderMinute: Int?) {
        viewModelScope.launch {
            runCatching {
                val med = medications.add(
                    NewMedication(
                        name = name.trim(),
                        kind = "other",
                        route = "oral",
                        defaultDose = dose,
                        defaultDoseUnit = unit?.ifBlank { null },
                        color = null,
                        notes = null,
                    )
                )
                if (reminderHour != null && reminderMinute != null) {
                    schedules.createDaily(med.id, reminderHour, reminderMinute)
                }
            }.onFailure { _error.value = describe(it) }
            nextWizardPage()
        }
    }

    fun addLabReminder(label: String, intervalDays: Int) {
        runCatching { labs.createInterval(label, intervalDays, LabReminderPrefs.CATEGORY_LAB) }
            .onFailure { _error.value = describe(it) }
        nextWizardPage()
    }

    fun addPhotoReminder(label: String, intervalDays: Int) {
        runCatching { labs.createInterval(label, intervalDays, LabReminderPrefs.CATEGORY_PHOTO) }
            .onFailure { _error.value = describe(it) }
        nextWizardPage()
    }

    fun addVoiceReminder(label: String, intervalDays: Int) {
        runCatching { labs.createInterval(label, intervalDays, LabReminderPrefs.CATEGORY_VOICE) }
            .onFailure { _error.value = describe(it) }
        nextWizardPage()
    }

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
