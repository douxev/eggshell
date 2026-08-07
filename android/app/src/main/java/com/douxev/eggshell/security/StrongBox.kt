package com.douxev.eggshell.security

import android.os.Build
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi

/**
 * Whether [t] is the Keystore's "this device has no StrongBox" signal.
 *
 * `StrongBoxUnavailableException` only exists from API 28. Both key-generation
 * fallbacks used to test `t is StrongBoxUnavailableException` with no version
 * guard at all, which asks ART to resolve a class that is simply not present on
 * Android 8.0 and 8.1 — and `minSdk` is 26, so those are real users. The place
 * it would have surfaced is the worst possible one: inside the `getOrElse` whose
 * entire job is to recover from a failed key generation, so the fallback that
 * exists to save the vault would itself have been the thing that broke.
 *
 * Nothing below API 28 can ever raise it either, since `setIsStrongBoxBacked`
 * is already gated on the same version — so the guard costs nothing and the
 * answer is `false` exactly when it should be.
 *
 * The class reference is isolated in its own `@RequiresApi` function rather than
 * written inline behind an `if`: that way the type is resolved only when a
 * device that actually has it calls in, instead of when the enclosing method is
 * verified.
 */
internal fun isStrongBoxUnavailable(t: Throwable): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && matchesStrongBoxUnavailable(t)

@RequiresApi(Build.VERSION_CODES.P)
private fun matchesStrongBoxUnavailable(t: Throwable): Boolean =
    t is StrongBoxUnavailableException
