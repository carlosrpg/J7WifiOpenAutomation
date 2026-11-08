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

// Only CI tag builds pass -PappVersionName=X.Y.Z. Every other build (local, even release) is a prerelease of the
// next patch after the latest vX.Y.Z tag: X.Y.(Z+1)-dev.N, N = commits since that tag. No manual version bumps.
val appVersionName: String = prop("appVersionName") ?: run {
    val describe = runCatching {
        providers.exec { commandLine("git", "describe", "--tags", "--long", "--match", "v[0-9]*.[0-9]*.[0-9]*") }
            .standardOutput.asText.get().trim()
    }.getOrNull() // no tag yet, or no git
    val tag = describe?.let { Regex("""v(\d+)\.(\d+)\.(\d+)-(\d+)-g\w+""").matchEntire(it) }
    if (tag == null) "0.0.1-dev.0"
    else tag.destructured.let { (major, minor, patch, commits) -> "$major.$minor.${patch.toInt() + 1}-dev.$commits" }
}

// X.Y.Z-dev.N always sorts below X.Y.Z, so a CI release installs over any local build of the same version.
val appVersionCode: Int = run {
    val m = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})(?:-dev\.(\d+))?""").matchEntire(appVersionName)
        ?: error("appVersionName must be X.Y.Z or X.Y.Z-dev.N (minor/patch < 100), got '$appVersionName'")
    val (major, minor, patch, dev) = m.destructured
    val core = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    core * 1_000 + if (dev.isEmpty()) 999 else minOf(dev.toInt(), 998)
}

android {
    namespace = "com.carlosrpg.wifiopen"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.carlosrpg.wifiopen"
        minSdk = 30
        targetSdk = 30 // Android 11
        versionCode = appVersionCode
        versionName = appVersionName
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
