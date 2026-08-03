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

rootProject.name = "ConnectX"

include(
    ":app",
    ":core:model",
    ":core:designsystem",
    ":strategy:api",
    ":vpn:api",
    ":vpn:nativebridge",
    ":vpn:relay",
    ":vpn:service",
)
