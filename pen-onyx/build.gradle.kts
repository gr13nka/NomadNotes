plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nomadnotes.pen.onyx"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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
    // This is the only module allowed to depend on the Onyx SDK.
    implementation(libs.onyxsdk.pen) {
        // easypermissions is a runtime-permission helper for Onyx's demo flows; the raw-drawing
        // path never touches it. It is published only on repo.boox.com (404 on Maven Central),
        // so keeping it would tie every build to that unreachable repo, and it drags in the
        // legacy com.android.support stack on top. Excluding both keeps the build self-contained
        // and the APK off the old support library.
        exclude(group = "pub.devrel", module = "easypermissions")
        exclude(group = "com.android.support")
    }
}
