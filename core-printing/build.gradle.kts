import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Invoice document model, layout engine and templates (no Compose), plus the PDFBox painter on
// desktop. Shared by core-ui, desktopApp and the headless CLI (docs/03 module rules).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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
        commonMain.dependencies {
            api(project(":core-domain"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.zxing.core)
        }
        val desktopMain by getting {
            // PDF fonts (Noto Sans, OFL) on the classpath under fonts/.
            resources.srcDir("pdf-assets")
            dependencies {
                implementation(libs.zxing.core)
                api(libs.pdfbox)
            }
        }
    }
}

android {
    namespace = "app.openbill.printing"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    // The same Noto Sans files as desktop, read from assets/fonts/ by the Android PDF painter.
    sourceSets["main"].assets.srcDir("pdf-assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
