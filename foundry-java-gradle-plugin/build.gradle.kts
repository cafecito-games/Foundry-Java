plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("foundryJava") {
            id = "games.cafecito.foundry.java"
            implementationClass = "games.cafecito.foundry.gradle.FoundryJavaPlugin"
            displayName = "Foundry Java Gradle plugin"
            description = "Conventions for Java-first Foundry Android projects."
        }
    }
}
