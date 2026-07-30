abstract class VerifyJavaOnlyConsumerClasspath : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Classpath
    abstract val consumerClasspath: org.gradle.api.file.ConfigurableFileCollection

    @org.gradle.api.tasks.TaskAction
    fun verifyClasspath() {
        val forbidden =
            consumerClasspath.files
                .map { it.name }
                .filter { it.contains("kotlin", ignoreCase = true) || it.contains("coroutines") }
        check(forbidden.isEmpty()) {
            "Java-only consumer classpath leaked Kotlin dependencies: $forbidden"
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":foundry-java-runtime"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

val javaOnlyConsumerClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(javaOnlyConsumerClasspath.name, project(":foundry-java-runtime"))
}

val compileJavaOnlyConsumerJava by
    tasks.registering(org.gradle.api.tasks.compile.JavaCompile::class) {
        source = fileTree("src/test/fixtures/java-only") { include("**/*.java") }
        classpath = javaOnlyConsumerClasspath
        destinationDirectory = layout.buildDirectory.dir("classes/javaOnlyConsumer")
        options.release = 17
    }

val verifyJavaOnlyConsumerClasspath by tasks.registering(VerifyJavaOnlyConsumerClasspath::class) {
    consumerClasspath.from(javaOnlyConsumerClasspath)
}

val kotlinOverJavaConsumer by sourceSets.creating
val mixedConsumer by sourceSets.creating {
    java.srcDir("src/test/fixtures/mixed")
}

kotlin {
    sourceSets.named(kotlinOverJavaConsumer.name) {
        kotlin.srcDir("src/test/fixtures/kotlin-over-java")
    }
    sourceSets.named(mixedConsumer.name) {
        kotlin.srcDir("src/test/fixtures/mixed")
    }
}

dependencies {
    listOf(kotlinOverJavaConsumer, mixedConsumer).forEach { consumer ->
        add(consumer.implementationConfigurationName, files(sourceSets.main.get().output))
        add(consumer.implementationConfigurationName, project(":foundry-java-runtime"))
        add(consumer.implementationConfigurationName, libs.kotlinx.coroutines.core)
    }
}

tasks.named<Test>("test") {
    dependsOn(
        tasks.named("generatePomFileForMavenJavaPublication"),
        tasks.named("generateMetadataFileForMavenJavaPublication"),
    )
}

val kotlinApiJar =
    layout.buildDirectory.file(
        "libs/foundry-java-kotlin-${project.version}.jar",
    )
val verifyKotlinApi by tasks.registering(org.gradle.api.tasks.Exec::class) {
    dependsOn(tasks.named("jar"))
    inputs.file("verify-kotlin-api.sh")
    inputs.file("api/foundry-java-kotlin.api")
    inputs.file(kotlinApiJar)
    commandLine(
        "bash",
        file("verify-kotlin-api.sh").absolutePath,
        kotlinApiJar.get().asFile.absolutePath,
        file("api/foundry-java-kotlin.api").absolutePath,
    )
}

tasks.named("check") {
    dependsOn(
        compileJavaOnlyConsumerJava,
        verifyJavaOnlyConsumerClasspath,
        verifyKotlinApi,
        tasks.named(kotlinOverJavaConsumer.classesTaskName),
        tasks.named(mixedConsumer.classesTaskName),
    )
}

// This module has no Java sources, so the standard Javadoc task would produce a documentation
// archive containing nothing but a manifest. Until Kotlin documentation generation is added, the
// published archive states where the Kotlin API is documented instead of being silently empty.
val kotlinDocumentationNotice =
    tasks.register("kotlinDocumentationNotice") {
        val notice = layout.buildDirectory.file("docs/kotlin-documentation-notice/README.txt")
        outputs.file(notice)
        doLast {
            val file = notice.get().asFile
            file.parentFile.mkdirs()
            file.writeText(
                "foundry-java-kotlin exposes Kotlin conveniences over the Java API in\n" +
                    "foundry-java-runtime. Its Kotlin API is documented in docs/kotlin-helpers.md\n" +
                    "at https://github.com/cafecito-games/Foundry-Java.\n",
            )
        }
    }

tasks.named<org.gradle.jvm.tasks.Jar>("javadocJar") {
    from(kotlinDocumentationNotice)
}
