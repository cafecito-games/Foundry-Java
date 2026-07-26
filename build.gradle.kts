import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
}

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

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
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
            if (plugins.hasPlugin("java") && publications.findByName("mavenJava") == null) {
                publications.create<MavenPublication>("mavenJava") {
                    from(components.getByName("java"))
                }
            }
        }
    }
}
