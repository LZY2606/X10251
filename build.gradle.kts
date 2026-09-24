plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "org.boarddebug"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.13")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("org.boarddebug.tap.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

kotlin {
    jvmToolchain(17)
}
