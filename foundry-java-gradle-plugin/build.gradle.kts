import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

plugins {
    `java-gradle-plugin`
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.android.gradle.plugin.get()}")
    testRuntimeOnly("com.android.tools.build:gradle:${libs.versions.android.gradle.plugin.get()}")
}

val agpTestPluginClasspath =
    configurations.detachedConfiguration(
        dependencies.create(
            "com.android.tools.build:gradle:${libs.versions.android.gradle.plugin.get()}",
        ),
    )

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpTestPluginClasspath)
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
