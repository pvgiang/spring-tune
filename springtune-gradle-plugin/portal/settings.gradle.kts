// Publishing-only build for the Gradle Plugin Portal (docs/releasing.md#gradle-plugin-portal).
// The Maven reactor stays the source of truth: this build compiles the same sources (../src/main) and only exists
// because the Portal accepts uploads exclusively through the com.gradle.plugin-publish Gradle plugin.
rootProject.name = "springtune-gradle-plugin"
