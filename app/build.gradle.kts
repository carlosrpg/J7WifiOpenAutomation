import java.util.Properties

plugins {
    id("com.android.application")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/** Gradle property (-P / ORG_GRADLE_PROJECT_*) -> local.properties -> environment variable. */
fun prop(key: String): String? =
    findProperty(key) as? String ?: localProps.getProperty(key) ?: System.getenv(key)

android {
    namespace = "com.carlosrpg.wifiopen"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.carlosrpg.wifiopen"
        minSdk = 30
        targetSdk = 30 // Android 11
        versionCode = prop("appVersionCode")?.toInt() ?: 1
        versionName = prop("appVersionName") ?: "1.0.3"
    }

    val keystore = rootProject.file(prop("RELEASE_STORE_FILE") ?: "release.keystore")
    if (keystore.exists()) {
        signingConfigs.create("release") {
            storeFile = keystore
            storePassword = prop("RELEASE_STORE_PASSWORD")
            keyAlias = prop("RELEASE_KEY_ALIAS")
            keyPassword = prop("RELEASE_KEY_PASSWORD")
        }
    }

    // Sideloaded onto an Android 11 head unit, never published to Play.
    lint { disable += "ExpiredTargetSdkVersion" }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No keystore (fresh clone): release stays unsigned instead of failing the build.
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
