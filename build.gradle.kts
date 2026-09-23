plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
}

group = "tapreplay"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.0.3")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.3")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:3.0.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("tapreplay.AppKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
