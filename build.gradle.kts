plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

group = "com.watchoutrf.desktop"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna:5.14.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Unit tests (DSP + export logic)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.watchoutrf.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Pkg)
            packageName = "WatchoutRF"
            packageVersion = "1.0.0"
            description = "RF spectrum analyzer for RTL-SDR dongles"

            // Ship the whole JDK runtime: coroutines, JNA and Swing's file dialog need
            // modules that jlink's automatic detection does not always pick up.
            includeAllModules = true

            // librtlsdr + libusb are bundled here (see scripts/bundle_native_libs.sh)
            // and found at runtime through compose.application.resources.dir.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            macOS {
                bundleID = "com.watchoutrf.desktop"
            }
        }
    }
}

// Copy librtlsdr/libusb from Homebrew into resources/ before the app is assembled,
// so the packaged app does not depend on Homebrew being installed.
val bundleNativeLibs by tasks.registering(Exec::class) {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    commandLine("bash", "scripts/bundle_native_libs.sh")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(bundleNativeLibs)
}
