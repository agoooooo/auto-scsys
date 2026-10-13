plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningValues = listOf(
    providers.environmentVariable("FLOWPILOT_CONTROLLER_RELEASE_STORE_FILE").orNull,
    providers.environmentVariable("FLOWPILOT_CONTROLLER_RELEASE_STORE_PASSWORD").orNull,
    providers.environmentVariable("FLOWPILOT_CONTROLLER_RELEASE_KEY_ALIAS").orNull,
    providers.environmentVariable("FLOWPILOT_CONTROLLER_RELEASE_KEY_PASSWORD").orNull,
)
val hasReleaseSigningValues = releaseSigningValues.any { !it.isNullOrBlank() }
val hasCompleteReleaseSigningValues = releaseSigningValues.all { !it.isNullOrBlank() }
if (hasReleaseSigningValues && !hasCompleteReleaseSigningValues) {
    throw GradleException("Controller release signing requires all FLOWPILOT_CONTROLLER_RELEASE_* environment variables")
}

android {
    namespace = "com.flowpilot.controller"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.flowpilot.controller"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasCompleteReleaseSigningValues) {
                storeFile = file(requireNotNull(releaseSigningValues[0]))
                storePassword = requireNotNull(releaseSigningValues[1])
                keyAlias = requireNotNull(releaseSigningValues[2])
                keyPassword = requireNotNull(releaseSigningValues[3])
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
