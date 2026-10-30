plugins {
    id("fabric-loom") version "1.11-SNAPSHOT"
    kotlin("jvm") version "2.2.20"
}

group = property("maven_group") as String
version = property("mod_version") as String
base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
    maven("https://maven.impactdev.net/repository/development/")
    maven("https://artefacts.cobblemon.com/releases")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("com.cobblemon:fabric:${property("cobblemon_version")}")
    compileOnly("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
    annotationProcessor("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
}

loom {
    mixin {
        defaultRefmapName.set("wondertradeplus-refmap.json")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// Build with: gradle build (or import the project in IntelliJ IDEA and use its Gradle tool window).
