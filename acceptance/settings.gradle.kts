dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl(java.io.File(settingsDir.parentFile, "build/repository").toURI()) }
        mavenCentral()
    }
}

rootProject.name = "foundry-java-acceptance"

include(":extension")
