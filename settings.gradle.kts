// Standalone build of the Strybok Kotlin Multiplatform SDK.
//
// When the SDK is added to an app as a module (see docs/kotlin-multiplatform.md) this
// file is not used: plugin versions and repositories then come from the app's own settings.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.3.20"
        id("com.android.kotlin.multiplatform.library") version "9.0.1"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "strybok-kmp"
