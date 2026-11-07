import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

// CHANGELOG.md and docs/help.md bundled as Kotlin constants for "What's new" and the offline help.
val generateChangelog by tasks.registering {
    val sources = mapOf("CHANGELOG" to rootProject.file("CHANGELOG.md"), "HELP" to rootProject.file("docs/help.md"))
    val outDir = layout.buildDirectory.dir("generated/changelog")
    inputs.files(sources.values)
    outputs.dir(outDir)
    doLast {
        sources.forEach { (name, source) ->
            val text = source.readText().replace("\r", "").replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("\n", "\\n")
            val out = outDir.get().file("app/openbill/ui/settings/${name.lowercase().replaceFirstChar { it.uppercase() }}.kt").asFile
            out.parentFile.mkdirs()
            out.writeText("package app.openbill.ui.settings\n\n// Generated from ${source.name} by :core-ui:generateChangelog.\ninternal const val $name: String = \"$text\"\n")
        }
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
