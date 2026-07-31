plugins {
    id("com.android.application") apply false
    id("games.cafecito.foundry.java") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}

// Standalone consumer build. It resolves Foundry-Java exactly like a third-party project does:
// published Maven artifacts plus the published Gradle plugin, with no project dependency on the
// Foundry-Java build and no access to Foundry-Java test fixtures or internals.
allprojects {
    group = "games.cafecito.foundry.samples"
    version = providers.gradleProperty("foundryVersion").getOrElse("0.1.0")
}
