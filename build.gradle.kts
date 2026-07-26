import com.android.build.api.dsl.LibraryExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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

val requiredProjects =
    setOf(
        "foundry-java-api-model",
        "foundry-java-generator",
        "foundry-java-annotations",
        "foundry-java-processor",
        "foundry-java-runtime",
        "foundry-java-android",
        "foundry-java-gradle-plugin",
        "foundry-java-kotlin",
        "foundry-java-test",
    )
val requiredGroup = "games.cafecito.foundry"
val requiredJavaVersion = JavaVersion.VERSION_17
val requiredJavaLanguageVersion = JavaLanguageVersion.of(17)
val requiredPluginId = "games.cafecito.foundry.java"
val junitVersion = libs.versions.junit.get()
val forbiddenBoundaryPluginIds =
    setOf(
        "com.android.application",
        "com.android.dynamic-feature",
        "com.android.library",
        "com.android.test",
    )
val unsupportedAndroidLockConfigurations =
    setOf(
        // AGP injects the tested library into these synthetic configurations, whose self-variants
        // are ambiguous when they are resolved directly outside the Android test variant tasks.
        "debugAndroidTestCompileClasspath",
        "debugAndroidTestRuntimeClasspath",
        "debugUnitTestCompileClasspath",
        "debugUnitTestRuntimeClasspath",
        "releaseUnitTestCompileClasspath",
        "releaseUnitTestRuntimeClasspath",
    )

val resolveLockTasks =
    allprojects.map { project ->
        project.tasks.register("resolveAndLockProject") {
            notCompatibleWithConfigurationCache("Resolves project dependency configurations for lock generation.")
            doLast {
                project.configurations
                    .filter {
                        it.isCanBeResolved &&
                            !(
                                project.path == ":foundry-java-android" &&
                                    it.name in unsupportedAndroidLockConfigurations
                            )
                    }.forEach { it.resolve() }
            }
        }
    }

tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolves every relevant project configuration for dependency locking."
    dependsOn(resolveLockTasks)
}

tasks.register("verifyRepositoryContract") {
    group = "verification"
    description = "Verifies evaluated repository model, publications, boundaries, and Android archive contents."
    dependsOn(":foundry-java-android:bundleReleaseAar")
    notCompatibleWithConfigurationCache("Inspects evaluated Gradle project and publication models.")
    doLast {
        check(subprojects.map { it.name }.toSet() == requiredProjects) { "Included project set differs from contract." }
        check(file("gradle/wrapper/gradle-wrapper.properties").readText().contains("gradle-8.11.1-bin.zip"))
        check(allprojects.all { it.group.toString() == requiredGroup }) {
            "Every project must use Maven group $requiredGroup."
        }
        allprojects.forEach { project ->
            check(project.file("gradle.lockfile").isFile) {
                "Dependency lock state is missing for ${project.path}."
            }
            project.tasks.withType(AbstractArchiveTask::class.java).forEach { archive ->
                check(!archive.isPreserveFileTimestamps && archive.isReproducibleFileOrder)
            }
        }
        allprojects.filter { it.pluginManager.hasPlugin("java") }.forEach { project ->
            val javaExtension = project.extensions.getByType(JavaPluginExtension::class.java)
            check(javaExtension.toolchain.languageVersion.get() == requiredJavaLanguageVersion) {
                "${project.path} must use a Java 17 toolchain."
            }
            check(javaExtension.sourceCompatibility == requiredJavaVersion) {
                "${project.path} must compile Java 17 sources."
            }
            check(javaExtension.targetCompatibility == requiredJavaVersion) {
                "${project.path} must produce Java 17 bytecode."
            }
        }
        val android = project(":foundry-java-android")
        check(android.pluginManager.hasPlugin("com.android.library"))
        check(subprojects.filter { it != android }.none { it.pluginManager.hasPlugin("com.android.library") })
        val androidExtension = android.extensions.getByType(LibraryExtension::class.java)
        check(androidExtension.compileOptions.sourceCompatibility == requiredJavaVersion) {
            "Android sources must compile as Java 17."
        }
        check(androidExtension.compileOptions.targetCompatibility == requiredJavaVersion) {
            "Android bytecode must target Java 17."
        }
        val expectedBoundaryDependencies =
            mapOf(
                ":foundry-java-api-model" to
                    setOf(
                        "api=project(:foundry-java-annotations)",
                        "testImplementation=org.junit:junit-bom",
                        "testImplementation=org.junit.jupiter:junit-jupiter",
                        "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                        "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
                    ),
                ":foundry-java-annotations" to
                    setOf(
                        "testImplementation=org.junit:junit-bom",
                        "testImplementation=org.junit.jupiter:junit-jupiter",
                        "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                        "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
                    ),
                ":foundry-java-runtime" to
                    setOf(
                        "api=project(:foundry-java-api-model)",
                        "implementation=project(:foundry-java-annotations)",
                        "testImplementation=org.junit:junit-bom",
                        "testImplementation=org.junit.jupiter:junit-jupiter",
                        "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                        "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
                    ),
            )
        expectedBoundaryDependencies.forEach { (path, expectedDependencies) ->
            val boundaryProject = project(path)
            check(forbiddenBoundaryPluginIds.none { boundaryProject.pluginManager.hasPlugin(it) }) {
                "$path must remain free of Android plugins and artifacts."
            }
            val declaredDependencies =
                boundaryProject.configurations
                    .flatMap { configuration ->
                        configuration.dependencies.map { dependency ->
                            val coordinate =
                                if (dependency is ProjectDependency) {
                                    "project(${dependency.path})"
                                } else {
                                    "${dependency.group}:${dependency.name}"
                                }
                            "${configuration.name}=$coordinate"
                        }
                    }.toSet()
            check(declaredDependencies == expectedDependencies) {
                "$path declared dependencies differ from the Android-free boundary: $declaredDependencies"
            }
        }
        check(
            project(":foundry-java-kotlin").configurations.getByName("api").dependencies.any {
                it.name ==
                    "foundry-java-runtime"
            },
        )
        val androidPublishing = android.extensions.getByType(PublishingExtension::class.java)
        check(androidPublishing.publications.names.contains("release"))
        check(android.tasks.names.contains("publishReleasePublicationToMavenLocal"))
        val plugin = project(":foundry-java-gradle-plugin")
        val pluginPublishing = plugin.extensions.getByType(PublishingExtension::class.java)
        check(pluginPublishing.publications.names.contains("pluginMaven"))
        check(pluginPublishing.publications.names.none { it == "mavenJava" })
        check(plugin.tasks.names.any { it.startsWith("publishPluginMavenPublication") })
        val pluginDevelopment = plugin.extensions.getByType(GradlePluginDevelopmentExtension::class.java)
        check(pluginDevelopment.plugins.map { it.id }.toSet() == setOf(requiredPluginId)) {
            "Declared Gradle plugin IDs must be exactly $requiredPluginId."
        }
        val aar =
            android.layout.buildDirectory
                .file("outputs/aar/foundry-java-android-release.aar")
                .get()
                .asFile
        check(aar.isFile) { "Release AAR was not produced." }
        ZipFile(aar).use { zip ->
            check(zip.entries().asSequence().none { it.name.contains("libfoundry_android.so") }) {
                "Release AAR must not package libfoundry_android.so."
            }
            val classesJar = zip.getEntry("classes.jar")
            check(classesJar != null) { "Release AAR must contain classes.jar." }
            ZipInputStream(zip.getInputStream(classesJar)).use { nestedZip ->
                check(
                    generateSequence(nestedZip::getNextEntry).none {
                        it.name.endsWith(".class") &&
                            it.name.substringAfterLast('/').contains("Host")
                    },
                ) {
                    "Release AAR classes.jar must not contain forbidden Android host classes."
                }
            }
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
        dependencies.add("testImplementation", dependencies.platform("org.junit:junit-bom:$junitVersion"))
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
            if (project.name != "foundry-java-gradle-plugin" &&
                plugins.hasPlugin("java") &&
                publications.findByName("mavenJava") == null
            ) {
                publications.create<MavenPublication>("mavenJava") {
                    from(components.getByName("java"))
                }
            }
        }
    }
}
