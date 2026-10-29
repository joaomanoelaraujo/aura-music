import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// AURA Desktop: Compose for Desktop on the JVM, audio decoded by FFmpeg (LGPL build, via JavaCPP)
// and played through Java Sound. Shares models, library index and search with Android via :core.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    // AURA's icon set is plain Compose vector code, identical on Android and Desktop: compiled from
    // the Android source so both apps always draw the same icons. Keep that file free of Android APIs.
    sourceSets.main {
        kotlin.srcDir("../app/src/main/java/com/aura/music/core/designsystem/icons")
    }
}

/** JavaCPP classifier of the machine building the app (Windows first; others untested). */
val nativePlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier(nativePlatform) })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(nativePlatform) })

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.aura.desktop.MainKt"
        // Bundle the same JDK the code is compiled for (not whichever JDK runs Gradle).
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Xss2m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AURA"
            packageVersion = "0.1.0"
            description = "AURA — player de música"
            vendor = "AURA"
            modules("java.desktop", "java.logging", "jdk.unsupported")
            windows {
                menuGroup = "AURA"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5b0f5d0e-6a8c-4f3e-9b7a-2b8c1d7e4a91"
            }
        }
    }
}
