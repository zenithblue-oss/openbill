import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Sync protocol (docs/10-server.md section 6): operation envelope and the device outbox.
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
            api(project(":core-database"))
            // Shared posting code: derived caches are recomputed after applying ops (docs/10-server.md 6.2.2).
            api(project(":core-data"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The in-process server and snapshot files are JVM (the S1 server runs on the JVM).
        val desktopMain by getting {
            dependencies {
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.androidx.sqlite.bundled)
            }
        }
    }
}

// Simulation knobs (docs/10-server.md 16): -Popenbill.sim.seeds=500 -Popenbill.sim.steps=400, one seed -Popenbill.sim.seed=<n>, -Popenbill.sim.keep=<dir>.
tasks.withType<Test>().configureEach {
    listOf("openbill.sim.seed", "openbill.sim.seeds", "openbill.sim.steps", "openbill.sim.keep").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

android {
    namespace = "app.openbill.core.sync"
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
