plugins {
    kotlin("jvm") version "2.1.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-cio:2.3.13")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:2.3.13")
}

application {
    mainClass.set("jtagreplay.MainKt")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
