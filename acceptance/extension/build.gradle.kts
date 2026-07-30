plugins {
    `java-library`
}

val publishedFoundryVersion: String =
    providers.gradleProperty("foundryVersion").getOrElse("0.1.0")

// The gate's negative self-test builds this same module with the engine class registration
// disabled. Both variants are processed by the same annotation processor and package the same
// descriptor, registry index, keep rules, and bridge ABIs, so the only observable difference is
// whether the engine class the acceptance script resolves through ClassDB exists. A binding that is
// packaged perfectly but never registers must fail the gate.
val registrationDisabled: Boolean =
    providers
        .gradleProperty("foundryJavaRegistrationDisabled")
        .map { it == "true" }
        .getOrElse(false)

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") {
        java.setSrcDirs(
            listOf(if (registrationDisabled) "src/unregistered/java" else "src/registered/java"),
        )
    }
}

dependencies {
    api("games.cafecito.foundry:foundry-java-runtime:$publishedFoundryVersion")
    annotationProcessor(
        "games.cafecito.foundry:foundry-java-processor:$publishedFoundryVersion",
    )
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Afoundry.module=acceptance")
}
