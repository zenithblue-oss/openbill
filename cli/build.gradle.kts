import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// `openbill` command-line client, direct mode (docs/11-cli.md). No UI dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

version = "0.1.3"

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-backup"))
    implementation(project(":core-printing"))
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    applicationName = "openbill"
    mainClass.set("app.openbill.cli.MainKt")
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    // ./gradlew :cli:test -PupdateGoldens rewrites src/test/resources/golden for review.
    systemProperty("openbill.goldenDir", file("src/test/resources/golden").absolutePath)
    if (project.hasProperty("updateGoldens")) systemProperty("openbill.updateGoldens", "true")
}
