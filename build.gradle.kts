import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * JetBrains Runtime 21 compiles, tests, runs and is bundled with the app: its API draws the custom title bar.
 * The foojay resolver (settings.gradle.kts) downloads it when it isn't installed.
 */
fun JavaToolchainSpec.jetBrainsRuntime() {
    languageVersion = JavaLanguageVersion.of(21)
    vendor = JvmVendorSpec.JETBRAINS
}

kotlin {
    jvmToolchain { jetBrainsRuntime() }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.material3)
            implementation(libs.materialkolor)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.reorderable)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
            implementation(libs.ktor.client.cio)
            implementation(libs.jna.platform)
            implementation(libs.jbr.api)
            runtimeOnly(libs.slf4j.nop)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

compose.resources {
    packageOfResClass = "nl.diments.requestify.resources"
    publicResClass = false
}

compose.desktop {
    application {
        mainClass = "nl.diments.requestify.MainKt"
        // `run` and the packaged app use the same JetBrains Runtime as the toolchain.
        javaHome = javaToolchains.launcherFor { jetBrainsRuntime() }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Requestify"
            packageVersion = "0.1.0"
            // From `./gradlew suggestRuntimeModules`. jdk.httpserver is the Spotify login's loopback receiver;
            // without it the installed app cannot log in. jdk.localedata formats Dutch amounts ("€2,50").
            modules("java.instrument", "java.management", "jdk.httpserver", "jdk.localedata", "jdk.unsupported")
        }
    }
}
