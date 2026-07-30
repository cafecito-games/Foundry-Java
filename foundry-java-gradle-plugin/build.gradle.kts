import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata
import org.gradle.process.CommandLineArgumentProvider

plugins {
    `java-gradle-plugin`
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.android.gradle.plugin.get()}")
    testRuntimeOnly("com.android.tools.build:gradle:${libs.versions.android.gradle.plugin.get()}")
}

// Test.systemProperties is an @Input, so handing the TestKit fixtures their artifact locations that
// way put checkout-specific absolute paths into every test task's build cache key: an entry stored on
// one machine could never be replayed on another, and the key would break the day a differently
// pathed runner appeared. A CommandLineArgumentProvider contributes the same -D flags at execution
// time while declaring only the artifacts themselves as normalized inputs, so the fixtures still
// receive real paths and the key travels. gradle/verify-build-cache-portability.sh keeps that true.
abstract class AgpPluginClasspathArgumentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val pluginClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dfoundry.agp891.plugin.classpath=" +
                pluginClasspath.files.joinToString(File.pathSeparator),
        )
}

abstract class DescriptorGoldenArgumentProvider : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val descriptorGolden: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dfoundry.processor.descriptor.golden=" + descriptorGolden.get().asFile.absolutePath,
        )
}

abstract class ActualArtifactArgumentProvider : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val androidAar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeJar: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dfoundry.actual.android.aar=" + androidAar.get().asFile.absolutePath,
            "-Dfoundry.actual.runtime.jar=" + runtimeJar.get().asFile.absolutePath,
        )
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
val actualAndroidAar =
    project(":foundry-java-android")
        .layout.buildDirectory
        .file("outputs/aar/foundry-java-android-release.aar")
val actualRuntimeJar =
    project(":foundry-java-runtime")
        .tasks
        .named<Jar>("jar")
        .flatMap(Jar::getArchiveFile)
val processorDescriptorGolden =
    project(":foundry-java-processor")
        .layout.projectDirectory
        .file("src/test/resources/golden/demo-module.descriptor")

// Exactly one test in this suite consumes the real release AAR and the real runtime jar. Declaring
// those two artifacts as inputs of the whole suite meant any change under src/main/cpp or in the
// runtime — most changes in this repository — invalidated a multi-minute TestKit suite that is
// otherwise testing plugin wiring against synthetic fixtures. The heavy inputs now belong to the one
// task that reads them.
val actualArtifactTag = "actualArtifacts"

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpTestPluginClasspath)
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(
        objects.newInstance<AgpPluginClasspathArgumentProvider>().apply {
            pluginClasspath.from(agp891TestPluginClasspath)
        },
    )
    jvmArgumentProviders.add(
        objects.newInstance<DescriptorGoldenArgumentProvider>().apply {
            descriptorGolden.set(processorDescriptorGolden)
        },
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags(actualArtifactTag)
    }
}

val actualArtifactTest =
    tasks.register<Test>("actualArtifactTest") {
        group = "verification"
        description =
            "Runs the TestKit tests that consume the real release AAR and the real runtime jar."
        val testSourceSet = sourceSets["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags(actualArtifactTag)
        }
        dependsOn(":foundry-java-android:bundleReleaseAar", ":foundry-java-runtime:jar")
        jvmArgumentProviders.add(
            objects.newInstance<ActualArtifactArgumentProvider>().apply {
                androidAar.set(actualAndroidAar)
                runtimeJar.set(actualRuntimeJar)
            },
        )
    }

tasks.named("check") {
    dependsOn(actualArtifactTest)
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
