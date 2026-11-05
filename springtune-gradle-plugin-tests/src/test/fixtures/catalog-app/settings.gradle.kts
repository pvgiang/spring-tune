pluginManagement {
    repositories {
        maven("@REPO_URL@") { content { includeGroup("io.github.winkytek.springtune") } }
        gradlePluginPortal()
    }
}
rootProject.name = "catalog-app"
