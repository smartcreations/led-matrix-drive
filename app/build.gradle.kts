import java.util.Properties

plugins {
    id("com.android.application")
}

// The server address and device key come from local.properties (ledmatrix.serverUrl,
// ledmatrix.deviceKey; see local.properties.example), environment variables (LED_SERVER_URL,
// DEVICE_KEY) or ../led-matrix-ai/.env.local, so they never land in git.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val envLocal: Map<String, String> = rootProject.file("../led-matrix-ai/.env.local").let { file ->
    if (!file.exists()) emptyMap()
    else file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }
}

fun setting(property: String, env: String): String =
    (localProperties.getProperty(property) ?: System.getenv(env) ?: envLocal[env] ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"")

// The release signing key lives outside the repo; keystore.properties points to it and stays out of git.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.smartcreations.ledmatrixauto"
    compileSdk = 37

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    defaultConfig {
        applicationId = "com.smartcreations.ledmatrixauto"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        buildConfigField("String", "SERVER_URL", "\"${setting("ledmatrix.serverUrl", "LED_SERVER_URL")}\"")
        buildConfigField("String", "DEVICE_KEY", "\"${setting("ledmatrix.deviceKey", "DEVICE_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    // The phone side of Android Auto: CarAudioRecord needs it for the car's mic.
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
