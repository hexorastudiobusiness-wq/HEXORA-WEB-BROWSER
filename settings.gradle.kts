// Hexora Browser
rootProject.name = "HexoraBrowser"

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
        // Mozilla GeckoView artifacts
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

include(":app")
