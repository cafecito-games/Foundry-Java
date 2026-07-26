import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.zip.ZipFile

plugins {
    `java-library`
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "games.cafecito.foundry"
version = providers.gradleProperty("foundryVersion").orElse("0.1.0-SNAPSHOT").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java", "*/src/**/*.java")
        googleJavaFormat("1.22.0").aosp()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "settings.gradle.kts")
        ktlint("1.3.1")
    }
}

allprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val requiredProjects = setOf(
    "foundry-java-api-model", "foundry-java-generator", "foundry-java-annotations",
    "foundry-java-processor", "foundry-java-runtime", "foundry-java-android",
    "foundry-java-gradle-plugin", "foundry-java-kotlin", "foundry-java-test",
)

tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolves every resolvable configuration in every project for dependency locking."
    notCompatibleWithConfigurationCache("Resolves project dependency configurations for lock generation.")
    dependsOn(allprojects.map { project ->
        project.tasks.register("resolveAndLockProject") {
            doLast {
                project.configurations.filter {
                    it.isCanBeResolved &&
                        (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")) &&
                        !it.name.contains("AndroidTest", ignoreCase = true)
                }.forEach { it.resolve() }
            }
        }
    })
}

tasks.register("verifyRepositoryContract") {
    group = "verification"
    description = "Verifies evaluated repository model, publications, boundaries, and Android archive contents."
    dependsOn(":foundry-java-android:bundleReleaseAar")
    notCompatibleWithConfigurationCache("Inspects evaluated Gradle project and publication models.")
    doLast {
        check(subprojects.map { it.name }.toSet() == requiredProjects) { "Included project set differs from contract." }
        check(file("gradle/wrapper/gradle-wrapper.properties").readText().contains("gradle-8.11.1-bin.zip"))
        allprojects.forEach { project ->
            check(project.file("gradle.lockfile").isFile || project == rootProject) {
                "Dependency lock state is missing for ${project.path}."
            }
            project.tasks.withType(AbstractArchiveTask::class.java).forEach { archive ->
                check(!archive.isPreserveFileTimestamps && archive.isReproducibleFileOrder)
            }
        }
        val android = project(":foundry-java-android")
        check(android.pluginManager.hasPlugin("com.android.library"))
        check(subprojects.filter { it != android }.none { it.pluginManager.hasPlugin("com.android.library") })
        listOf(":foundry-java-api-model", ":foundry-java-annotations", ":foundry-java-runtime").forEach { path ->
            check(!project(path).pluginManager.hasPlugin("com.android.library"))
        }
        check(project(":foundry-java-kotlin").configurations.getByName("api").dependencies.any { it.name == "foundry-java-runtime" })
        val androidPublishing = android.extensions.getByType(PublishingExtension::class.java)
        check(androidPublishing.publications.names.contains("release"))
        check(android.tasks.names.contains("publishReleasePublicationToMavenLocal"))
        val plugin = project(":foundry-java-gradle-plugin")
        val pluginPublishing = plugin.extensions.getByType(PublishingExtension::class.java)
        check(pluginPublishing.publications.names.contains("pluginMaven"))
        check(pluginPublishing.publications.names.none { it == "mavenJava" })
        check(plugin.tasks.names.any { it.startsWith("publishPluginMavenPublication") })
        val aar = android.layout.buildDirectory.file("outputs/aar/foundry-java-android-release.aar").get().asFile
        check(aar.isFile) { "Release AAR was not produced." }
        ZipFile(aar).use { zip ->
            check(zip.entries().asSequence().none { it.name.contains("libfoundry_android.so") || it.name.contains("FoundryAndroidHost") })
        }
    }
}

tasks.named("check") { dependsOn("verifyRepositoryContract") }

subprojects {
    if (name != "foundry-java-android") {
        pluginManager.apply("java-library")
        pluginManager.apply("maven-publish")
    }
    pluginManager.apply("com.diffplug.spotless")

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        dependencies.add("testImplementation", dependencies.platform("org.junit:junit-bom:5.11.3"))
        dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter")
        dependencies.add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine")
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set("Java-first Android integration surface for Foundry.")
                    url.set("https://github.com/cafecito-games/Foundry-Java")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/cafecito-games/Foundry-Java")
                        connection.set("scm:git:https://github.com/cafecito-games/Foundry-Java.git")
                    }
                }
            }
            if (project.name != "foundry-java-gradle-plugin" && plugins.hasPlugin("java") && publications.findByName("mavenJava") == null) {
                publications.create<MavenPublication>("mavenJava") {
                    from(components.getByName("java"))
                }
            }
        }
    }
}
