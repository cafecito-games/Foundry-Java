import org.gradle.api.tasks.testing.Test
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

val agp891TestPluginClasspath =
    configurations.detachedConfiguration(
        dependencies.create("com.android.tools.build:gradle:8.9.1"),
    )
val agp891TestPluginFiles = agp891TestPluginClasspath.files

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpTestPluginClasspath)
}

tasks.withType<Test>().configureEach {
    inputs.files(agp891TestPluginFiles)
    systemProperty(
        "foundry.agp891.plugin.classpath",
        agp891TestPluginFiles.joinToString(File.pathSeparator),
    )
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
