plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    kotlin("plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
    application
}

application {
    mainClass = "myapp.demo.AppKt"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Pulls wun-android via composite build (see settings.gradle.kts)
    implementation("wun:wun")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(21) }
