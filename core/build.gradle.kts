// Plain Kotlin/JVM code shared by the Android app and AURA Desktop (both run on the JVM):
// models, library index and offline smart search. Nothing here may depend on Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
