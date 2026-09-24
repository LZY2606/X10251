plugins {
    kotlin("jvm") version "2.1.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("jtag.MainKt")
}

tasks.test { useJUnitPlatform() }
