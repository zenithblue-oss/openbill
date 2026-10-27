import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

// CHANGELOG.md bundled as a Kotlin constant for the in-app "What's new" dialog.
val generateChangelog by tasks.registering {
    val source = rootProject.file("CHANGELOG.md")
    val outDir = layout.buildDirectory.dir("generated/changelog")
    inputs.file(source)
    outputs.dir(outDir)
    doLast {
        val text = source.readText().replace("\r", "").replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("$", "\\$").replace("\n", "\\n")
        val out = outDir.get().file("app/openbill/ui/settings/Changelog.kt").asFile
        out.parentFile.mkdirs()
        out.writeText("package app.openbill.ui.settings\n\n// Generated from CHANGELOG.md by :core-ui:generateChangelog.\ninternal const val CHANGELOG: String = \"$text\"\n")
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        all {
            // Stock M3 components such as TopAppBar scroll behaviours and DatePicker are still
            // marked experimental; the design system opts in once here.
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        }
        commonMain { kotlin.srcDir(generateChangelog) }
        commonMain.dependencies {
            api(project(":core-model"))
            api(project(":core-domain"))
            api(project(":core-printing"))
            implementation(libs.kotlinx.datetime)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.animation)
            implementation(libs.compose.material.icons.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.zxing.core)
            // Barcode scanning: CameraX frames decoded by ZXing (no ML Kit / Play Services).
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.activity.compose) // camera permission request
        }
    }
}

android {
    namespace = "app.openbill.ui"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
