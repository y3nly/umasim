plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.mee1080.umasim"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// 1. Tell Gradle to look in BOTH the race and utility folders
sourceSets {
    main {
        kotlin.srcDirs(
            "race/src/commonMain/kotlin",
            "utility/src/commonMain/kotlin"
        )
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    manifest {
        attributes("Main-Class" to "io.github.mee1080.umasim.race.MainKt")
    }
    
    archiveBaseName.set("umasim-cli")
    archiveClassifier.set("all")
    archiveVersion.set("") 
}