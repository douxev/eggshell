package com.douxev.eggshell.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Tap-anywhere-outside-an-input dismisses the soft keyboard.
 *
 * Apply to a screen's root container (typically the Column inside Scaffold's body).
 * Children with their own click handlers (Button, Switch, Slider, TextField) consume
 * their taps first; this modifier only fires on empty space / non-clickable widgets.
 *
 * Skip on PIN / unlock screens where the keyboard should stay open until submission.
 */
@Composable
fun Modifier.clickToDismissKeyboard(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
    ) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
}
