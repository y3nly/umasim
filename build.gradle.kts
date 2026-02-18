plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"    
    id("org.graalvm.buildtools.native") version "0.9.28"
}

group = "io.github.mee1080.umasim"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

sourceSets {
    main {
        kotlin.srcDirs(
            "race/src/commonMain/kotlin",
            "utility/src/commonMain/kotlin"
        )
    }
}

dependencies {
    // Standard Kotlin JSON parser
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("io.github.mee1080.umasim.race.MainKt")
            imageName.set("umasim-cli") // Output file will be umasim-cli.exe
        }
    }
}