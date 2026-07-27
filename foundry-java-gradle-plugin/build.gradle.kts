import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
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
val actualAndroidAar =
    project(":foundry-java-android")
        .layout.buildDirectory
        .file("outputs/aar/foundry-java-android-release.aar")
val actualRuntimeJar =
    project(":foundry-java-runtime")
        .tasks
        .named<Jar>("jar")
        .flatMap(Jar::getArchiveFile)

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpTestPluginClasspath)
}

tasks.withType<Test>().configureEach {
    dependsOn(":foundry-java-android:bundleReleaseAar", ":foundry-java-runtime:jar")
    inputs.files(agp891TestPluginFiles)
    inputs.file(actualAndroidAar)
    inputs.file(actualRuntimeJar)
    systemProperty(
        "foundry.agp891.plugin.classpath",
        agp891TestPluginFiles.joinToString(File.pathSeparator),
    )
    systemProperty("foundry.actual.android.aar", actualAndroidAar.get().asFile.absolutePath)
    systemProperty("foundry.actual.runtime.jar", actualRuntimeJar.get().asFile.absolutePath)
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
