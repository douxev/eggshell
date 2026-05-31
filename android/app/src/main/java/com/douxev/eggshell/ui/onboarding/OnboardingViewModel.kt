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
import com.douxev.eggshell.data.VaultRepository
import com.douxev.eggshell.security.VaultPrefs

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: VaultRepository,
) : ViewModel() {

    sealed interface Step {
        data object Welcome : Step
        data object PickMode : Step
        data class SetupPassphrase(val mode: VaultPrefs.Mode) : Step
        data object Confirming : Step
        data object Done : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Welcome)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun next() {
        _error.value = null
        _step.value = when (val s = _step.value) {
            is Step.Welcome -> Step.PickMode
            else -> s
        }
    }

    fun back() {
        _error.value = null
        _step.value = when (val s = _step.value) {
            is Step.PickMode -> Step.Welcome
            is Step.SetupPassphrase -> Step.PickMode
            else -> s
        }
    }

    fun pickMode(mode: VaultPrefs.Mode, activity: FragmentActivity, biometricCopy: VaultRepository.BiometricCopy) {
        _error.value = null
        when (mode) {
            VaultPrefs.Mode.KEYSTORE_ONLY -> {
                _step.value = Step.Confirming
                viewModelScope.launch {
                    runCatching { repo.initializeKeystoreOnly() }
                        .onSuccess { _step.value = Step.Done }
                        .onFailure { fail(it) }
                }
            }
            VaultPrefs.Mode.KEYSTORE_BIOMETRIC -> {
                _step.value = Step.Confirming
                viewModelScope.launch {
                    runCatching { repo.initializeKeystoreBiometric(activity, biometricCopy) }
                        .onSuccess { _step.value = Step.Done }
                        .onFailure { fail(it) }
                }
            }
            VaultPrefs.Mode.KEYSTORE_PASSPHRASE,
            VaultPrefs.Mode.PARANOID -> {
                _step.value = Step.SetupPassphrase(mode)
            }
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
                .onSuccess { _step.value = Step.Done }
                .onFailure { fail(it) }
        }
    }

    private fun fail(t: Throwable) {
        // Include the exception class name — for biometric setup failures the
        // bare message is often null or one cryptic word, and the class tells
        // us exactly what went wrong (KeyPermanentlyInvalidatedException,
        // UserNotAuthenticatedException, ProviderException, …).
        _error.value = "${t::class.java.simpleName}: ${t.message ?: "no detail"}"
        _step.value = Step.PickMode
    }
}
