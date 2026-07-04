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

rootProject.name = "percept-android"

include(":core:canonical")
include(":core:da")
include(":core:trace")
include(":core:index")
include(":perception:video")
include(":perception:audio")
include(":dispatch")
include(":app")
