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

fun configValue(name: String): String {
    return providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: providers.environmentVariable(name).orNull
        ?: ""
}

val gitSha = providers.environmentVariable("GITHUB_SHA").orNull
    ?.trim()
    ?.take(12)
    ?.takeIf { it.isNotEmpty() }
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
        }.standardOutput.asText.get().trim().take(12)
    }.getOrNull().orEmpty().ifBlank { "local" }

val sentryAuthToken = configValue("SENTRY_AUTH_TOKEN")

android {
    namespace = "app.buzer.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.buzer.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["FAKE_BACKEND_BASE_URL"] =
            configValue("FAKE_BACKEND_BASE_URL").ifBlank { "http://10.0.2.2:18433" }

        buildConfigField("String", "BUZER_BACKEND_BASE_URL", "\"${configValue("BUZER_BACKEND_BASE_URL")}\"")
        buildConfigField("String", "BUZER_BACKEND_TOKEN", "\"${configValue("BUZER_BACKEND_TOKEN")}\"")
        buildConfigField("String", "BUZER_ASSIGNEE_ME_ID", "\"${configValue("BUZER_ASSIGNEE_ME_ID")}\"")
        buildConfigField("String", "BUZER_ASSIGNEE_WIFE_ID", "\"${configValue("BUZER_ASSIGNEE_WIFE_ID")}\"")
        buildConfigField("String", "SENTRY_ANDROID_DSN", "\"${configValue("SENTRY_ANDROID_DSN")}\"")
        buildConfigField("String", "SENTRY_ENVIRONMENT", "\"${configValue("SENTRY_ENVIRONMENT").ifBlank { "development-device" }}\"")
        buildConfigField("String", "SENTRY_RELEASE", "\"app.buzer@$versionName+$gitSha\"")
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
    org.set(configValue("SENTRY_ORG").ifBlank { "jans-gi" })
    projectName.set(configValue("SENTRY_PROJECT").ifBlank { "buzer-android" })
    authToken.set(sentryAuthToken)
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryAuthToken.isNotBlank())
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(true)
    autoUploadSourceContext.set(sentryAuthToken.isNotBlank())
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
    implementation(libs.androidx.lifecycle.runtime.compose)
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
