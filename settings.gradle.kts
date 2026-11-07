pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Loopa"

include(
    ":app",
    ":core",
    ":core-ui",
    ":core-data",
    ":core-network",
    ":feature-auth",
    ":feature-home",
    ":feature-lobby",
    ":feature-game",
    ":feature-results",
    ":feature-profile",
    ":feature-avatar",
    ":feature-shop",
    ":feature-settings",
)
