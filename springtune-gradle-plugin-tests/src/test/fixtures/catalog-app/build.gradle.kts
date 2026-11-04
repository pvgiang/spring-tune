// Dependencies come only from the version catalog, so reading this file literally finds nothing.
// The Spring Boot Gradle plugin (3.5) sits on the same buildscript classpath as SpringTune, whose engine
// uses another Boot version: the worker's isolated class loader keeps them apart.
plugins {
    java
    alias(libs.plugins.spring.boot)
    id("io.github.winky.springtune") version "@VERSION@"
}

repositories {
    maven("@REPO_URL@") { content { includeGroup("io.github.winky.springtune") } }
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.data.jpa)
}

springtune {
    formats.set(listOf("markdown", "sarif", "json"))
}
