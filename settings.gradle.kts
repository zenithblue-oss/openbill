pluginManagement {
    repositories {
        google()
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

rootProject.name = "openbill"

include(":core-model")
include(":core-tax")
include(":core-domain")
include(":core-database")
include(":core-data")
include(":core-backup")
include(":core-ui")
include(":androidApp")
include(":desktopApp")
include(":cli")
