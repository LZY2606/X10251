plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.1.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.3")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("gsb.jtag.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
