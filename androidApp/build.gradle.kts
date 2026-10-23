import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing material is resolved outside the repository:
//  1. OPENBILL_KEYSTORE_PROPERTIES (path) or ~/.config/openbill/keystore.properties
//  2. CI env vars OPENBILL_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
// If neither is present the release build is produced unsigned.
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun resolveReleaseSigning(): ReleaseSigning? {
    val propsPath = System.getenv("OPENBILL_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.config/openbill/keystore.properties"
    val propsFile = File(propsPath)
    if (propsFile.isFile) {
        val p = Properties().apply { propsFile.inputStream().use { load(it) } }
        val store = p.getProperty("storeFile")
        val sp = p.getProperty("storePassword")
        val alias = p.getProperty("keyAlias")
        val kp = p.getProperty("keyPassword") ?: sp
        if (store != null && sp != null && alias != null && kp != null && File(store).isFile) {
            return ReleaseSigning(File(store), sp, alias, kp)
        }
    }
    val envStore = System.getenv("OPENBILL_STORE_FILE")
    val envSp = System.getenv("OPENBILL_STORE_PASSWORD")
    val envAlias = System.getenv("OPENBILL_KEY_ALIAS")
    val envKp = System.getenv("OPENBILL_KEY_PASSWORD") ?: envSp
    if (!envStore.isNullOrBlank() && File(envStore).isFile &&
        !envSp.isNullOrBlank() && !envAlias.isNullOrBlank() && !envKp.isNullOrBlank()
    ) {
        return ReleaseSigning(File(envStore), envSp, envAlias, envKp)
    }
    return null
}

val releaseSigning = resolveReleaseSigning()

android {
    namespace = "app.openbill.android"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    defaultConfig {
        applicationId = "app.openbill.android"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 4
        versionName = "0.1.3-alpha"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 is intentionally off for 0.1.0: no app-level keep rules exist yet and a
            // minified build cannot be verified here. Enable after a device smoke test.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(libs.androidx.activity.compose)
}
