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

    packaging {
        jniLibs {
            // onyxsdk-pen, onyxsdk-pennative and com.tencent:mmkv each bundle their own copy of
            // the libc++ STL runtime (libc++_shared.so) for every ABI, which the packager refuses
            // to merge. Keep the first: all copies are the ABI-stable C++ shared runtime and a
            // single one serves every native lib. This is the only .so that collides across the
            // vendored AARs (the SDK/MMKV libraries themselves have unique names).
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pen-onyx"))

    implementation(libs.androidx.core.ktx)

    // Background storage I/O and autosave run on coroutines launched from the Activity's
    // lifecycleScope (lifecycle-runtime-ktx).
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

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
