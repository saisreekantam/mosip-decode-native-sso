plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.acme.sso.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        buildConfigField("String", "ESIGNET_ISSUER", "\"${providers.gradleProperty("esignetIssuer").get()}\"")
    }
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.browser)
    // Shared Acme look and feel (com.acme.sso.ui), used by both apps.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
