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
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pure-JVM Opus port: no NDK/JNI. If this artifact ever moves, the
    // upstream is https://github.com/lostromb/concentus (JitPack works too).
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("org.json:json:20240303")
}
