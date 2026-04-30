plugins {
    // 2.1.20+ knows how to parse Java 25's version string; older
    // releases trip on `IllegalArgumentException: 25.0.2`.
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    kotlin("plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
    application
}

application {
    // Compose Desktop demo window is the default; the SmokeKt CLI
    // is still reachable via `gradle run -PmainClass=wun.SmokeKt`.
    mainClass = (project.findProperty("mainClass") as String?) ?: "wun.demo.AppKt"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation(compose.desktop.currentOs)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
