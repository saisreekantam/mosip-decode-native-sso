plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.acme.pharmacy"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.acme.pharmacy"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    signingConfigs {
        // A certificate the vendor does not own: the "rogue" build demonstrates that Android refuses
        // to bind the Super App's SSO service for an app that is not signed by the vendor.
        create("rogue") {
            storeFile = file("rogue.keystore")
            storePassword = "rogue-demo"
            keyAlias = "rogue"
            keyPassword = "rogue-demo"
        }
    }
    buildTypes {
        create("rogue") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".rogue"
            signingConfig = signingConfigs.getByName("rogue")
            matchingFallbacks += "debug"
            manifestPlaceholders["appLabel"] = "Pharmacy (unofficial)"
        }
        getByName("debug") { manifestPlaceholders["appLabel"] = "Acme Pharmacy" }
        getByName("release") { manifestPlaceholders["appLabel"] = "Acme Pharmacy" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":sso-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
