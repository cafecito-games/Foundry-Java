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
        outputs.dir(generatedApiSources)
        outputs.file(generatedCompatibilityManifest)
        outputs.cacheIf("accepted API generation is deterministic") { true }

        args(
            acceptedApiDirectory.asFile.absolutePath,
            generatedApiSources.get().asFile.absolutePath,
            generatedCompatibilityManifest.get().asFile.absolutePath,
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

tasks.named("check") {
    dependsOn(
        verifyRuntimeApi,
        tasks.named("javadoc"),
        generatorProject.tasks.named("test"),
    )
}
