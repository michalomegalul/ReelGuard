import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional password hash baked into the APK (see README); read from the git-ignored local.properties.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}

// `./gradlew assembleRelease -Pshared` builds an APK for other people: no preset or master password,
// so they choose their own and nobody else's password can unlock their phone.
val shared = project.hasProperty("shared")

/** A Gradle property (`-Pname=...`), then an environment variable, then local.properties. */
fun setting(name: String, env: String): String? =
    project.findProperty(name)?.toString() ?: System.getenv(env) ?: localProperties.getProperty(name)

// Release key (see README); without it, release builds fall back to the debug key of this machine.
val releaseStoreFile = setting("releaseStoreFile", "RELEASE_STORE_FILE")

android {
    namespace = "dev.reelsguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.reelsguard"
        minSdk = 26
        targetSdk = 35
        // CI passes these from the git tag; every published release needs a higher versionCode.
        versionCode = (project.findProperty("versionCode") ?: "1").toString().toInt()
        versionName = (project.findProperty("versionName") ?: "1.0").toString()

        buildConfigField("String", "PRESET_SALT", "\"${if (shared) "" else localProperties.getProperty("presetSalt", "")}\"")
        buildConfigField("String", "PRESET_HASH", "\"${if (shared) "" else localProperties.getProperty("presetHash", "")}\"")
        buildConfigField("String", "MASTER_SALT", "\"${if (shared) "" else localProperties.getProperty("masterSalt", "")}\"")
        buildConfigField("String", "MASTER_HASH", "\"${if (shared) "" else localProperties.getProperty("masterHash", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = setting("releaseStorePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = setting("releaseKeyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = setting("releaseKeyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Updates only install over an APK signed with the same key, so published builds must use the release key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
