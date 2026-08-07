package com.douxev.eggshell.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.fragment.app.FragmentActivity

/**
 * The activity hosting this composition, as the [FragmentActivity] that
 * `BiometricPrompt` requires.
 *
 * The three screens that raise a biometric prompt used to reach it with
 * `LocalContext.current as FragmentActivity`. That happens to work here — the
 * app has one activity and it is an `AppCompatActivity` — but it is a cast
 * across an abstraction that gives no such guarantee: `LocalContext` is only
 * promised to be a `Context`, and inside a dialog, a popup or a preview it is a
 * wrapper that is not an Activity at all. The failure mode is a
 * `ClassCastException` on the unlock screen, which is the one screen that has
 * to work.
 *
 * `LocalActivity` (androidx.activity 1.10+) unwraps the context chain properly
 * and is the supported way to ask this question.
 */
@Composable
@ReadOnlyComposable
fun currentFragmentActivity(): FragmentActivity =
    LocalActivity.current as? FragmentActivity
        ?: error("eggshell screens must be hosted by a FragmentActivity (BiometricPrompt needs one)")
