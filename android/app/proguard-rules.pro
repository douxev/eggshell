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

# ── SQLCipher (net.zetetic:sqlcipher-android) ───────────────────────────────
# Native JNI entry points must keep their exact class + method names.
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.sqlcipher.**

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
# BiometricPrompt has internal classes loaded by reflection on some OEMs.
-keep class androidx.biometric.** { *; }

# ── CameraX ─────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

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
