plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-netty:3.2.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("jtag.MainKt") }

tasks.test { useJUnitPlatform() }

val generateFixtures by tasks.registering(JavaExec::class) {
    group = "fixtures"
    mainClass.set("casepack.FixtureGenKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
