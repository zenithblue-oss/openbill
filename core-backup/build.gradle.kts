import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Backup archive writer/reader and atomic restore (docs/03-architecture.md section 14).
// Plain JVM so both the Android app and the desktop/CLI share it; no Room dependency.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
