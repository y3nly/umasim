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

// 1. Tell Gradle where your source code actually lives
sourceSets {
    main {
        kotlin.srcDirs("src/commonMain/kotlin")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

application {
    // 2. Point to your specific Main.kt location
    // Since Main.kt is in io/github/mee1080/umasim/race, the class name has "Kt" appended
    mainClass.set("io.github.mee1080.umasim.race.MainKt")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveBaseName.set("umasim-cli")
    archiveClassifier.set("all")
    archiveVersion.set("") 
}