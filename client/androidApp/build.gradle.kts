import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
    compileSdk = 35

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

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
