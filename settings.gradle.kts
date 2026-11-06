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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wow-forever-android"
include(":app")

// Ensure local.properties exists to satisfy secrets-gradle-plugin and AGP on clean checkouts
val localProps = java.io.File(rootDir, "local.properties")
if (!localProps.exists()) {
    localProps.createNewFile()
}
