pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS: a module quietly adding its own repository is a supply-chain
    // decision, and those are made here or not at all (Founding §2.1, §8.1).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FortressFlag_SDK_android"

include(":fortressflag")
include(":example")
