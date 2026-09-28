plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.3.3")
    implementation("io.ktor:ktor-server-cio-jvm:3.3.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.3.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.3.3")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("gsb.web.ServerKt")
}

tasks.test { useJUnitPlatform(); testLogging { events("passed","failed","skipped") } }
