pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "foundry-java"

include(
    ":foundry-java-api-model",
    ":foundry-java-generator",
    ":foundry-java-annotations",
    ":foundry-java-processor",
    ":foundry-java-runtime",
    ":foundry-java-android",
    ":foundry-java-gradle-plugin",
    ":foundry-java-kotlin",
    ":foundry-java-test",
)
