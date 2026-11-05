plugins {
    java
    id("io.github.winkytek.springtune")
}

val springtuneVersion = providers.gradleProperty("springtuneVersion").get()
val stagingRepo = providers.gradleProperty("stagingRepo").orNull

repositories {
    if (stagingRepo != null) {
        maven(stagingRepo)
    }
    // The engine the plugin runs (springtune-check-engine) and the dependencies below.
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.github.winkytek.springtune:springtune-spring-boot-starter:$springtuneVersion")
    implementation("io.github.winkytek.springtune:springtune-limiter:$springtuneVersion")
}

// Step 1 of gradle-smoke.sh: resolves every runtime jar, so a missing artifact fails here with its cause (the check
// itself only warns about dependencies it cannot resolve).
val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
tasks.register("resolveRuntimeClasspath") {
    inputs.files(runtimeFiles)
    doLast {
        runtimeFiles.files.map { it.name }.sorted().forEach { println("resolved: $it") }
    }
}
