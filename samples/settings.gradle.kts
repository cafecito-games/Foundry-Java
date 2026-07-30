pluginManagement {
    val publishedFoundryVersion = startParameter.projectProperties["foundryVersion"] ?: "0.1.0"
    repositories {
        maven { setUrl(java.io.File(settingsDir.parentFile, "build/repository").toURI()) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.10.0"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("games.cafecito.foundry.java") version publishedFoundryVersion
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl(java.io.File(settingsDir.parentFile, "build/repository").toURI()) }
        google()
        mavenCentral()
    }
}

rootProject.name = "foundry-java-samples"

include(
    ":conformance-java",
    ":conformance-java-app",
    ":conformance-kotlin",
    ":conformance-kotlin-app",
)
