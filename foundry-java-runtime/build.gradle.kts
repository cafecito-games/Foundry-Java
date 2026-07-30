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

        args(
            acceptedApiDirectory.asFile.absolutePath,
            generatedApiSources.get().asFile.absolutePath,
            generatedCompatibilityManifest.get().asFile.absolutePath,
            generatedRealizationMap.get().asFile.absolutePath,
            generatedSurfaceManifest.get().asFile.absolutePath,
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

val runtimeApiBaseline =
    layout.projectDirectory.file("api/foundry-java-runtime.api")
val runtimeApiVerifier =
    rootProject.layout.projectDirectory.file("gradle/verify-runtime-api.sh")
val runtimeClasses = layout.buildDirectory.dir("classes/java/main")

val verifyRuntimeApi =
    tasks.register<Exec>("verifyRuntimeApi") {
        group = "verification"
        description = "Checks the sorted public javap inventory against the frozen runtime API."
        dependsOn(tasks.named("compileJava"))
        inputs.file(runtimeApiBaseline)
        inputs.file(runtimeApiVerifier)
        inputs
            .dir(runtimeClasses)
            .withPathSensitivity(PathSensitivity.RELATIVE)
        commandLine(
            "bash",
            runtimeApiVerifier.asFile.absolutePath,
            runtimeClasses.get().asFile.absolutePath,
            runtimeApiBaseline.asFile.absolutePath,
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
        outputs.dir(realizationReportDirectory)

        args(
            acceptedApiDirectory.asFile.absolutePath,
            generatedRealizationMap.get().asFile.absolutePath,
            runtimeClasses.get().asFile.absolutePath,
            realizationAccountingBaseline.asFile.absolutePath,
            realizationReportDirectory.get().asFile.absolutePath,
            generatedSurfaceManifest.get().asFile.absolutePath,
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
