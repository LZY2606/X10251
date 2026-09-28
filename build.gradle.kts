plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.serialization") version "1.9.25"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.1.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("jtag.MainKt")
}
