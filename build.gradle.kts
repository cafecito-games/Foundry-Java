import com.android.build.api.dsl.LibraryExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

abstract class ResolveAndLockDependencies : DefaultTask() {
    @get:Classpath
    abstract val dependencyFiles: ConfigurableFileCollection

    @TaskAction
    fun resolveDependencies() {
        logger.lifecycle("Resolved ${dependencyFiles.files.size} dependency artifacts for $path.")
    }
}

abstract class VerifyRepositoryModel : DefaultTask() {
    @get:Input
    abstract val expectedProjectNames: SetProperty<String>

    @get:Input
    abstract val actualProjectNames: SetProperty<String>

    @get:Input
    abstract val requiredGroup: Property<String>

    @get:Input
    abstract val projectGroups: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFiles: ConfigurableFileCollection

    @get:Input
    abstract val invalidArchiveTasks: ListProperty<String>

    @get:Input
    abstract val javaSettings: MapProperty<String, String>

    @get:Input
    abstract val androidProjects: SetProperty<String>

    @get:Input
    abstract val androidJavaSettings: MapProperty<String, String>

    @get:Input
    abstract val expectedBoundaryDependencies: MapProperty<String, String>

    @get:Input
    abstract val actualBoundaryDependencies: MapProperty<String, String>

    @get:Input
    abstract val boundaryAndroidPlugins: MapProperty<String, String>

    @get:Input
    abstract val kotlinDependsOnRuntime: Property<Boolean>

    @get:Input
    abstract val expectedPublicationNames: MapProperty<String, String>

    @get:Input
    abstract val actualPublicationNames: MapProperty<String, String>

    @get:Input
    abstract val requiredPluginId: Property<String>

    @get:Input
    abstract val actualPluginIds: SetProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrapperProperties: RegularFileProperty

    @TaskAction
    fun verifyModel() {
        check(actualProjectNames.get() == expectedProjectNames.get()) {
            "Included project set differs from contract."
        }
        val group = requiredGroup.get()
        check(projectGroups.get().values.all { it == group }) {
            "Every project must use Maven group $group: ${projectGroups.get()}"
        }
        check(lockFiles.files.size == expectedProjectNames.get().size + 1) {
            "Every project, including the root, must have dependency lock state."
        }
        check(invalidArchiveTasks.get().isEmpty()) {
            "Archive tasks must use reproducible order and timestamps: ${invalidArchiveTasks.get()}"
        }
        check(javaSettings.get().values.all { it == "17|17|17" }) {
            "Every Java project must use a Java 17 toolchain and Java 17 bytecode: ${javaSettings.get()}"
        }
        check(androidProjects.get() == setOf(":foundry-java-android")) {
            "Only foundry-java-android may apply the Android library plugin: ${androidProjects.get()}"
        }
        check(androidJavaSettings.get() == mapOf(":foundry-java-android" to "17|17")) {
            "Android sources and bytecode must target Java 17: ${androidJavaSettings.get()}"
        }
        check(boundaryAndroidPlugins.get().values.all { it.isEmpty() }) {
            "Android-free boundary projects applied forbidden plugins: ${boundaryAndroidPlugins.get()}"
        }
        check(actualBoundaryDependencies.get() == expectedBoundaryDependencies.get()) {
            "Android-free boundary dependencies differ from the contract: ${actualBoundaryDependencies.get()}"
        }
        check(kotlinDependsOnRuntime.get()) {
            "foundry-java-kotlin must expose foundry-java-runtime."
        }
        check(actualPublicationNames.get() == expectedPublicationNames.get()) {
            "Configured Maven publications differ from the bootstrap contract: ${actualPublicationNames.get()}"
        }
        check(actualPluginIds.get() == setOf(requiredPluginId.get())) {
            "Declared Gradle plugin IDs must be exactly ${requiredPluginId.get()}."
        }
        check(
            wrapperProperties
                .get()
                .asFile
                .readText()
                .contains("gradle-8.11.1-bin.zip"),
        ) {
            "The repository must use the Gradle 8.11.1 binary distribution."
        }
    }
}

abstract class VerifyAndroidAar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aarFile: RegularFileProperty

    @get:Input
    abstract val allowedClasses: SetProperty<String>

    @TaskAction
    fun verifyAar() {
        val aar = aarFile.get().asFile
        ZipFile(aar).use { zip ->
            check(
                zip.entries().asSequence().none {
                    it.name == "libfoundry_android.so" || it.name.endsWith("/libfoundry_android.so")
                },
            ) {
                "Release AAR must not package libfoundry_android.so."
            }
            val classesJar = zip.getEntry("classes.jar")
            check(classesJar != null) { "Release AAR must contain classes.jar." }
            val actualClasses =
                ZipInputStream(zip.getInputStream(classesJar)).use { nestedZip ->
                    generateSequence(nestedZip::getNextEntry)
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .toSet()
                }
            check(actualClasses == allowedClasses.get()) {
                "Release AAR classes.jar must contain exactly the allowed bootstrap classes. " +
                    "Expected ${allowedClasses.get()}, found $actualClasses."
            }
        }
    }
}

abstract class VerifyPublications : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val expectedPoms: MapProperty<String, String>

    @get:Input
    abstract val expectedModules: MapProperty<String, String>

    @get:Input
    abstract val expectedArtifacts: ListProperty<String>

    @TaskAction
    fun verifyPublishedFiles() {
        val repository = repositoryDirectory.get().asFile

        fun publishedFile(
            relativeDirectory: String,
            artifactId: String,
            extension: String,
            classifier: String = "",
        ): File {
            val directory = repository.resolve(relativeDirectory)
            val classifierSuffix = if (classifier.isEmpty()) "" else "-$classifier"
            val candidates =
                directory.listFiles().orEmpty().filter {
                    it.isFile &&
                        it.name.startsWith("$artifactId-") &&
                        it.name.endsWith("$classifierSuffix.$extension")
                }
            check(candidates.isNotEmpty()) {
                "Expected a published $extension file for $artifactId in $relativeDirectory."
            }
            return candidates.maxBy { it.lastModified() }
        }

        expectedPoms.get().forEach { (relativeDirectory, encodedCoordinates) ->
            val coordinates = encodedCoordinates.split('|')
            val pom = publishedFile(relativeDirectory, coordinates[1], "pom")
            check(pom.length() > 0L) { "Generated POM is empty: $pom" }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)

            fun firstValue(name: String) = document.getElementsByTagName(name).item(0)?.textContent
            check(firstValue("groupId") == coordinates[0]) { "POM groupId differs for $pom." }
            check(firstValue("artifactId") == coordinates[1]) { "POM artifactId differs for $pom." }
            check(firstValue("version") == coordinates[2]) { "POM version differs for $pom." }
        }
        expectedModules.get().forEach { (relativeDirectory, encodedCoordinates) ->
            val coordinates = encodedCoordinates.split('|')
            val module = publishedFile(relativeDirectory, coordinates[1], "module")
            check(module.length() > 0L) { "Gradle module metadata is empty: $module" }
            val content = module.readText()
            check(content.contains("\"group\": \"${coordinates[0]}\"")) {
                "Module metadata group differs for $module."
            }
            check(content.contains("\"module\": \"${coordinates[1]}\"")) {
                "Module metadata name differs for $module."
            }
            check(content.contains("\"version\": \"${coordinates[2]}\"")) {
                "Module metadata version differs for $module."
            }
        }
        expectedArtifacts.get().forEach { encodedArtifact ->
            val artifactSpec = encodedArtifact.split('|')
            val artifact =
                publishedFile(
                    artifactSpec[0],
                    artifactSpec[1],
                    artifactSpec[2],
                    artifactSpec[3],
                )
            check(artifact.length() > 0L) { "Published artifact is empty: $artifact" }
            if (artifact.extension == "jar" || artifact.extension == "aar") {
                ZipFile(artifact).use { zip ->
                    check(zip.entries().hasMoreElements()) { "Published archive is empty: $artifact" }
                }
            }
        }
    }
}

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
    kotlin {
        target("*/src/**/*.kt")
        ktlint("1.3.1")
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
val requiredGroupCoordinate = "games.cafecito.foundry"
val requiredJavaVersion = JavaVersion.VERSION_17
val requiredJavaLanguageVersion = JavaLanguageVersion.of(17)
val requiredPluginIdentifier = "games.cafecito.foundry.java"
val junitVersion = libs.versions.junit.get()
val forbiddenBoundaryPluginIds =
    setOf(
        "com.android.application",
        "com.android.dynamic-feature",
        "com.android.library",
        "com.android.test",
    )
val javaLockConfigurations =
    setOf(
        "annotationProcessor",
        "compileClasspath",
        "runtimeClasspath",
        "testAnnotationProcessor",
        "testCompileClasspath",
        "testRuntimeClasspath",
    )
val kotlinLockConfigurations =
    setOf(
        "kotlinBuildToolsApiClasspath",
        "kotlinCompilerClasspath",
        "kotlinCompilerPluginClasspathMain",
        "kotlinCompilerPluginClasspathTest",
    )
val androidMainLockConfigurations =
    setOf(
        "debugAnnotationProcessorClasspath",
        "debugCompileClasspath",
        "debugRuntimeClasspath",
        "releaseAnnotationProcessorClasspath",
        "releaseCompileClasspath",
        "releaseRuntimeClasspath",
    )
val requiredBoundaryDependencies =
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
val requiredPublicationNames =
    requiredProjects.associate { projectName ->
        val path = ":$projectName"
        path to
            when (projectName) {
                "foundry-java-android" -> "release"
                "foundry-java-gradle-plugin" -> "foundryJavaPluginMarkerMaven,pluginMaven"
                else -> "mavenJava"
            }
    }
val allowedBootstrapAndroidClasses = emptySet<String>()

val resolveLockTasks =
    allprojects.map { currentProject ->
        val resolveTask =
            currentProject.tasks.register<ResolveAndLockDependencies>("resolveAndLockProject") {
                group = "verification"
                description = "Resolves supported dependency inputs for ${currentProject.path} lock generation."
            }
        currentProject.configurations.configureEach {
            val supportedNames =
                (
                    if (currentProject.name != "foundry-java-android") {
                        javaLockConfigurations
                    } else {
                        emptySet()
                    }
                ) +
                    (
                        if (currentProject.name ==
                            "foundry-java-kotlin"
                        ) {
                            kotlinLockConfigurations
                        } else {
                            emptySet<String>()
                        }
                    ) +
                    (
                        if (currentProject.name == "foundry-java-android") {
                            androidMainLockConfigurations
                        } else {
                            emptySet()
                        }
                    )
            if (isCanBeResolved && name in supportedNames) {
                resolveTask.configure { dependencyFiles.from(this@configureEach) }
            }
        }
        resolveTask
    }

tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolves every supported project dependency input for lock generation."
    dependsOn(resolveLockTasks)
    // AGP's unit and instrumentation configurations include tested-library self-variants that
    // generic resolution cannot select reliably. Android's check task resolves those dependencies
    // through AGP's own variant-aware lint and test task inputs.
    dependsOn(":foundry-java-android:check")
}

val verifyRepositoryModel =
    tasks.register<VerifyRepositoryModel>("verifyRepositoryModel") {
        group = "verification"
        description = "Verifies the captured, configuration-cache-safe repository model."
        expectedProjectNames.set(requiredProjects)
        requiredGroup.set(requiredGroupCoordinate)
        expectedBoundaryDependencies.set(
            requiredBoundaryDependencies.mapValues { (_, dependencies) -> dependencies.sorted().joinToString(",") },
        )
        expectedPublicationNames.set(requiredPublicationNames)
        requiredPluginId.set(requiredPluginIdentifier)
        wrapperProperties.set(layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
    }

val verifyAndroidAar =
    tasks.register<VerifyAndroidAar>("verifyAndroidAar") {
        group = "verification"
        description = "Verifies the Android bootstrap archive contents."
        dependsOn(":foundry-java-android:bundleReleaseAar")
        aarFile.set(
            project(":foundry-java-android")
                .layout.buildDirectory
                .file("outputs/aar/foundry-java-android-release.aar"),
        )
        allowedClasses.set(allowedBootstrapAndroidClasses)
    }

val verifyPublications =
    tasks.register<VerifyPublications>("verifyPublications") {
        group = "verification"
        description = "Publishes and validates every configured bootstrap Maven publication."
        repositoryDirectory.set(layout.buildDirectory.dir("repository"))
        dependsOn(requiredProjects.map { ":$it:publishAllPublicationsToBootstrapRepository" })
    }

tasks.register("verifyRepositoryContract") {
    group = "verification"
    description = "Verifies repository model, publications, boundaries, and Android archive contents."
    dependsOn(verifyRepositoryModel, verifyAndroidAar, verifyPublications)
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
            repositories {
                maven {
                    name = "bootstrap"
                    url =
                        rootProject.layout.buildDirectory
                            .dir("repository")
                            .get()
                            .asFile
                            .toURI()
                }
            }
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

gradle.projectsEvaluated {
    verifyRepositoryModel.configure {
        actualProjectNames.set(subprojects.map { it.name }.toSet())
        projectGroups.set(allprojects.associate { it.path to it.group.toString() })
        lockFiles.from(allprojects.map { it.layout.projectDirectory.file("gradle.lockfile") })
        invalidArchiveTasks.set(
            allprojects.flatMap { currentProject ->
                currentProject.tasks
                    .withType(AbstractArchiveTask::class.java)
                    .filter { it.isPreserveFileTimestamps || !it.isReproducibleFileOrder }
                    .map { "${currentProject.path}:${it.name}" }
            },
        )
        javaSettings.set(
            allprojects
                .filter { it.pluginManager.hasPlugin("java") }
                .associate { currentProject ->
                    val javaExtension = currentProject.extensions.getByType(JavaPluginExtension::class.java)
                    currentProject.path to
                        listOf(
                            javaExtension.toolchain.languageVersion
                                .get()
                                .asInt(),
                            javaExtension.sourceCompatibility.majorVersion,
                            javaExtension.targetCompatibility.majorVersion,
                        ).joinToString("|")
                },
        )
        val androidProjectsWithPlugin =
            subprojects.filter { it.pluginManager.hasPlugin("com.android.library") }
        androidProjects.set(androidProjectsWithPlugin.map { it.path }.toSet())
        androidJavaSettings.set(
            androidProjectsWithPlugin.associate { currentProject ->
                val androidExtension = currentProject.extensions.getByType(LibraryExtension::class.java)
                currentProject.path to
                    listOf(
                        androidExtension.compileOptions.sourceCompatibility.majorVersion,
                        androidExtension.compileOptions.targetCompatibility.majorVersion,
                    ).joinToString("|")
            },
        )
        boundaryAndroidPlugins.set(
            requiredBoundaryDependencies.keys.associateWith { path ->
                forbiddenBoundaryPluginIds
                    .filter { project(path).pluginManager.hasPlugin(it) }
                    .sorted()
                    .joinToString(",")
            },
        )
        actualBoundaryDependencies.set(
            requiredBoundaryDependencies.keys.associateWith { path ->
                project(path)
                    .configurations
                    .flatMap { configuration ->
                        configuration.dependencies.map { dependency ->
                            val coordinate =
                                if (dependency is ProjectDependency) {
                                    "project(:${dependency.name})"
                                } else {
                                    "${dependency.group}:${dependency.name}"
                                }
                            "${configuration.name}=$coordinate"
                        }
                    }.toSortedSet()
                    .joinToString(",")
            },
        )
        kotlinDependsOnRuntime.set(
            project(":foundry-java-kotlin")
                .configurations
                .getByName("api")
                .dependencies
                .any { it.name == "foundry-java-runtime" },
        )
        actualPublicationNames.set(
            subprojects.associate { currentProject ->
                val publishing = currentProject.extensions.getByType(PublishingExtension::class.java)
                currentProject.path to
                    publishing.publications.names
                        .sorted()
                        .joinToString(",")
            },
        )
        actualPluginIds.set(
            project(":foundry-java-gradle-plugin")
                .extensions
                .getByType(GradlePluginDevelopmentExtension::class.java)
                .plugins
                .map { it.id }
                .toSet(),
        )
    }

    val expectedPoms = mutableMapOf<String, String>()
    val expectedModules = mutableMapOf<String, String>()
    val expectedArtifacts = mutableListOf<String>()
    subprojects.forEach { currentProject ->
        val publishing = currentProject.extensions.getByType(PublishingExtension::class.java)
        publishing.publications.withType(MavenPublication::class.java).forEach { publication ->
            val groupId = publication.groupId
            val artifactId = publication.artifactId
            val publicationVersion = publication.version
            val coordinates = "$groupId|$artifactId|$publicationVersion"
            val relativeDirectory = "${groupId.replace('.', '/')}/$artifactId/$publicationVersion"
            expectedPoms[relativeDirectory] = coordinates
            publication.artifacts.forEach { artifact ->
                expectedArtifacts +=
                    "$relativeDirectory|$artifactId|${artifact.extension}|${artifact.classifier.orEmpty()}"
            }
            if (publication.artifacts.isNotEmpty()) {
                expectedModules[relativeDirectory] = coordinates
            }
        }
    }
    verifyPublications.configure {
        this.expectedPoms.set(expectedPoms)
        this.expectedModules.set(expectedModules)
        this.expectedArtifacts.set(expectedArtifacts.sorted())
    }
}
