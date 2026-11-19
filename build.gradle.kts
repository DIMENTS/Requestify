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

/** Who publishes Requestify, as Windows shows it. */
val REQUESTIFY_VENDOR = "DIMENTS"

/** The product's upgrade code: fixed for good, so every release upgrades the one installed before it. */
val REQUESTIFY_UPGRADE_UUID = "5df66373-1641-4856-80f2-f089f5d7fc7c"

/**
 * A local build's version, `0.0.<n>`: a new n every minute, and always above the last local installer built here
 * ([Params.lastPackaged], written by the installer tasks), so installing a fresh local build over an older one is an
 * upgrade and never Windows Installer's error 1638 (same version, other package), also for two builds in one minute.
 * A value source, so the configuration cache asks for it again on every build instead of keeping the first one; it
 * only reads, so a test run in the same minute keeps the cache.
 */
abstract class LocalBuildVersion : ValueSource<String, LocalBuildVersion.Params> {
    interface Params : ValueSourceParameters {
        val lastPackaged: RegularFileProperty
    }

    override fun obtain(): String {
        val byMinute = ((System.currentTimeMillis() / 60_000) % 65_535 + 1).toInt()
        val last = parameters.lastPackaged.get().asFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        val n = when {
            byMinute > last -> byMinute
            last >= 65_535 -> 1
            else -> last + 1
        }
        return "0.0.$n"
    }
}

/** The BUILD number of the last local installer built here (outside git, survives `clean`). */
val lastLocalInstaller: RegularFile = layout.projectDirectory.file(".gradle/requestify-last-local-build.txt")

/**
 * The version to package: `-Prequestify.version=0.2.0` (the release workflow passes the tag's), else a local one.
 * Windows Installer takes MAJOR.MINOR.BUILD with MAJOR and MINOR at most 255 and BUILD at most 65535.
 */
val requestifyVersion: String = providers.gradleProperty("requestify.version").orNull?.also { version ->
    val parts = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,5})""").matchEntire(version)?.groupValues?.drop(1)?.map(String::toInt)
    if (parts == null || parts[0] > 255 || parts[1] > 255 || parts[2] > 65_535) {
        throw GradleException("requestify.version must be MAJOR.MINOR.BUILD (at most 255.255.65535), not \"$version\"")
    }
} ?: providers.of(LocalBuildVersion::class) { parameters.lastPackaged.set(lastLocalInstaller) }.get()

tasks.register("printRequestifyVersion") {
    group = "help"
    description = "Prints the version the installers would get now."
    val version = requestifyVersion
    doLast { println(version) }
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
            packageVersion = requestifyVersion
            vendor = REQUESTIFY_VENDOR
            windows {
                // Per user: no admin prompt, and the updater can install without one. Never switch this between
                // releases: Windows Installer keeps per-user and per-machine installs apart.
                perUserInstall = true
                // Fixed for good: every release is an upgrade of the same product.
                upgradeUuid = REQUESTIFY_UPGRADE_UUID
                menu = true
                menuGroup = "Requestify"
                shortcut = true
                iconFile.set(project.file("packaging/requestify.ico"))
            }
            // From `./gradlew suggestRuntimeModules`. jdk.httpserver is the Spotify login's loopback receiver;
            // without it the installed app cannot log in. jdk.localedata formats Dutch amounts ("€2,50").
            modules("java.instrument", "java.management", "jdk.httpserver", "jdk.localedata", "jdk.unsupported")
        }
    }
}

/*
 * The installers. Compose's own packageMsi/packageExe cannot take jpackage's resource dir, and Requestify's installer
 * needs its own WiX additions (packaging/wix/main.wxs: clean up autostart on uninstall, start after install). So
 * jpackage runs here on the app image Compose builds (createDistributable), with the same settings as above.
 * Output: build/installer/msi/Requestify-<version>.msi and build/installer/exe/Requestify-<version>.exe.
 */
val jpackageExecutable: String = javaToolchains.launcherFor { jetBrainsRuntime() }.get()
    .metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath

fun registerInstallerTask(taskName: String, type: String) = tasks.register<Exec>(taskName) {
    group = "distribution"
    description = "Builds the Requestify $type installer with Requestify's WiX additions."
    dependsOn("createDistributable", "unzipWix")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Requestify")
    val out = layout.buildDirectory.dir("installer/$type")
    val wix = layout.buildDirectory.dir("wix311")
    val resources = layout.projectDirectory.dir("packaging/wix")
    val icon = layout.projectDirectory.file("packaging/requestify.ico")
    inputs.dir(appImage)
    inputs.dir(resources)
    inputs.file(icon)
    inputs.property("version", requestifyVersion)
    outputs.dir(out)
    executable(jpackageExecutable)
    args(
        "--type", type,
        "--app-image", appImage.get().asFile.absolutePath,
        "--name", "Requestify",
        "--app-version", requestifyVersion,
        "--vendor", REQUESTIFY_VENDOR,
        "--description", "Requestify",
        "--about-url", "https://github.com/DIMENTS/Requestify",
        "--win-per-user-install",
        "--win-upgrade-uuid", REQUESTIFY_UPGRADE_UUID,
        "--win-menu",
        "--win-menu-group", "Requestify",
        "--win-shortcut",
        "--icon", icon.asFile.absolutePath,
        "--resource-dir", resources.asFile.absolutePath,
        "--dest", out.get().asFile.absolutePath,
    )
    // jpackage finds WiX on the PATH; Compose unpacks it into build/wix311.
    environment("PATH", wix.get().asFile.absolutePath + File.pathSeparator + providers.environmentVariable("PATH").get())
    doFirst { out.get().asFile.deleteRecursively() }
    // A local build remembers its number: the next one, even within the same minute, goes above it.
    val version = requestifyVersion
    val remember = lastLocalInstaller.asFile
    doLast { if (version.startsWith("0.0.")) remember.apply { parentFile.mkdirs() }.writeText(version.removePrefix("0.0.")) }
}

registerInstallerTask("packageRequestifyMsi", "msi")
registerInstallerTask("packageRequestifyExe", "exe")

// Compose's own installers would lack the WiX additions: point at the ones above instead.
listOf("packageMsi" to "packageRequestifyMsi", "packageExe" to "packageRequestifyExe").forEach { (compose, ours) ->
    // Compose registers its tasks after this script ran: match them by name when they appear.
    tasks.matching { it.name == compose }.configureEach {
        doFirst { throw GradleException("Use .\\gradlew.bat $ours: Requestify's installer needs its own WiX additions.") }
    }
}
