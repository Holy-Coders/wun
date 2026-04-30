plugins {
    // 2.1.20+ knows how to parse Java 25's version string; older
    // releases trip on `IllegalArgumentException: 25.0.2`.
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

application {
    mainClass = "wun.SmokeKt"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
