// Publishes io.github.winkytek.springtune to the Gradle Plugin Portal. See docs/releasing.md#gradle-plugin-portal.
//
//   gradle -p springtune-gradle-plugin/portal publishPlugins -PspringtuneVersion=0.3.0 \
//          -Pgradle.publish.key=... -Pgradle.publish.secret=...
//
// Same sources and resources as the Maven module, so the jar is equivalent to the one on Maven Central. Like the
// Maven build, the jar has no runtime dependencies: the engine is resolved at check time from the user's
// repositories, so springtune-check-engine:<version> must already be on Maven Central when this is published.
plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.1.1"
}

val springtuneVersion = providers.gradleProperty("springtuneVersion").orNull
    ?: error("Pass -PspringtuneVersion=<released version>")

group = "io.github.winkytek.springtune"
version = springtuneVersion

repositories {
    mavenCentral()
    // Local verification only: -PstagingRepo=<path to target/staging-repo> resolves the engine before it is on Central.
    providers.gradleProperty("stagingRepo").orNull?.let { maven { url = uri(it) } }
}

dependencies {
    // Compile-only, exactly like <scope>provided</scope> in ../pom.xml.
    compileOnly("io.github.winkytek.springtune:springtune-check-engine:$springtuneVersion")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("../src/main/java"))
        resources.setSrcDirs(listOf("../src/main/resources"))
        // java-gradle-plugin generates the plugin descriptor from the gradlePlugin block below.
        resources.exclude("META-INF/gradle-plugins/**")
    }
}

tasks.processResources {
    // Same values Maven filters into it; the plugin reads them to resolve the matching engine.
    filesMatching("springtune-gradle-plugin.properties") {
        filter { line ->
            line.replace("\${project.version}", springtuneVersion).replace("\${project.groupId}", project.group.toString())
        }
    }
    // LICENSE and NOTICE in META-INF, as in the Maven-built jar.
    from(rootDir.resolve("../../")) {
        include("LICENSE", "NOTICE")
        into("META-INF")
    }
}

gradlePlugin {
    website.set("https://github.com/pvgiang/spring-tune")
    vcsUrl.set("https://github.com/pvgiang/spring-tune")
    plugins {
        create("springtune") {
            id = "io.github.winkytek.springtune"
            implementationClass = "io.github.winkytek.springtune.gradle.SpringTunePlugin"
            displayName = "SpringTune"
            description = "Fails the build on risky Spring Boot configuration: connection pools, timeouts, executors, retries."
            tags.set(listOf("spring-boot", "static-analysis", "hikaricp", "configuration", "sarif"))
        }
    }
}
