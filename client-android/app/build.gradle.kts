import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

// Release signing. Keystore and passwords live OUTSIDE the repo (see
// key.properties, already excluded by .gitignore), so a checkout never
// contains anything that can sign a release. Absent key.properties the
// release build simply stays unsigned instead of failing -- that keeps
// a fresh clone buildable for anyone who only wants a debug APK.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mykelsilver.paniccall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mykelsilver.paniccall"
        minSdk = 26            // practical floor: NotificationChannel requires 26.
                                // Covers Jo's Android 12 (API 31) with margin.
        targetSdk = 36
        versionCode = 33
        versionName = "0.2.13"
    }
    buildFeatures { compose = true }
    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // R8 bewust uit: Concentus (Opus) en OkHttp leunen op
            // reflectie, en een stille shrink-fout zou zich pas tijdens
            // een echte noodoproep laten zien. Winst in APK-grootte weegt
            // daar niet tegenop.
            isMinifyEnabled = false
        }
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
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    // WindowInsetsControllerCompat, for light/dark system bar icons.
    // Arrives transitively via activity-compose anyway; declared
    // explicitly so a future dependency bump cannot quietly remove it.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pure-JVM Opus port: no NDK/JNI. If this artifact ever moves, the
    // upstream is https://github.com/lostromb/concentus (JitPack works too).
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("org.json:json:20240303")
}
