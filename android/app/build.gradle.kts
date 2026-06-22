import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load the release-signing credentials from <repo>/keystores/keystore.properties
// if it exists. The file is git-ignored — see keystores/keystore.properties.example
// for the template. CI can supply the same values via env vars instead (handy for
// signed builds in GitHub Actions without ever shipping the .jks).
val keystorePropsFile = rootProject.file("../keystores/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(propName: String, envName: String): String? =
    keystoreProps.getProperty(propName) ?: System.getenv(envName)

android {
    namespace = "com.douxev.eggshell"
    compileSdk = 35
    // Pin to the same NDK that cargo-ndk uses to build the Rust .so files.
    // Without this, AGP picks whichever NDK is newest on the machine
    // (currently 30.0.x) and its strip/objcopy can't process our libs
    // built with 27.2.x — the "Unable to strip" warning + the empty
    // native-symbol-tables extraction both come from that mismatch.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.douxev.eggshell"
        minSdk = 26
        targetSdk = 35
        // Reminder for the next release: Play enforces strictly monotonic
        // versionCode across all tracks. Bump versionCode every upload,
        // even for a same-day re-build, otherwise Play refuses the AAB.
        versionCode = 10
        versionName = "0.1.0"

        ndk {
            // Limit ABIs to common phone architectures; can extend to x86 for emulators.
            // For fast local installs over (slow) wireless adb, package a single ABI:
            //   ./gradlew installDebug -PdevAbi=arm64-v8a
            // Default (no flag) bundles all three so release AABs stay complete.
            val devAbi = (project.findProperty("devAbi") as String?)?.takeIf { it.isNotBlank() }
            if (devAbi != null) {
                abiFilters += devAbi
            } else {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("storeFile", "EGGSHELL_KEYSTORE")
            val storePass = signingValue("storePassword", "EGGSHELL_KEYSTORE_PASSWORD")
            val alias = signingValue("keyAlias", "EGGSHELL_KEY_ALIAS")
            val keyPass = signingValue("keyPassword", "EGGSHELL_KEY_PASSWORD")
            if (storeFilePath != null && storePass != null && alias != null && keyPass != null) {
                // Resolve relative paths against the repo root so the same
                // `storeFile=keystores/eggshell-upload.jks` works from both
                // `./gradlew` (cwd = android/) and CI checkouts.
                storeFile = rootProject.file("../$storeFilePath")
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
            // If unset, the signing block is incomplete and Gradle will fall
            // back to "no signing" for release — which produces an unsigned
            // AAB Play Store will refuse. We surface this loudly below.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Extract function-name tables from every .so we bundle (our
            // Rust core, SQLCipher, Tesseract, leptonica, JNA, …) and
            // pack them inside the AAB's BUNDLE-METADATA section. Play
            // uses them to symbolicate native crashes + ANRs; clients
            // never download these symbol files.
            // FULL would add DWARF line numbers (+ ~50 MB to the AAB);
            // SYMBOL_TABLE is the sweet spot — readable stack frames
            // without bloating the bundle.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            // Only attach the signing config if we actually loaded credentials.
            // Otherwise `./gradlew assembleDebug` (which doesn't need them)
            // still works without keystore.properties on disk.
            val rc = signingConfigs.findByName("release")
            if (rc?.storeFile != null) signingConfig = rc
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures {
        compose = true
        // AGP 8+ no longer generates BuildConfig by default; we need it for
        // versionCode comparisons (e.g. the what's-new sheet).
        buildConfig = true
    }

    sourceSets {
        named("main") {
            // jniLibs are populated by the cargoBuildRust task below
            jniLibs.srcDirs("src/main/jniLibs")
            // Generated UniFFI Kotlin bindings
            java.srcDirs(layout.buildDirectory.dir("generated/uniffi/kotlin"))
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

// ---------------------------------------------------------------------------
// Rust ↔ Android plumbing — keep this tight; the goal is to avoid the archived
// org.mozilla.rust-android-gradle plugin and stay on cargo-ndk + uniffi-bindgen
// directly. See plan section "Build chain Android (sans plugin magique)".
// ---------------------------------------------------------------------------

val coreDir = layout.projectDirectory.dir("../../core")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val uniffiKotlinOutDir = layout.buildDirectory.dir("generated/uniffi/kotlin")

val cargoBuildRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build the transition-uniffi cdylib for all Android ABIs via cargo-ndk"

    workingDir = coreDir.asFile
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release", "-p", "transition-uniffi"
    )

    inputs.dir(coreDir.dir("transition-core/src"))
    inputs.dir(coreDir.dir("transition-uniffi/src"))
    inputs.file(coreDir.file("transition-uniffi/Cargo.toml"))
    inputs.file(coreDir.file("transition-core/Cargo.toml"))
    inputs.file(coreDir.file("Cargo.toml"))
    outputs.dir(jniLibsDir)
}

val cargoBuildRustHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build a host cdylib so uniffi-bindgen can read metadata from it"

    workingDir = coreDir.asFile
    commandLine("cargo", "build", "-p", "transition-uniffi")

    inputs.dir(coreDir.dir("transition-core/src"))
    inputs.dir(coreDir.dir("transition-uniffi/src"))
    outputs.file(coreDir.file("target/debug/libtransition_uniffi.so"))
}

val uniffiGenerateBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate Kotlin bindings from the transition-uniffi cdylib"
    dependsOn(cargoBuildRustHost)

    doFirst { uniffiKotlinOutDir.get().asFile.mkdirs() }

    workingDir = coreDir.asFile
    commandLine(
        "cargo", "run",
        "-p", "transition-uniffi",
        "--features", "cli-bindgen",
        "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", "target/debug/libtransition_uniffi.so",
        "--language", "kotlin",
        "--out-dir", uniffiKotlinOutDir.get().asFile.absolutePath
    )

    inputs.file(coreDir.file("transition-uniffi/src/transition.udl"))
    inputs.file(coreDir.file("target/debug/libtransition_uniffi.so"))
    outputs.dir(uniffiKotlinOutDir)
}

tasks.named("preBuild") {
    dependsOn(cargoBuildRust, uniffiGenerateBindings)
}

// ---------------------------------------------------------------------------

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.hilt)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // UniFFI runtime — JNA powers the Kotlin bindings
    implementation(libs.jna) { artifact { type = "aar" } }

    // Decoy notes app: long-press drag-to-reorder for the LazyVerticalGrid.
    implementation(libs.reorderable)

    // AppCompat — needed for AppCompatDelegate.setApplicationLocales (runtime
    // language switching with persistence across cold starts).
    implementation(libs.androidx.appcompat)

    // SQLCipher native lib (will be wired up in Phase 1)
    implementation(libs.sqlcipher.android)

    // Lab-result import — two-stage strategy:
    //  1. Most lab PDFs (Cerba, Biogroup, Synlab…) ship with an embedded
    //     text layer. PDFBox-Android extracts it losslessly without any
    //     OCR pass — fast, accurate, no model files needed.
    //  2. When extraction yields nothing (scanned PDF, image input),
    //     we fall back to Tesseract OCR. Languages bundled: fra + eng.
    // Both libraries are pure FOSS (BSD / Apache 2.0) — no proprietary
    // blobs, so the app stays clean of F-Droid anti-features.
    implementation(libs.pdfbox.android)
    implementation(libs.tesseract4android)

    // EXIF stripping on photo import so GPS / camera-model tags don't end up
    // in the encrypted blob (and consequently leak out via share / gallery).
    implementation(libs.androidx.exifinterface)
}
