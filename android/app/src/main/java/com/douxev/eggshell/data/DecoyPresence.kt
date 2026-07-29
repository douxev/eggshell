package com.douxev.eggshell.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the decoy notes app is the thing currently on screen.
 *
 * Exists so the window flags can stop contradicting the cover story. The
 * decoy renders inside the Unlock route, which forces FLAG_SECURE — meaning
 * the "ordinary notes app" refused screenshots and showed a blank card in
 * Recents. No notes app behaves that way, and someone holding the phone sees
 * it immediately. The screen has to look as unremarkable as it claims to be.
 */
@Singleton
class DecoyPresence @Inject constructor() {
    private val _onScreen = MutableStateFlow(false)
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    fun setOnScreen(value: Boolean) { _onScreen.value = value }
}
