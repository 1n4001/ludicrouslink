pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "ludicrouslink"
include(":proto")
include(":ludicrouslinkAndroid")
include(":ludicrouslinkAndroid:app")
include(":frontend")
include(":backend")
