import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer

dependencies {
    api(project(":foundry-java-api-model"))
    implementation(project(":foundry-java-annotations"))
}

val acceptedApiDirectory = rootProject.layout.projectDirectory.dir("api/current")
val generatedApiSources = layout.buildDirectory.dir("generated/sources/foundryApi/main")
val generatedCompatibilityManifest =
    layout.buildDirectory.file("generated/foundryApi/compatibility-manifest.json")
val generatedRealizationMap =
    layout.buildDirectory.file("generated/foundryApi/realization-map.tsv")
val generatedSurfaceManifest =
    layout.buildDirectory.file("generated/foundryApi/foundry-java-surface-manifest.json")

// The binding identity is fixed; the binding version is the published Foundry-Java version, so the
// manifest a release publishes names the binding release it describes.
val bindingVersion = project.version.toString()
val generatorProject = project(":foundry-java-generator")
val apiModelProject = project(":foundry-java-api-model")
val annotationsProject = project(":foundry-java-annotations")
val generatorJar = generatorProject.tasks.named("jar")
val apiModelJar = apiModelProject.tasks.named("jar")
val annotationsJar = annotationsProject.tasks.named("jar")

// Exec and JavaExec put `args` into the build cache key but not `workingDir`, so every path handed to
// a cacheable task here is relative to the repository root. Absolute arguments made each stored entry
// private to the checkout that produced it, which is the same defect as declaring no outputs at all:
// the entry exists and can never be reused. gradle/verify-build-cache-portability.sh proves the fix.
fun rootRelative(path: File): String = path.relativeTo(rootDir).invariantSeparatorsPath

val generateFoundryApi =
    tasks.register<JavaExec>("generateFoundryApi") {
        group = "build"
        description = "Generates the exhaustive public Java API from checked accepted inputs."
        dependsOn(generatorJar, apiModelJar, annotationsJar)
        classpath = files(generatorJar, apiModelJar, annotationsJar)
        mainClass.set("games.cafecito.foundry.generator.FoundrySourceGenerator")

        inputs
            .files(
                acceptedApiDirectory.file("extension_api.json"),
                acceptedApiDirectory.file("foundry_extension_interface.h"),
                acceptedApiDirectory.file("compatibility-manifest.json"),
                acceptedApiDirectory.file("provenance.json"),
            ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("generatorMainClass", mainClass)
        inputs.property("bindingVersion", bindingVersion)
        outputs.dir(generatedApiSources)
        outputs.file(generatedCompatibilityManifest)
        outputs.file(generatedRealizationMap)
        outputs.file(generatedSurfaceManifest)
        outputs.cacheIf("accepted API generation is deterministic") { true }

        workingDir = rootDir
        args(
            rootRelative(acceptedApiDirectory.asFile),
            rootRelative(generatedApiSources.get().asFile),
            rootRelative(generatedCompatibilityManifest.get().asFile),
            rootRelative(generatedRealizationMap.get().asFile),
            rootRelative(generatedSurfaceManifest.get().asFile),
            bindingVersion,
        )
    }

extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(generatedApiSources)
}

tasks.named("compileJava") {
    dependsOn(generateFoundryApi)
}

tasks.named("javadoc") {
    dependsOn(generateFoundryApi)
}

// The published sources archive carries the generated API, so it is produced from the same generated
// sources the compiler and Javadoc consume.
tasks.named("sourcesJar") {
    dependsOn(generateFoundryApi)
}

val runtimeApiBaseline =
    layout.projectDirectory.file("api/foundry-java-runtime.api")
val runtimeApiVerifier =
    rootProject.layout.projectDirectory.file("gradle/verify-runtime-api.sh")
val runtimeClasses = layout.buildDirectory.dir("classes/java/main")
val runtimeApiReport = layout.buildDirectory.file("reports/foundry-runtime-api/actual.api")

// javap decides what this inventory looks like, and the verifier used to resolve it from PATH, so the
// declared inputs alone did not determine the result. The task now runs the configured Java
// toolchain's javap and names that toolchain's version in the key, which is the condition for a cache
// hit being indistinguishable from an execution. The absolute JDK path travels in the environment,
// which Exec treats as @Internal, so it stays out of the key.
val javaToolchainLauncher =
    extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

val verifyRuntimeApi =
    tasks.register<Exec>("verifyRuntimeApi") {
        group = "verification"
        description = "Checks the sorted public javap inventory against the frozen runtime API."
        dependsOn(tasks.named("compileJava"))
        inputs.file(runtimeApiBaseline).withPathSensitivity(PathSensitivity.NONE)
        inputs.file(runtimeApiVerifier).withPathSensitivity(PathSensitivity.NONE)
        inputs
            .dir(runtimeClasses)
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property(
            "javaRuntimeVersion",
            javaToolchainLauncher.map { it.metadata.javaRuntimeVersion },
        )
        // The inventory the verifier computed is the whole result of this task. Publishing it as a
        // declared output is what makes the task cacheable, and it is also the artifact a developer
        // needs when the frozen baseline and the compiled classes disagree.
        outputs.file(runtimeApiReport).withPropertyName("actualApiInventory")
        outputs.cacheIf("the public javap inventory is determined by its declared inputs") { true }
        environment(
            "FOUNDRY_JDK_BIN",
            javaToolchainLauncher
                .get()
                .metadata.installationPath
                .dir("bin")
                .asFile.absolutePath,
        )
        workingDir = rootDir
        executable = "bash"
        args(
            rootRelative(runtimeApiVerifier.asFile),
            rootRelative(runtimeClasses.get().asFile),
            rootRelative(runtimeApiBaseline.asFile),
            rootRelative(runtimeApiReport.get().asFile),
        )
    }

val realizationAccountingBaseline =
    layout.projectDirectory.file("api/foundry-java-realization-accounting.txt")
val realizationReportDirectory = layout.buildDirectory.dir("reports/foundry-realization")

// The parity oracle is anchored to the vendored engine API: it compares the vendored compatibility
// manifest, the generated realization map, and the compiled generated surface. No sibling binding
// participates.
val verifyGeneratedRealization =
    tasks.register<JavaExec>("verifyGeneratedRealization") {
        group = "verification"
        description = "Verifies every accepted engine entity against the generated Java surface."
        dependsOn(generateFoundryApi, tasks.named("compileJava"))
        classpath = files(generatorJar, apiModelJar, annotationsJar, runtimeClasses)
        mainClass.set("games.cafecito.foundry.generator.RealizationVerifier")
        // A measured budget, not a ceiling picked for safety: verification exhausts 256m inside
        // surface-manifest parsing and passes at 288m against the pinned engine API. Holding it
        // explicit keeps the requirement identical on every machine, because the JVM default is a
        // quarter of physical RAM. Raising it requires a fresh measurement — see
        // docs/binding-neutral-surface-manifest.md.
        maxHeapSize = "512m"

        inputs
            .files(
                acceptedApiDirectory.file("extension_api.json"),
                acceptedApiDirectory.file("foundry_extension_interface.h"),
                acceptedApiDirectory.file("compatibility-manifest.json"),
                acceptedApiDirectory.file("provenance.json"),
                generatedRealizationMap,
                generatedSurfaceManifest,
                realizationAccountingBaseline,
            ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs
            .dir(runtimeClasses)
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("bindingVersion", bindingVersion)
        outputs.dir(realizationReportDirectory).withPropertyName("realizationReport")
        outputs.cacheIf("the parity oracle is determined by the pinned engine API") { true }

        workingDir = rootDir
        args(
            rootRelative(acceptedApiDirectory.asFile),
            rootRelative(generatedRealizationMap.get().asFile),
            rootRelative(runtimeClasses.get().asFile),
            rootRelative(realizationAccountingBaseline.asFile),
            rootRelative(realizationReportDirectory.get().asFile),
            rootRelative(generatedSurfaceManifest.get().asFile),
            bindingVersion,
        )
    }

tasks.named("check") {
    dependsOn(
        verifyRuntimeApi,
        verifyGeneratedRealization,
        tasks.named("javadoc"),
        generatorProject.tasks.named("test"),
    )
}
