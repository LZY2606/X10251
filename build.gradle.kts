plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("jtag.MainKt")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
