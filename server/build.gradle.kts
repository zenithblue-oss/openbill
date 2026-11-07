import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// OpenBill Server (docs/10-server.md): sync and backup hub. Ktor on Netty, SQLite per workspace,
// the same sync rules and database code as the apps (core-sync, core-data).
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

version = providers.gradleProperty("openbill.version").get()

dependencies {
    implementation(project(":core-sync"))
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.network.tls.certificates)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.okhttp)
}

application {
    applicationName = "openbill-server"
    mainClass.set("app.openbill.server.MainKt")
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

// One runnable file: `java -jar openbill-server-<version>.jar` (docs/10-server.md section 3).
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Self-contained server JAR"
    archiveBaseName.set("openbill-server")
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "app.openbill.server.MainKt", "Implementation-Version" to project.version)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "META-INF/INDEX.LIST", "module-info.class", "META-INF/versions/*/module-info.class")
    }
}

tasks.assemble { dependsOn(fatJar) }
