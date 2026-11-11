import java.util.Properties
import java.net.URI

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
val domesticUpdateRepository = (providers.environmentVariable("CAMPUS_CNB_REPOSITORY").orNull
    ?: privateMapProperties.getProperty("CAMPUS_CNB_REPOSITORY", "campus-toolbox/campus-toolbox")).trim()
require(domesticUpdateRepository.isEmpty() || domesticUpdateRepository.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) {
    "CAMPUS_CNB_REPOSITORY must be empty or a fixed public owner/repository path."
}
// Public endpoint configuration only; never credentials. Empty keeps local stories/drafts.
val storyApiUrl=(providers.environmentVariable("STORY_API_URL").orNull ?: privateMapProperties.getProperty("STORY_API_URL", "")).trim()
val storyAuthUrl=(providers.environmentVariable("STORY_AUTH_URL").orNull ?: privateMapProperties.getProperty("STORY_AUTH_URL", "")).trim()
listOf(storyApiUrl,storyAuthUrl).filter {it.isNotEmpty()}.forEach {
    val uri=URI(it)
    require(uri.scheme=="https" && uri.host!=null && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.port in setOf(-1,443)) {"Story endpoints must be fixed HTTPS URLs without credentials/query/fragment"}
}
require(storyApiUrl.isEmpty()==storyAuthUrl.isEmpty()) {"Configure both STORY_API_URL and STORY_AUTH_URL, or neither"}
val storyMock=providers.gradleProperty("storyMock").orNull=="true"
val releaseSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith {
    providers.environmentVariable("CAMPUS_SIGNING_$it").orNull
}
val hasReleaseSigning = releaseSigning.values.any { it != null }
require(!hasReleaseSigning || releaseSigning.values.all { !it.isNullOrBlank() }) {
    "Provide all four CAMPUS_SIGNING_* environment values or none; no debug-signing fallback."
}
android {
    namespace = "cn.campuslife.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.campuslife.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "1.8.3"
        buildConfigField("String", "AMAP_ANDROID_KEY", "\"$privateMapKey\"")
        buildConfigField("boolean", "LOCAL_MAP_EDITING", "true")
        buildConfigField("String", "CNB_REPOSITORY", "\"$domesticUpdateRepository\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "STORY_API_URL", "\"$storyApiUrl\"")
        buildConfigField("String", "STORY_AUTH_URL", "\"$storyAuthUrl\"")
        buildConfigField("boolean", "STORY_MOCK", "false")
    }
    buildFeatures { buildConfig = true }
    if(storyMock) buildTypes.getByName("debug") {
        buildConfigField("String", "STORY_API_URL", "\"http://127.0.0.1:8787/api\"")
        buildConfigField("String", "STORY_AUTH_URL", "\"http://127.0.0.1:8787\"")
        buildConfigField("boolean", "STORY_MOCK", "true")
    }
    if (hasReleaseSigning) {
        val privateRelease = signingConfigs.create("privateRelease") {
            storeFile = file(releaseSigning.getValue("STORE_FILE")!!)
            storePassword = releaseSigning.getValue("STORE_PASSWORD")
            keyAlias = releaseSigning.getValue("KEY_ALIAS")
            keyPassword = releaseSigning.getValue("KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = privateRelease
    }
    testBuildType = providers.gradleProperty("deviceTestBuildType").orElse("debug").get().also {
        require(it in setOf("debug", "release")) { "deviceTestBuildType must be debug or release" }
    }
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
    // Capability probe only. Release builds must not package the WeChat SDK.
    debugImplementation("com.tencent.mm.opensdk:wechat-sdk-android:6.8.40")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
