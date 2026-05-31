pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — hosts Tesseract4Android (adaptech-cz/Tesseract4Android)
        // and a handful of other community-maintained Android libs that
        // don't publish to Maven Central. Scoped to the adaptech-cz user +
        // its sub-modules (JitPack publishes multi-module projects as
        // groupId `com.github.adaptech-cz.Tesseract4Android` for the
        // children — `includeGroupAndSubgroups` covers both shapes).
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupAndSubgroups("com.github.adaptech-cz")
            }
        }
    }
}

rootProject.name = "Transition"
include(":app")
