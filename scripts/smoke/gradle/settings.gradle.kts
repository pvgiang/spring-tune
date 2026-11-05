// Post-release smoke fixture (docs/releasing.md#post-release-smoke). Run it with scripts/smoke/gradle-smoke.sh.
//   -PspringtuneVersion=X.Y.Z   required
//   -PpluginRepo=portal|central|staging   where the plugin (marker + jar) is resolved from; default portal
//   -PstagingRepo=file:///...   local rehearsal only: a staged Maven repository, searched first
// Never mavenLocal(): the point is to resolve what users resolve.
pluginManagement {
    val springtuneVersion = providers.gradleProperty("springtuneVersion").get()
    val pluginRepo = providers.gradleProperty("pluginRepo").getOrElse("portal")
    val stagingRepo = providers.gradleProperty("stagingRepo").orNull
    repositories {
        if (stagingRepo != null) {
            maven(stagingRepo)
        }
        when (pluginRepo) {
            "portal" -> gradlePluginPortal()
            "central" -> mavenCentral()
            "staging" -> require(stagingRepo != null) { "pluginRepo=staging needs -PstagingRepo" }
            else -> error("pluginRepo must be portal, central or staging, was $pluginRepo")
        }
    }
    plugins {
        id("io.github.winkytek.springtune") version springtuneVersion
    }
}

rootProject.name = "springtune-gradle-smoke"
