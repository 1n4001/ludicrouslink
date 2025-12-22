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

rootProject.name = "hstreamer"
include(":proto")
include(":hstreamerAndroid")
include(":hstreamerAndroid:app")
include(":frontend")
include(":backend")
