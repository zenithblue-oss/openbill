import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-backup"))
    implementation(compose.desktop.currentOs)
    implementation(libs.pdfbox)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // Sample PDFs from DesktopPdfSamplesTest go here when set (for visual review).
    System.getenv("OPENBILL_PDF_SAMPLES")?.let { systemProperty("openbill.pdfSamples", it) }
}

compose.desktop {
    application {
        mainClass = "app.openbill.desktop.MainKt"

        nativeDistributions {
            // Each format only builds on its own OS (jpackage); the host ignores others.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "OpenBill"
            packageVersion = "1.0.0"
            description = "Offline-first GST billing and accounting"
            vendor = "OpenBill contributors"
            copyright = "Copyright (c) OpenBill contributors"
            licenseFile.set(rootProject.file("LICENSE"))

            // Equivalent of suggestRuntimeModules for this app: SQLite/Room (java.sql),
            // Compose/Skiko native loading (jdk.unsupported), plus logging/crypto/desktop basics.
            modules("java.sql", "jdk.unsupported", "java.naming", "java.management", "jdk.crypto.ec", "java.net.http")

            linux {
                iconFile.set(project.rootProject.file("branding/icons/openbill.png"))
                shortcut = true
                menuGroup = "Office"
                appCategory = "Office"
                debMaintainer = "zenithblue-oss@users.noreply.github.com"
            }
            windows {
                iconFile.set(project.rootProject.file("branding/icons/openbill.ico"))
                menu = true
                menuGroup = "OpenBill"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "6d0b0f4e-3c1a-4f7e-9b52-8a1e47c2d5f3"
            }
        }
    }
}
