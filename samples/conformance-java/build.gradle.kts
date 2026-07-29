plugins {
    `java-library`
}

val publishedFoundryVersion: String =
    providers.gradleProperty("foundryVersion").getOrElse("0.1.0")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    // The conformance matrix is authored once and executed twice: on the JVM by this module and on
    // an API 36 device by the consumer application module's instrumentation source set.
    named("test") { java.srcDir("src/conformance/java") }
}

dependencies {
    api("games.cafecito.foundry:foundry-java-runtime:$publishedFoundryVersion")
    annotationProcessor(
        "games.cafecito.foundry:foundry-java-processor:$publishedFoundryVersion",
    )
    testImplementation("junit:junit:4.13.2")
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Afoundry.module=samples-java")
}
