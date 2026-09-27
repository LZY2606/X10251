plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "com.boarddebug.jtap"
version = "1.0.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.boarddebug.jtap.MainKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val ktorVersion = "3.3.0"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
