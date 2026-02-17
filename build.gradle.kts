plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "io.github.mee1080.umasim"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Tell Gradle exactly where the code is located inside your repo
sourceSets {
    main {
        kotlin.srcDirs("race/src/commonMain/kotlin")
    }
}

dependencies {
    // Standard Kotlin JSON parser
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

application {
    // Points to io/github/mee1080/umasim/race/Main.kt
    mainClass.set("io.github.mee1080.umasim.race.MainKt")
}

// Configures the Shadow plugin to build the fat JAR
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("umasim-cli")
    archiveClassifier.set("all")
    archiveVersion.set("") 
}