import java.util.Properties

plugins {
    application
    kotlin("jvm") version "1.9.22"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

application {
    mainClass.set("org.example.MainKt")
    tasks.installDist {
        val instDir = System.getenv("INSTALL_DIR").also {
            if (it == null) {
                println("INSTALL_DIR env var is not set, installing to project-local dir")
                return@installDist
            }
        }.let { File(it) }
        println("Installing to $instDir as set by env var")
        into(instDir)
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(19)
}