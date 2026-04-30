plugins {
    kotlin("jvm")                         version "2.1.20"
    kotlin("plugin.serialization")        version "2.1.20"
    kotlin("plugin.compose")              version "2.1.20"
    id("org.jetbrains.compose")           version "1.7.3"
    application
}

application {
    mainClass = "myapp.AppKt"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    // For a future JitPack-based dependency:
    // maven("https://jitpack.io")
}

dependencies {
    // Pulled in via composite build (settings.gradle.kts -> includeBuild).
    // The wun-android module's rootProject.name is "wun" with group "wun",
    // so the coordinate is "wun:wun".
    implementation("wun:wun")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(21) }
