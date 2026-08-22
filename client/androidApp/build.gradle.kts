import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.sentry.android.gradle)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun backendConfigValue(name: String): String {
    return providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: ""
}

android {
    namespace = "app.buzer.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.buzer.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["FAKE_BACKEND_BASE_URL"] =
            providers.gradleProperty("FAKE_BACKEND_BASE_URL").orNull ?: "http://10.0.2.2:18433"

        buildConfigField("String", "BUZER_BACKEND_BASE_URL", "\"${backendConfigValue("BUZER_BACKEND_BASE_URL")}\"")
        buildConfigField("String", "BUZER_BACKEND_TOKEN", "\"${backendConfigValue("BUZER_BACKEND_TOKEN")}\"")
        buildConfigField("String", "BUZER_ASSIGNEE_ME_ID", "\"${backendConfigValue("BUZER_ASSIGNEE_ME_ID")}\"")
        buildConfigField("String", "BUZER_ASSIGNEE_WIFE_ID", "\"${backendConfigValue("BUZER_ASSIGNEE_WIFE_ID")}\"")
        buildConfigField("String", "SENTRY_ANDROID_DSN", "\"${backendConfigValue("SENTRY_ANDROID_DSN")}\"")
        buildConfigField("String", "SENTRY_ENVIRONMENT", "\"${backendConfigValue("SENTRY_ENVIRONMENT").ifBlank { "development-device" }}\"")
        buildConfigField("String", "BUZER_GIT_SHA", "\"${providers.environmentVariable("GITHUB_SHA").orNull?.take(12) ?: "local"}\"")
        buildConfigField("String", "SENTRY_RELEASE", "\"app.buzer@${versionName}+${providers.environmentVariable("GITHUB_SHA").orNull?.take(12) ?: "local"}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        managedDevices {
            localDevices {
                create("pixel8Api34") {
                    device = "Pixel 8"
                    apiLevel = 34
                    systemImageSource = "google"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sentry {
    org.set(providers.gradleProperty("SENTRY_ORG").orNull ?: "jans-gi")
    projectName.set(providers.gradleProperty("SENTRY_PROJECT").orNull ?: "buzer-android")
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(providers.environmentVariable("SENTRY_AUTH_TOKEN").isPresent || localProperties.getProperty("SENTRY_AUTH_TOKEN") != null)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(true)
    autoUploadSourceContext.set(providers.environmentVariable("SENTRY_AUTH_TOKEN").isPresent || localProperties.getProperty("SENTRY_AUTH_TOKEN") != null)
    autoInstallation {
        enabled.set(false)
    }
    telemetry.set(false)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.wheel.picker.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sentry.android)
    implementation(libs.sentry.android.replay)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
