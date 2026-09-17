plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

// The calibration report is an on-demand tool, not part of the suite: it stays skipped unless it is
// pointed at a corpus. Gradle's -D lands on the daemon, not the forked test JVM, so it has to be
// handed across explicitly or the documented invocation silently runs nothing.
tasks.test {
    System.getProperty("calibration.corpus")?.let { systemProperty("calibration.corpus", it) }
}
