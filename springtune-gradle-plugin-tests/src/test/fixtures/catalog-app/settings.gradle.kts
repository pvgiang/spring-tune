pluginManagement {
    repositories {
        maven("@REPO_URL@") { content { includeGroup("io.github.winky.springtune") } }
        gradlePluginPortal()
    }
}
rootProject.name = "catalog-app"
