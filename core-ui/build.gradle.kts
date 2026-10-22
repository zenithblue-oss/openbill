import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
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
        commonMain.dependencies {
            api(project(":core-model"))
            api(project(":core-domain"))
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
        // Local QR generation for UPI payment codes on invoices (both targets are JVM).
        androidMain.dependencies {
            implementation(libs.zxing.core)
        }
        val desktopMain by getting {
            // PDF fonts (Noto Sans, OFL) on the desktop classpath under fonts/.
            resources.srcDir("pdf-assets")
            dependencies {
                implementation(libs.zxing.core)
            }
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

    // The same Noto Sans files as desktop, read from assets/fonts/ by the PDF painter.
    sourceSets["main"].assets.srcDir("pdf-assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
