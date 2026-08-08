# ─────────────────────────────────────────────────────────────────────────────
# Eggshell — R8 / ProGuard rules for the release build.
# ─────────────────────────────────────────────────────────────────────────────
# Tighter shrinking happens via proguard-android-optimize.txt (referenced from
# build.gradle.kts). This file only adds keep-rules for libraries that touch
# JNI / reflection / native symbol lookups and would otherwise be silently
# stripped (and crash at runtime).
#
# Whenever you add a new library, smoke-test the RELEASE APK on a device,
# not just the debug. Debug is unobfuscated and won't surface stripping bugs.

# ── UniFFI / JNA ─────────────────────────────────────────────────────────────
# JNA loads .so files by reflection and binds to interface classes. Renaming
# them breaks every FFI call (the vault stops opening).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
# JNA's Native$AWT helper references java.awt.{Component,Window,…} that don't
# exist on Android. Those code paths are gated on AWT availability at runtime
# and never fire here — R8 just needs us to confirm we know about the gap.
-dontwarn java.awt.**
-dontwarn com.sun.jna.Native$AWT
# Generated UniFFI Kotlin bindings — their classes are the FFI surface and
# must keep their exact symbol names for the native side to dlsym() them.
-keep class uniffi.** { *; }
# UniFFI declares callback interfaces by name from Rust → Kotlin; keep the
# interface metadata so the runtime can find them.
-keepclassmembers class * implements uniffi.** { *; }

# ── (Opt-in) Obfuscate the UniFFI *data model* class names ──────────────────
# The broad `-keep class uniffi.**` above leaves the whole domain model
# readable in the DEX (uniffi.transition.Medication, Vault, DoseSchedule,
# Journal…), which is a forensic-comprehension leak. Most of those are plain
# Kotlin data classes that R8 *could* rename; only the JNA-touched FFI plumbing
# (RustBuffer/ForeignBytes structures, the `_UniFFILib` JNA interface, the
# top-level FFI functions, and callback interfaces) truly needs stable names.
#
# To narrow it, REPLACE the two `-keep` rules above with the block below — but
# only with on-device testing, because an over-aggressive rename here surfaces
# as runtime FFI crashes (UnsatisfiedLinkError / NPE in an FfiConverter) that
# CANNOT be caught at compile time. Left disabled by default for that reason.
#
#   -keep class uniffi.**$Companion { *; }
#   -keep,allowobfuscation class uniffi.** { *; }
#   -keepclassmembers class uniffi.** {
#       <init>(...);
#       public static ** INSTANCE;
#   }
#   # JNA maps these by reflection on field names/order — never rename them:
#   -keep class * extends com.sun.jna.Structure { *; }
#   -keep interface uniffi.**Lib { *; }
#   -keepclassmembers class * implements uniffi.** { *; }

# ── Tesseract4Android (adaptech-cz fork) ────────────────────────────────────
# JNI bridge to libtesseract.so + libleptonica.so. The native side looks up
# Java classes + method signatures by exact name through JNI, so anything in
# com.googlecode.tesseract.android and com.googlecode.leptonica.android MUST
# keep its identifiers and members intact through obfuscation.
-keep class com.googlecode.tesseract.android.** { *; }
-keepclassmembers class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keepclassmembers class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-dontwarn com.googlecode.leptonica.android.**

# ── PDFBox-Android (com.tom-roush:pdfbox-android) ───────────────────────────
# PDFBox uses reflection for font fallback + ICC profile loading; reflective
# class lookups must survive obfuscation. The library also ships AWT shims
# referencing java.awt.* (same flavour as JNA above) — keep dontwarn there
# so R8 doesn't fail on those phantom classes.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
# Generated _HiltModules.java and the like rely on reflection for module
# discovery — the default android.txt rules cover most, but the warning
# suppressors above + KSP-generated symbols stay.

# ── AndroidX Biometric / Keystore ───────────────────────────────────────────
# No blanket keep for androidx.biometric.
#
# BiometricPrompt does load internal classes reflectively on some OEMs, which
# is why a keep was here at all — but the library ships 32 rules of its own for
# exactly that, keeping the method *names* on its Api* inner classes with
# `allowobfuscation, allowshrinking`. Ours restated them less precisely and
# took the shrinking away, holding 167 classes out of R8's reach.
#
# There is no CameraX section below any more: the dependency is gone. It was
# declared, force-kept by a blanket rule, and imported by nothing — see the
# commit that removed it.

# ── AndroidX EXIFInterface ──────────────────────────────────────────────────
-keep class androidx.exifinterface.media.ExifInterface { *; }

# ── Kotlinx coroutines internals ────────────────────────────────────────────
# Debug agent classes are referenced by name when present; suppress the
# missing-class warning on release where the debug variant isn't shipped.
-dontwarn kotlinx.coroutines.debug.**

# ── Reorderable (sh.calvin.reorderable) ─────────────────────────────────────
-dontwarn sh.calvin.reorderable.**

# ── Compose-time reflection (preview tooling) ───────────────────────────────
# Tooling classes referenced by debugImplementation aren't on the release
# classpath, but Compose's own runtime checks for their presence by name.
-dontwarn androidx.compose.ui.tooling.**
