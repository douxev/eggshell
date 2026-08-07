import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // No `kotlin.android` here: AGP 9 has built-in Kotlin support and refuses
    // to run alongside the standalone plugin. The Compose compiler plugin is a
    // separate compiler plugin and is still applied on its own.
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

// Single source of truth for the ABIs this build targets — read by BOTH the
// packaging filter and the cargo-ndk task below. They used to disagree:
// `-PdevAbi` shrank the APK but cargoBuildRust still compiled all three
// targets, so the flag never actually saved any Rust build time (which is the
// expensive half — rusqlite vendors OpenSSL + SQLCipher per target).
//
// Accepts a comma-separated list:
//   ./gradlew installDebug   -PdevAbi=arm64-v8a                   fast local install
//   ./gradlew assembleRelease -PdevAbi=arm64-v8a,armeabi-v7a      CI: phones only
//
// The default keeps all three, because the Play AAB is built locally from it
// and x86_64 is what ChromeOS / x86 Chromebooks run — dropping it there would
// silently cut those users off.
val targetAbis: List<String> =
    (project.findProperty("devAbi") as String?)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "com.douxev.eggshell"
    compileSdk = 37
    // Pin to the same NDK that cargo-ndk uses to build the Rust .so files.
    // Without this, AGP picks whichever NDK is newest on the machine
    // (currently 30.0.x) and its strip/objcopy can't process our libs
    // built with 27.2.x — the "Unable to strip" warning + the empty
    // native-symbol-tables extraction both come from that mismatch.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.douxev.eggshell"
        minSdk = 26
        targetSdk = 36
        // Reminder for the next release: Play enforces strictly monotonic
        // versionCode across all tracks. Bump versionCode every upload,
        // even for a same-day re-build, otherwise Play refuses the AAB.
        versionCode = 21
        versionName = "2.3.1"

        ndk {
            abiFilters += targetAbis
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
    // AGP 9 owns the Kotlin compilation, so the old `kotlinOptions` block is
    // gone; the target is set through the Kotlin compiler options instead.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
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
            // Generated UniFFI Kotlin bindings. Two things changed with AGP 9:
            // it rejects a Provider here (it cannot tell a generated read-only
            // directory from a source one), and since it compiles Kotlin itself
            // the `java` source set no longer feeds the Kotlin compilation —
            // the directory has to be registered on `kotlin` too, or every
            // `uniffi.*` reference fails to resolve.
            val uniffiGenerated =
                layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile
            java.srcDirs(uniffiGenerated)
            kotlin.srcDirs(uniffiGenerated)
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

val cargoBuildRust = tasks.register<Exec>("cargoBuildRust") {
    group = "rust"
    description = "Build the transition-uniffi cdylib for all Android ABIs via cargo-ndk"

    workingDir = coreDir.asFile
    commandLine(
        buildList {
            addAll(listOf("cargo", "ndk"))
            targetAbis.forEach { addAll(listOf("-t", it)) }
            addAll(listOf("-o", jniLibsDir.asFile.absolutePath))
            addAll(listOf("build", "--release", "-p", "transition-uniffi"))
        }
    )

    // Without this the task stays UP-TO-DATE across an ABI-set change and
    // silently ships jniLibs from the previous target list.
    inputs.property("abis", targetAbis)
    inputs.dir(coreDir.dir("transition-core/src"))
    inputs.dir(coreDir.dir("transition-uniffi/src"))
    inputs.file(coreDir.file("transition-uniffi/Cargo.toml"))
    inputs.file(coreDir.file("transition-core/Cargo.toml"))
    inputs.file(coreDir.file("Cargo.toml"))
    outputs.dir(jniLibsDir)
}

val cargoBuildRustHost = tasks.register<Exec>("cargoBuildRustHost") {
    group = "rust"
    description = "Build a host cdylib so uniffi-bindgen can read metadata from it"

    workingDir = coreDir.asFile
    commandLine("cargo", "build", "-p", "transition-uniffi")

    inputs.dir(coreDir.dir("transition-core/src"))
    inputs.dir(coreDir.dir("transition-uniffi/src"))
    outputs.file(coreDir.file("target/debug/libtransition_uniffi.so"))
}

val uniffiGenerateBindings = tasks.register<Exec>("uniffiGenerateBindings") {
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

// AGP 9 compiles Kotlin itself, and its compile tasks no longer sit behind
// `preBuild` the way the old Kotlin plugin's did — hooking there alone left the
// generated UniFFI bindings absent at compile time. Depend on the generator
// from the compile tasks directly, which is ordering that holds whichever
// plugin owns the compilation.
tasks.matching {
    it.name.startsWith("compile") &&
        (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac"))
}.configureEach {
    dependsOn(uniffiGenerateBindings)
}

// The native libraries only have to exist by the time they are merged.
tasks.matching { it.name.startsWith("merge") && it.name.contains("NativeLibs") }
    .configureEach { dependsOn(cargoBuildRust) }

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

    // UniFFI runtime — JNA powers the Kotlin bindings
    implementation(libs.jna) { artifact { type = "aar" } }

    // Decoy notes app: long-press drag-to-reorder for the LazyVerticalGrid.
    implementation(libs.reorderable)

    // Notes: markdown rendering. The core artifact has no image loader — that
    // is a separate -coil3 module we deliberately do NOT take, because note
    // images are encrypted blobs we decrypt ourselves and no note content
    // should ever be fetched over a network.
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    // AppCompat — needed for AppCompatDelegate.setApplicationLocales (runtime
    // language switching with persistence across cold starts).
    implementation(libs.androidx.appcompat)

    // No net.zetetic:sqlcipher-android here: the vault is opened entirely from
    // Rust, whose rusqlite is built with `bundled-sqlcipher-vendored-openssl`,
    // so SQLCipher is already linked *inside* libtransition_uniffi.so. The AAR
    // was a leftover from an early phase that never got wired up, and it was
    // shipping a second, unused 6.2 MB libsqlcipher.so per ABI.

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
