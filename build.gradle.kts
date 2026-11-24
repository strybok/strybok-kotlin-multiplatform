import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

group = "com.strybok"
version = "0.2.0"

val coroutinesVersion = "1.10.2"
val serializationVersion = "1.9.0"

kotlin {
    explicitApi()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    androidLibrary {
        namespace = "com.strybok.kmp"
        compileSdk = 36
        minSdk = 21
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withHostTestBuilder {}
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // `jvmCommon` holds the HttpURLConnection transport shared by the JVM and Android targets.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.target.name == "android" }
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }
        androidMain.dependencies {
            // Provides Dispatchers.Main on Android.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
        }
    }
}

// Publishing to Maven Central. Credentials and the signing key come from Gradle properties
// (ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password, ...signingInMemoryKey / ...KeyPassword),
// which only the release workflow has; without them `publishToMavenLocal` still works, unsigned.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("com.strybok", "strybok-kmp", version.toString())
    pom {
        name.set("Strybok Kotlin Multiplatform SDK")
        description.set("Receives iOS Universal Links and Android App Links, resolves them through the Strybok API to an in-app route and payload, and delivers each link exactly once.")
        url.set("https://strybok.com")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("strybok")
                name.set("Strybok")
                url.set("https://strybok.com")
            }
        }
        scm {
            url.set("https://github.com/strybok/strybok-kotlin-multiplatform")
            connection.set("scm:git:https://github.com/strybok/strybok-kotlin-multiplatform.git")
            developerConnection.set("scm:git:ssh://git@github.com/strybok/strybok-kotlin-multiplatform.git")
        }
    }
}
