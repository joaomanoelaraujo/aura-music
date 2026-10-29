// Plain Kotlin/JVM code shared by the Android app and AURA Desktop (both run on the JVM):
// models, library index, offline smart search, lyrics (LRC parsing, LRCLIB client) and the
// Adaptive Atmosphere colour math. Nothing here may depend on Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    // Elliptic-curve arithmetic for SPAKE2 pairing in AURA Link (the JDK doesn't expose point math).
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
