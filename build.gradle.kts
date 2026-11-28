import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

group = "com.strybok"
version = "0.5.0"

val coroutinesVersion = "1.10.2"
val serializationVersion = "1.9.0"

// The shared test vectors of the SDK contract (the byte-identical copy in
// src/commonTest/resources) as a Kotlin constant of the common tests: Kotlin/Native test
// binaries have no resources, so this is how the JVM, Android host and iOS simulator tests all
// read the very same text.
val generateParameterLinkVectors by tasks.registering {
    val source = layout.projectDirectory.file("src/commonTest/resources/parameter-links-vectors.json")
    val output = layout.buildDirectory.dir("generated/parameterLinkVectors/kotlin")
    inputs.file(source)
    outputs.dir(output)
    doLast {
        val text = source.asFile.readText(Charsets.UTF_8)
        // Plain string literals, every character escaped where Kotlin could read it otherwise.
        val chunks = text.chunked(4096).joinToString(",\n") { chunk ->
            val literal = buildString {
                for (char in chunk) {
                    when {
                        char == '\\' -> append("\\\\")
                        char == '"' -> append("\\\"")
                        char == '$' -> append("\\\$")
                        char == '\n' -> append("\\n")
                        char.code in 0x20..0x7E -> append(char)
                        else -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    }
                }
            }
            "    \"$literal\""
        }
        val file = output.get().file("com/strybok/kmp/ParameterLinkVectorsJson.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Generated from src/commonTest/resources/parameter-links-vectors.json by the Gradle task
            |// generateParameterLinkVectors. Do not edit.
            |package com.strybok.kmp
            |
            |/** The text of parameter-links-vectors.json, unchanged. */
            |internal val PARAMETER_LINK_VECTORS_JSON: String = listOf(
            |$chunks,
            |).joinToString("")
            |
            """.trimMargin(),
        )
    }
}

kotlin {
    explicitApi()
    // `PlatformContext` is an expect class (android.content.Context on Android).
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    androidLibrary {
        namespace = "com.strybok.kmp"
        compileSdk = 36
        minSdk = 21
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withHostTestBuilder {}
        // Shipped inside the AAR: see the file for why the SDK needs a rule at all.
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
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
        commonTest {
            kotlin.srcDir(generateParameterLinkVectors)
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            }
        }
        androidMain.dependencies {
            // Provides Dispatchers.Main on Android.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
            // Only PlayIntegrityTokenProvider uses it, and only to compile: the published
            // artifact does not depend on Google's libraries. An app that wants attestation on
            // Android adds the library itself.
            compileOnly("com.google.android.play:integrity:1.4.0")
            // Only the install referrer sources of deferred links use these two, and only to
            // compile: an app that wants a source adds its store's library itself.
            compileOnly("com.android.installreferrer:installreferrer:2.2")
            compileOnly("store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0")
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
