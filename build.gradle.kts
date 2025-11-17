plugins {
    application
    kotlin("jvm") version "1.9.22"
}

group = providers.gradleProperty("group")
version = providers.gradleProperty("version")
val mainClassFull = "$group.${providers.gradleProperty("relativeMainClass")}"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

// Deprecated
application {
    mainClass = mainClass
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
        archiveBaseName = providers.gradleProperty("archiveName")
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