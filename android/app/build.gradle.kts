import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key: kept OUTSIDE the repo. Looks in $SHOWCASE_SIGNING_DIR, else ~/.showcase-signing,
// for keystore.properties (storeFile, storePassword, keyAlias, keyPassword). Without it, release builds are unsigned.
val signingDir = file(System.getenv("SHOWCASE_SIGNING_DIR") ?: "${System.getProperty("user.home")}/.showcase-signing")
val signingProps = Properties().apply {
    val f = signingDir.resolve("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "app.showcase.screensaver"
    compileSdk = 37

    defaultConfig {
        // Decide on this before any Play Store release - it can never change afterwards.
        applicationId = "app.showcase.screensaver"
        minSdk = 26          // Android 8.0+: every Shield, Chromecast/Google TV and most TV boxes
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = signingDir.resolve(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("io.coil-kt:coil:2.7.0")
}
