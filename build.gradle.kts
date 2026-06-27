plugins {
    application
    kotlin("jvm") version "2.1.0"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()
val mainClassFull = "$group.${providers.gradleProperty("relativeMainClass").get()}"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Source: https://mvnrepository.com/artifact/net.java.dev.jna/jna-platform
    implementation("net.java.dev.jna:jna-platform:5.19.1")
}

// Deprecated
application {
    mainClass = mainClassFull
    tasks.installDist {
        val instDir = System.getenv("INSTALL_DIR").also {
            if (it == null) {
                println("INSTALL_DIR env var is not set, installing to project-local dir")
                return@installDist
            }
        }.let { File(it) }
        println("Installing to $instDir as set by INSTALL_DIR env var")
        into(instDir)
    }
}

tasks {
    jar {
        archiveBaseName = providers.gradleProperty("archiveName").get()
        // add runtime deps to jar
        val runtimeDeps = configurations.runtimeClasspath.get().map(::zipTree)
        from(runtimeDeps)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes("Main-Class" to mainClassFull)
        }
    }
    test {
        useJUnitPlatform()
    }
}
kotlin {
    jvmToolchain(19)
}