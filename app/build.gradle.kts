plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nomadnotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nomadnotes"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-spike"
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
    implementation(project(":core"))
    implementation(project(":pen-onyx"))

    implementation(libs.androidx.core.ktx)

    // Compose is staged for later steps only. The spike Activity uses plain Views, so the
    // Compose build feature (and its compiler) is intentionally NOT enabled yet — that keeps
    // this build's memory footprint low. These declarations just put the libraries on the
    // classpath for the UI work in later steps.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
