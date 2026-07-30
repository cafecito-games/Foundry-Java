import com.android.build.api.dsl.LibraryExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.FileCollectionDependency
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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element
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

fun stableBoundaryFileSignature(dependency: FileCollectionDependency): String {
    val declaredFiles = dependency.files
    val displayName = declaredFiles.toString()
    val buildTaskPaths =
        declaredFiles.buildDependencies
            .getDependencies(null)
            .map { it.path }
            .sorted()

    // These display names and the plugin metadata task are stable declaration metadata in the
    // repository's pinned Gradle 8.11.1 wrapper. Do not enumerate the files: doing so makes task
    // output existence and contents configuration-cache inputs.
    return when {
        displayName == "Gradle API files" && buildTaskPaths.isEmpty() -> "gradle-api-files"
        displayName == "Gradle TestKit files" && buildTaskPaths.isEmpty() -> "gradle-test-kit-files"
        displayName == "file collection" &&
            buildTaskPaths == listOf(":foundry-java-gradle-plugin:pluginUnderTestMetadata") ->
            "project-files(:foundry-java-gradle-plugin:pluginUnderTestMetadata)"
        displayName == "file collection" &&
            buildTaskPaths ==
            listOf(
                ":foundry-java-kotlin:classes",
                ":foundry-java-kotlin:compileJava",
                ":foundry-java-kotlin:compileKotlin",
            ) ->
            "project-files(:foundry-java-kotlin:main-output)"
        else ->
            error(
                "Unsupported file collection dependency: " +
                    "displayName=$displayName, type=${declaredFiles.javaClass.name}, " +
                    "buildDependencies=$buildTaskPaths",
            )
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
    abstract val requiredLockFilePaths: SetProperty<String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

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
    abstract val requiredHostNeutralProjectPaths: SetProperty<String>

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
        val rootDirectory = repositoryRoot.get().asFile
        val configuredLockFilePaths =
            lockFiles.files
                .map { it.relativeTo(rootDirectory).invariantSeparatorsPath }
                .toSet()
        check(configuredLockFilePaths == requiredLockFilePaths.get()) {
            "Configured lock files differ from the exact repository contract: $configuredLockFilePaths"
        }
        requiredLockFilePaths.get().forEach { relativePath ->
            check(rootDirectory.resolve(relativePath).isFile) {
                "Required dependency lock must exist as a regular file: $relativePath"
            }
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
        check(
            expectedBoundaryDependencies.get().keys == requiredHostNeutralProjectPaths.get(),
        ) {
            "Every host-neutral project must declare an exact dependency contract."
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

    @get:Input
    abstract val expectedNativeLibraries: SetProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectedFixedConfiguration: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectedConsumerRules: RegularFileProperty

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
            val actualNativeLibraries =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".so") }
                    .toSet()
            check(actualNativeLibraries == expectedNativeLibraries.get()) {
                "Release AAR native payload differs from the four-ABI bridge contract. " +
                    "Expected ${expectedNativeLibraries.get()}, found $actualNativeLibraries."
            }
            val classesJar = zip.getEntry("classes.jar")
            check(classesJar != null) { "Release AAR must contain classes.jar." }
            val nestedEntries =
                ZipInputStream(zip.getInputStream(classesJar)).use { nestedZip ->
                    buildList {
                        while (true) {
                            val entry = nestedZip.nextEntry ?: break
                            if (!entry.isDirectory) {
                                add(entry.name to nestedZip.readBytes())
                            }
                        }
                    }
                }
            val actualClasses =
                nestedEntries
                    .map { it.first }
                    .filter { it.endsWith(".class") }
                    .toSet()
            check(actualClasses == allowedClasses.get()) {
                "Release AAR classes.jar must contain exactly the allowed bootstrap classes. " +
                    "Expected ${allowedClasses.get()}, found $actualClasses."
            }
            val fixedConfigurations =
                nestedEntries.filter { it.first == "FoundryJava.foundryextension" }
            check(fixedConfigurations.size == 1) {
                "Release AAR classes.jar must contain exactly one fixed " +
                    "FoundryJava.foundryextension; found ${fixedConfigurations.size}."
            }
            check(
                fixedConfigurations.single().second.contentEquals(
                    expectedFixedConfiguration.get().asFile.readBytes(),
                ),
            ) {
                "Release AAR fixed FoundryJava.foundryextension differs from the checked-in contract."
            }
            check(
                zip.entries().asSequence().none {
                    it.name == "FoundryJava.foundryextension"
                },
            ) {
                "Release AAR must not duplicate FoundryJava.foundryextension outside classes.jar."
            }
            val packagedManifest = zip.getEntry("AndroidManifest.xml")
            check(packagedManifest != null) { "Release AAR must contain AndroidManifest.xml." }
            val packagedManifestText =
                zip
                    .getInputStream(packagedManifest)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            check(!packagedManifestText.contains("<application")) {
                "Release AAR manifest must not declare an application."
            }
            check(!packagedManifestText.contains("<provider")) {
                "Release AAR manifest must not declare a provider."
            }

            val packagedConsumerRules =
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name == "proguard.txt" }
                    .toList()
            check(packagedConsumerRules.size == 1) {
                "Release AAR must contain exactly one consumer-rule payload; " +
                    "found ${packagedConsumerRules.size}."
            }
            check(
                zip
                    .getInputStream(packagedConsumerRules.single())
                    .readBytes()
                    .contentEquals(expectedConsumerRules.get().asFile.readBytes()),
            ) {
                "Release AAR consumer rules differ from the checked-in narrow contract."
            }
        }
    }
}

abstract class VerifyPublications : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val publicationVersion: Property<String>

    @get:Input
    abstract val expectedPoms: MapProperty<String, String>

    @get:Input
    abstract val expectedModules: MapProperty<String, String>

    @get:Input
    abstract val expectedPomDependencies: MapProperty<String, String>

    @get:Input
    abstract val expectedModuleDependencies: MapProperty<String, String>

    @get:Input
    abstract val expectedModuleArtifactNames: MapProperty<String, String>

    @get:Input
    abstract val expectedArtifacts: ListProperty<String>

    @TaskAction
    fun verifyPublishedFiles() {
        val repository = repositoryDirectory.get().asFile
        val version = publicationVersion.get()
        val versionDirectorySuffix = "/$version"
        val poms = expectedPoms.get()
        val modules = expectedModules.get()
        val pomDependencies = expectedPomDependencies.get()
        val moduleDependencies = expectedModuleDependencies.get()
        val moduleArtifactNames = expectedModuleArtifactNames.get()
        val artifacts = expectedArtifacts.get()
        // The main-artifact topology and the classified archives are counted over separate
        // collections so each stays an exact check: eight main JARs and one AAR, plus sources and
        // Javadoc for every one of the nine published modules.
        val mainArtifacts = artifacts.filter { it.split('|')[3].isEmpty() }
        val jarCount = mainArtifacts.count { it.split('|')[2] == "jar" }
        val aarCount = mainArtifacts.count { it.split('|')[2] == "aar" }
        val sourcesJarCount = artifacts.count { it.split('|')[3] == "sources" }
        val javadocJarCount = artifacts.count { it.split('|')[3] == "javadoc" }

        check(poms.size == 10) { "Bootstrap topology must contain exactly 10 POM publications." }
        check(modules.size == 9) { "Bootstrap topology must contain exactly 9 Gradle module publications." }
        check(jarCount == 8 && aarCount == 1) {
            "Bootstrap topology must contain exactly 8 JARs and 1 AAR."
        }
        check(sourcesJarCount == 9 && javadocJarCount == 9) {
            "Every published module must publish a sources and a Javadoc archive."
        }
        check(pomDependencies.keys == poms.keys) {
            "Every expected POM must declare an exact dependency contract."
        }
        check(moduleDependencies.keys == modules.keys) {
            "Every expected Gradle module must declare an exact dependency contract."
        }
        check(moduleArtifactNames.keys == modules.keys) {
            "Every expected Gradle module must declare exact logical artifact names."
        }

        // build/repository can accumulate publications for other Foundry versions across
        // successive invocations with different -PfoundryVersion values. Scoping every
        // comparison to versionDirectorySuffix keeps this an exact check of the topology under
        // test without being tripped up by unrelated versions already staged on disk.
        fun publishedDirectories(extension: String) =
            repository
                .walkTopDown()
                .filter { it.isFile && it.extension == extension }
                .map { it.parentFile.relativeTo(repository).invariantSeparatorsPath }
                .filter { it.endsWith(versionDirectorySuffix) }
                .toSet()

        check(publishedDirectories("pom") == poms.keys) {
            "Published POM coordinates differ from the exact bootstrap topology."
        }
        check(publishedDirectories("module") == modules.keys) {
            "Published Gradle module coordinates differ from the exact bootstrap topology."
        }
        val expectedArchiveDirectories =
            artifacts
                .map {
                    val artifactSpec = it.split('|')
                    "${artifactSpec[0]}|${artifactSpec[2]}"
                }.toSet()
        val actualArchiveDirectories =
            repository
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("jar", "aar") }
                .map {
                    "${it.parentFile.relativeTo(repository).invariantSeparatorsPath}|${it.extension}"
                }.filter { it.substringBeforeLast('|').endsWith(versionDirectorySuffix) }
                .toSet()
        check(actualArchiveDirectories == expectedArchiveDirectories) {
            "Published archive coordinates differ from the exact bootstrap topology."
        }

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

        fun directChildValue(
            element: Element,
            name: String,
        ): String? =
            (0 until element.childNodes.length)
                .map { element.childNodes.item(it) }
                .filterIsInstance<Element>()
                .firstOrNull { it.tagName == name }
                ?.textContent

        fun decodeDependencies(encodedDependencies: String) =
            if (encodedDependencies.isEmpty()) {
                emptySet()
            } else {
                encodedDependencies.split(';').toSet()
            }

        poms.forEach { (relativeDirectory, encodedCoordinates) ->
            val coordinates = encodedCoordinates.split('|')
            val pom = publishedFile(relativeDirectory, coordinates[1], "pom")
            check(pom.length() > 0L) { "Generated POM is empty: $pom" }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
            val root = document.documentElement
            check(directChildValue(root, "groupId") == coordinates[0]) {
                "POM groupId differs for $pom."
            }
            check(directChildValue(root, "artifactId") == coordinates[1]) {
                "POM artifactId differs for $pom."
            }
            check(directChildValue(root, "version") == coordinates[2]) {
                "POM version differs for $pom."
            }
            val actualDependencies =
                document
                    .getElementsByTagName("dependency")
                    .let { nodes ->
                        (0 until nodes.length)
                            .map { nodes.item(it) }
                            .filterIsInstance<Element>()
                            .map { dependency ->
                                listOf(
                                    directChildValue(dependency, "scope") ?: "compile",
                                    directChildValue(dependency, "groupId"),
                                    directChildValue(dependency, "artifactId"),
                                    directChildValue(dependency, "version"),
                                ).joinToString("|")
                            }.toSet()
                    }
            check(actualDependencies == decodeDependencies(pomDependencies.getValue(relativeDirectory))) {
                "POM dependencies differ for $pom. Expected " +
                    "${pomDependencies.getValue(relativeDirectory)}, found $actualDependencies."
            }
        }
        modules.forEach { (relativeDirectory, encodedCoordinates) ->
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
            val actualDependencies =
                Regex(
                    """"group": "([^"]+)",\s*"module": "([^"]+)",\s*"version": \{\s*"requires": "([^"]+)"""",
                ).findAll(content)
                    .map { match ->
                        match.groupValues.drop(1).joinToString("|")
                    }.toSet()
            check(
                actualDependencies ==
                    decodeDependencies(moduleDependencies.getValue(relativeDirectory)),
            ) {
                "Gradle module dependencies differ for $module. Expected " +
                    "${moduleDependencies.getValue(relativeDirectory)}, found $actualDependencies."
            }
            val actualArtifactNames =
                Regex(""""name": "([^"]+\.(?:jar|aar))"""")
                    .findAll(content)
                    .map { it.groupValues[1] }
                    .toSet()
            check(
                actualArtifactNames ==
                    decodeDependencies(moduleArtifactNames.getValue(relativeDirectory)),
            ) {
                "Gradle module artifact names differ for $module. Expected " +
                    "${moduleArtifactNames.getValue(relativeDirectory)}, found $actualArtifactNames."
            }
        }
        artifacts.forEach { encodedArtifact ->
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

// Release signing material reaches Gradle as ORG_GRADLE_PROJECT_signingKey and
// ORG_GRADLE_PROJECT_signingPassword environment values, never as a command-line -P argument and
// never as a checked-in property, so no key or password can appear in a process listing or a log.
val releaseSigningKey = providers.gradleProperty("signingKey")
val releaseSigningPassword = providers.gradleProperty("signingPassword").orElse("")
val releaseStagingRepository = providers.gradleProperty("foundryStagingRepository")

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

val generatorOnlyConsumer by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(generatorOnlyConsumer.name, project(":foundry-java-generator"))
}

val compileGeneratorOnlyConsumer by tasks.registering(JavaCompile::class) {
    source = fileTree("src/generatorOnlyConsumer/java") { include("**/*.java") }
    classpath = generatorOnlyConsumer
    destinationDirectory = layout.buildDirectory.dir("classes/generatorOnlyConsumer")
    options.release = 17
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

configure<SpotlessExtension> {
    // Spotless's git-aware default can capture cold-only JGit state as a Gradle configuration
    // cache input. Explicit LF matches the repository policy and avoids that clean-state miss.
    // Related upstream regression: https://github.com/diffplug/spotless/issues/2431
    lineEndings = LineEnding.UNIX
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
val requiredHostNeutralProjects = requiredProjects - "foundry-java-android"
val requiredHostNeutralProjectPaths = requiredHostNeutralProjects.map { ":$it" }.toSet()
val requiredLockFilePaths =
    setOf(
        "gradle.lockfile",
        "settings-gradle.lockfile",
        "foundry-java-android/gradle.lockfile",
        "foundry-java-annotations/gradle.lockfile",
        "foundry-java-api-model/gradle.lockfile",
        "foundry-java-generator/gradle.lockfile",
        "foundry-java-gradle-plugin/gradle.lockfile",
        "foundry-java-kotlin/gradle.lockfile",
        "foundry-java-processor/gradle.lockfile",
        "foundry-java-runtime/gradle.lockfile",
        "foundry-java-test/gradle.lockfile",
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
        "coreLibraryDesugaring",
        "debugAnnotationProcessorClasspath",
        "debugCompileClasspath",
        "debugRuntimeClasspath",
        "releaseAnnotationProcessorClasspath",
        "releaseCompileClasspath",
        "releaseRuntimeClasspath",
    )
val requiredGradleApiFileSignature = "gradle-api-files"
val requiredGradleTestKitFileSignature = "gradle-test-kit-files"
val requiredGradlePluginRuntimeFileSignature =
    "project-files(:foundry-java-gradle-plugin:pluginUnderTestMetadata)"
val requiredBoundaryDependencies =
    mapOf(
        ":foundry-java-api-model" to
            listOf(
                "api=project(:foundry-java-annotations)",
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-annotations" to
            listOf(
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-generator" to
            listOf(
                "api=project(:foundry-java-api-model)",
                "implementation=project(:foundry-java-annotations)",
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-gradle-plugin" to
            listOf(
                "api=$requiredGradleApiFileSignature",
                "compileOnly=com.android.tools.build:gradle-api",
                "testImplementation=$requiredGradleTestKitFileSignature",
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=$requiredGradlePluginRuntimeFileSignature",
                "testRuntimeOnly=com.android.tools.build:gradle",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-kotlin" to
            listOf(
                "api=project(:foundry-java-runtime)",
                "implementation=org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "javaOnlyConsumerClasspath=project(:foundry-java-runtime)",
                "kotlinBuildToolsApiClasspath=org.jetbrains.kotlin:kotlin-build-tools-impl",
                "kotlinCompilerPluginClasspathKotlinOverJavaConsumer=org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable",
                "kotlinCompilerPluginClasspathMain=org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable",
                "kotlinCompilerPluginClasspathMixedConsumer=org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable",
                "kotlinCompilerPluginClasspathTest=org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable",
                "kotlinOverJavaConsumerImplementation=org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "kotlinOverJavaConsumerImplementation=project(:foundry-java-runtime)",
                "kotlinOverJavaConsumerImplementation=project-files(:foundry-java-kotlin:main-output)",
                "mixedConsumerImplementation=org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "mixedConsumerImplementation=project(:foundry-java-runtime)",
                "mixedConsumerImplementation=project-files(:foundry-java-kotlin:main-output)",
                "testImplementation=org.jetbrains.kotlin:kotlin-test",
                "testImplementation=org.jetbrains.kotlinx:kotlinx-coroutines-test",
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-processor" to
            listOf(
                "implementation=project(:foundry-java-annotations)",
                "testImplementation=project(:foundry-java-generator)",
                "testImplementation=project(:foundry-java-runtime)",
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-test" to
            listOf(
                "api=project(:foundry-java-runtime)",
                "testImplementation=org.junit:junit-bom",
                "testImplementation=org.junit.jupiter:junit-jupiter",
                "testRuntimeOnly=org.junit.jupiter:junit-jupiter-engine",
                "testRuntimeOnly=org.junit.platform:junit-platform-launcher",
            ),
        ":foundry-java-runtime" to
            listOf(
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
val requiredPublicationVersion = version.toString()

fun publicationDirectory(
    groupId: String,
    artifactId: String,
) = "${groupId.replace('.', '/')}/$artifactId/$requiredPublicationVersion"

val androidPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-android")
val annotationsPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-annotations")
val apiModelPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-api-model")
val generatorPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-generator")
val gradlePluginPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-gradle-plugin")
val kotlinPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-kotlin")
val processorPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-processor")
val runtimePublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-runtime")
val testPublicationDirectory =
    publicationDirectory(requiredGroupCoordinate, "foundry-java-test")
val pluginMarkerGroup = "games.cafecito.foundry.java"
val pluginMarkerArtifact = "games.cafecito.foundry.java.gradle.plugin"
val pluginMarkerPublicationDirectory =
    publicationDirectory(pluginMarkerGroup, pluginMarkerArtifact)

val requiredPublicationCoordinates =
    mapOf(
        androidPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-android|$requiredPublicationVersion",
        annotationsPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
        apiModelPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-api-model|$requiredPublicationVersion",
        generatorPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-generator|$requiredPublicationVersion",
        gradlePluginPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-gradle-plugin|$requiredPublicationVersion",
        kotlinPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-kotlin|$requiredPublicationVersion",
        processorPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-processor|$requiredPublicationVersion",
        runtimePublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
        testPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-test|$requiredPublicationVersion",
        pluginMarkerPublicationDirectory to
            "$pluginMarkerGroup|$pluginMarkerArtifact|$requiredPublicationVersion",
    )
val requiredPomDependencies =
    mapOf(
        androidPublicationDirectory to
            "compile|$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
        annotationsPublicationDirectory to "",
        apiModelPublicationDirectory to
            "compile|$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
        generatorPublicationDirectory to
            listOf(
                "compile|$requiredGroupCoordinate|foundry-java-api-model|$requiredPublicationVersion",
                "runtime|$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
            ).sorted()
                .joinToString(";"),
        gradlePluginPublicationDirectory to "",
        kotlinPublicationDirectory to
            listOf(
                "compile|$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
                "compile|org.jetbrains.kotlin|kotlin-stdlib|2.0.21",
                "runtime|org.jetbrains.kotlinx|kotlinx-coroutines-core-jvm|1.9.0",
            ).sorted()
                .joinToString(";"),
        processorPublicationDirectory to
            "runtime|$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
        runtimePublicationDirectory to
            listOf(
                "compile|$requiredGroupCoordinate|foundry-java-api-model|$requiredPublicationVersion",
                "runtime|$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
            ).sorted()
                .joinToString(";"),
        testPublicationDirectory to
            "compile|$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
        pluginMarkerPublicationDirectory to
            "compile|$requiredGroupCoordinate|foundry-java-gradle-plugin|$requiredPublicationVersion",
    )
val requiredModuleDependencies =
    mapOf(
        androidPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
        annotationsPublicationDirectory to "",
        apiModelPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
        generatorPublicationDirectory to
            listOf(
                "$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
                "$requiredGroupCoordinate|foundry-java-api-model|$requiredPublicationVersion",
            ).sorted()
                .joinToString(";"),
        gradlePluginPublicationDirectory to "",
        kotlinPublicationDirectory to
            listOf(
                "$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
                "org.jetbrains.kotlin|kotlin-stdlib|2.0.21",
                "org.jetbrains.kotlinx|kotlinx-coroutines-core|1.9.0",
            ).sorted()
                .joinToString(";"),
        processorPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
        runtimePublicationDirectory to
            listOf(
                "$requiredGroupCoordinate|foundry-java-annotations|$requiredPublicationVersion",
                "$requiredGroupCoordinate|foundry-java-api-model|$requiredPublicationVersion",
            ).sorted()
                .joinToString(";"),
        testPublicationDirectory to
            "$requiredGroupCoordinate|foundry-java-runtime|$requiredPublicationVersion",
    )
// Every published module carries its main archive plus the sources and Javadoc archives Maven
// Central requires, and Gradle module metadata names all three.
fun moduleArtifactNames(
    artifactId: String,
    mainExtension: String,
) = listOf(
    "$artifactId-$requiredPublicationVersion.$mainExtension",
    "$artifactId-$requiredPublicationVersion-sources.jar",
    "$artifactId-$requiredPublicationVersion-javadoc.jar",
).joinToString(";")

// The Android release Javadoc is published as a Maven artifact rather than a Gradle variant, because
// the Android library plugin's own Javadoc generation cannot read Java records in a dependency. It is
// therefore absent from Gradle module metadata and present in the Maven layout; see
// foundry-java-android/build.gradle.kts.
val androidModuleArtifactNames =
    listOf(
        "foundry-java-android-$requiredPublicationVersion.aar",
        "foundry-java-android-$requiredPublicationVersion-sources.jar",
    ).joinToString(";")

val requiredModuleArtifactNames =
    mapOf(
        androidPublicationDirectory to androidModuleArtifactNames,
        annotationsPublicationDirectory to moduleArtifactNames("foundry-java-annotations", "jar"),
        apiModelPublicationDirectory to moduleArtifactNames("foundry-java-api-model", "jar"),
        generatorPublicationDirectory to moduleArtifactNames("foundry-java-generator", "jar"),
        gradlePluginPublicationDirectory to
            moduleArtifactNames("foundry-java-gradle-plugin", "jar"),
        kotlinPublicationDirectory to moduleArtifactNames("foundry-java-kotlin", "jar"),
        processorPublicationDirectory to moduleArtifactNames("foundry-java-processor", "jar"),
        runtimePublicationDirectory to moduleArtifactNames("foundry-java-runtime", "jar"),
        testPublicationDirectory to moduleArtifactNames("foundry-java-test", "jar"),
    )
val requiredMainPublicationArtifacts =
    listOf(
        "$androidPublicationDirectory|foundry-java-android|aar||" +
            "foundry-java-android-$requiredPublicationVersion.aar",
        "$annotationsPublicationDirectory|foundry-java-annotations|jar||" +
            "foundry-java-annotations-$requiredPublicationVersion.jar",
        "$apiModelPublicationDirectory|foundry-java-api-model|jar||" +
            "foundry-java-api-model-$requiredPublicationVersion.jar",
        "$generatorPublicationDirectory|foundry-java-generator|jar||" +
            "foundry-java-generator-$requiredPublicationVersion.jar",
        "$gradlePluginPublicationDirectory|foundry-java-gradle-plugin|jar||" +
            "foundry-java-gradle-plugin-$requiredPublicationVersion.jar",
        "$kotlinPublicationDirectory|foundry-java-kotlin|jar||" +
            "foundry-java-kotlin-$requiredPublicationVersion.jar",
        "$processorPublicationDirectory|foundry-java-processor|jar||" +
            "foundry-java-processor-$requiredPublicationVersion.jar",
        "$runtimePublicationDirectory|foundry-java-runtime|jar||" +
            "foundry-java-runtime-$requiredPublicationVersion.jar",
        "$testPublicationDirectory|foundry-java-test|jar||" +
            "foundry-java-test-$requiredPublicationVersion.jar",
    )
val requiredClassifiedPublicationArtifacts =
    listOf(
        "$androidPublicationDirectory|foundry-java-android|jar|sources|" +
            "foundry-java-android-$requiredPublicationVersion-sources.jar",
        "$androidPublicationDirectory|foundry-java-android|jar|javadoc|" +
            "foundry-java-android-$requiredPublicationVersion-javadoc.jar",
        "$annotationsPublicationDirectory|foundry-java-annotations|jar|sources|" +
            "foundry-java-annotations-$requiredPublicationVersion-sources.jar",
        "$annotationsPublicationDirectory|foundry-java-annotations|jar|javadoc|" +
            "foundry-java-annotations-$requiredPublicationVersion-javadoc.jar",
        "$apiModelPublicationDirectory|foundry-java-api-model|jar|sources|" +
            "foundry-java-api-model-$requiredPublicationVersion-sources.jar",
        "$apiModelPublicationDirectory|foundry-java-api-model|jar|javadoc|" +
            "foundry-java-api-model-$requiredPublicationVersion-javadoc.jar",
        "$generatorPublicationDirectory|foundry-java-generator|jar|sources|" +
            "foundry-java-generator-$requiredPublicationVersion-sources.jar",
        "$generatorPublicationDirectory|foundry-java-generator|jar|javadoc|" +
            "foundry-java-generator-$requiredPublicationVersion-javadoc.jar",
        "$gradlePluginPublicationDirectory|foundry-java-gradle-plugin|jar|sources|" +
            "foundry-java-gradle-plugin-$requiredPublicationVersion-sources.jar",
        "$gradlePluginPublicationDirectory|foundry-java-gradle-plugin|jar|javadoc|" +
            "foundry-java-gradle-plugin-$requiredPublicationVersion-javadoc.jar",
        "$kotlinPublicationDirectory|foundry-java-kotlin|jar|sources|" +
            "foundry-java-kotlin-$requiredPublicationVersion-sources.jar",
        "$kotlinPublicationDirectory|foundry-java-kotlin|jar|javadoc|" +
            "foundry-java-kotlin-$requiredPublicationVersion-javadoc.jar",
        "$processorPublicationDirectory|foundry-java-processor|jar|sources|" +
            "foundry-java-processor-$requiredPublicationVersion-sources.jar",
        "$processorPublicationDirectory|foundry-java-processor|jar|javadoc|" +
            "foundry-java-processor-$requiredPublicationVersion-javadoc.jar",
        "$runtimePublicationDirectory|foundry-java-runtime|jar|sources|" +
            "foundry-java-runtime-$requiredPublicationVersion-sources.jar",
        "$runtimePublicationDirectory|foundry-java-runtime|jar|javadoc|" +
            "foundry-java-runtime-$requiredPublicationVersion-javadoc.jar",
        "$testPublicationDirectory|foundry-java-test|jar|sources|" +
            "foundry-java-test-$requiredPublicationVersion-sources.jar",
        "$testPublicationDirectory|foundry-java-test|jar|javadoc|" +
            "foundry-java-test-$requiredPublicationVersion-javadoc.jar",
    )
val requiredPublicationArtifacts =
    requiredMainPublicationArtifacts + requiredClassifiedPublicationArtifacts
val allowedBootstrapAndroidClasses =
    setOf(
        "games/cafecito/foundry/java/FoundryJavaInitializer\$DiagnosticCallbacks.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer\$DiagnosticSink.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer\$NativeBootstrap.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer\$NativeLibrary.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer\$NativeLoader.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer\$PrimingState\$Phase.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer\$PrimingState.class",
        "games/cafecito/foundry/java/FoundryJavaInitializer.class",
        "games/cafecito/foundry/java/FoundryJavaStartupProvider\$Primer.class",
        "games/cafecito/foundry/java/FoundryJavaStartupProvider.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$1.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$2.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$JniNativeGateway.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$NativeDecodedObject.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$NativeGateway.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$NativeVariantSnapshot.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$SignalBackend\$ConnectedCallable.class",
        "games/cafecito/foundry/java/FoundryNativeEngine\$SignalBackend.class",
        "games/cafecito/foundry/java/FoundryNativeEngine.class",
    )
val requiredAndroidNativeLibraries =
    setOf(
        "jni/armeabi-v7a/libfoundry_java.so",
        "jni/arm64-v8a/libfoundry_java.so",
        "jni/x86/libfoundry_java.so",
        "jni/x86_64/libfoundry_java.so",
    )

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

val exactRequiredLockFilePaths = requiredLockFilePaths
val exactRequiredHostNeutralProjectPaths = requiredHostNeutralProjectPaths
val verifyRepositoryModel =
    tasks.register<VerifyRepositoryModel>("verifyRepositoryModel") {
        group = "verification"
        description = "Verifies the captured, configuration-cache-safe repository model."
        expectedProjectNames.set(requiredProjects)
        requiredGroup.set(requiredGroupCoordinate)
        expectedBoundaryDependencies.set(
            requiredBoundaryDependencies.mapValues { (_, dependencies) -> dependencies.sorted().joinToString(",") },
        )
        requiredHostNeutralProjectPaths.set(exactRequiredHostNeutralProjectPaths)
        expectedPublicationNames.set(requiredPublicationNames)
        requiredPluginId.set(requiredPluginIdentifier)
        wrapperProperties.set(layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties"))
        requiredLockFilePaths.set(exactRequiredLockFilePaths)
        repositoryRoot.set(layout.projectDirectory)
        lockFiles.from(exactRequiredLockFilePaths.map { layout.projectDirectory.file(it) })
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
        expectedNativeLibraries.set(requiredAndroidNativeLibraries)
        expectedFixedConfiguration.set(
            project(":foundry-java-android")
                .layout.projectDirectory
                .file("src/main/resources/FoundryJava.foundryextension"),
        )
        expectedConsumerRules.set(
            project(":foundry-java-android")
                .layout.projectDirectory
                .file("src/main/consumer-rules.pro"),
        )
    }

val verifyPublications =
    tasks.register<VerifyPublications>("verifyPublications") {
        group = "verification"
        description = "Publishes and validates every configured bootstrap Maven publication."
        repositoryDirectory.set(layout.buildDirectory.dir("repository"))
        publicationVersion.set(requiredPublicationVersion)
        expectedPoms.set(requiredPublicationCoordinates)
        expectedModules.set(
            requiredPublicationCoordinates.filterKeys { it != pluginMarkerPublicationDirectory },
        )
        expectedPomDependencies.set(requiredPomDependencies)
        expectedModuleDependencies.set(requiredModuleDependencies)
        expectedModuleArtifactNames.set(requiredModuleArtifactNames)
        expectedArtifacts.set(requiredPublicationArtifacts)
        dependsOn(requiredProjects.map { ":$it:publishAllPublicationsToBootstrapRepository" })
    }

tasks.register("verifyRepositoryContract") {
    group = "verification"
    description = "Verifies repository model, publications, boundaries, and Android archive contents."
    dependsOn(verifyRepositoryModel, verifyAndroidAar, verifyPublications)
}

tasks.named("check") {
    dependsOn("spotlessCheck", "verifyRepositoryContract", compileGeneratorOnlyConsumer)
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
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<Javadoc>().configureEach {
            // Javadoc stamps its generation date into every page, which would make the Javadoc
            // archive differ between two builds of the same commit.
            (options as StandardJavadocDocletOptions).addBooleanOption("notimestamp", true)
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
                maven {
                    // The release pipeline stages into a directory it owns, verifies it completely,
                    // and only then uploads. Nothing in this build ever talks to a remote
                    // repository: see gradle/stage-release.sh and gradle/upload-staged-release.sh.
                    name = "staging"
                    url =
                        file(
                            releaseStagingRepository.getOrElse(
                                rootProject.layout.buildDirectory
                                    .dir("release-staging/repository")
                                    .get()
                                    .asFile
                                    .absolutePath,
                            ),
                        ).toURI()
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
                    developers {
                        developer {
                            name.set("Cafecito Games")
                            url.set("https://github.com/cafecito-games")
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

        // Signing is applied only when release key material is supplied, so an ordinary `check` run
        // configures no signing tasks at all and the configuration cache it stores stays free of
        // credential-shaped inputs. gradle/verify-staged-release.sh is what proves a release is
        // signed; it verifies every staged file against the release public key before any upload.
        if (releaseSigningKey.isPresent) {
            pluginManager.apply("signing")
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(releaseSigningKey.get(), releaseSigningPassword.get())
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}

gradle.projectsEvaluated {
    verifyRepositoryModel.configure {
        actualProjectNames.set(subprojects.map { it.name }.toSet())
        projectGroups.set(allprojects.associate { it.path to it.group.toString() })
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
                                when (dependency) {
                                    is ProjectDependency -> "project(:${dependency.name})"
                                    is FileCollectionDependency ->
                                        stableBoundaryFileSignature(dependency)
                                    else -> "${dependency.group}:${dependency.name}"
                                }
                            "${configuration.name}=$coordinate"
                        }
                    }.sorted()
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
}
