import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}
val privateMapProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val privateMapKey = (providers.environmentVariable("AMAP_ANDROID_KEY").orNull
    ?: privateMapProperties.getProperty("AMAP_ANDROID_KEY", "")).trim()
require(privateMapKey.isEmpty() || privateMapKey.matches(Regex("[a-fA-F0-9]{32}"))) {
    "AMAP_ANDROID_KEY must be empty or a valid 32-character Android Key. The value is not printed."
}
android {
    namespace = "cn.campuslife.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.campuslife.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.7.0"
        buildConfigField("String", "AMAP_ANDROID_KEY", "\"$privateMapKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    // Explicit headless-x86 UI test build only: it cannot render the ARM-only SDK.
    // Normal builds retain the real libraries; this flag must never be used for delivery.
    if (providers.gradleProperty("mapUiTestWithoutNative").orNull == "true") {
        packaging {
            resources.excludes += setOf("lib/armeabi-v7a/**", "lib/arm64-v8a/**")
            jniLibs.excludes += setOf("**/armeabi-v7a/**", "**/arm64-v8a/**")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":watermark-core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
