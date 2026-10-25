plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.acme.sso.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        buildConfigField("String", "ESIGNET_ISSUER", "\"${providers.gradleProperty("esignetIssuer").get()}\"")
    }
    buildFeatures { buildConfig = true }
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
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
